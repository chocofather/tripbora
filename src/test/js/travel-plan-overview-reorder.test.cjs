const {test} = require('node:test');
const assert = require('node:assert/strict');
const {FakeElement, flush} = require('./fake-dom.cjs');
const {targetIndex, persistMove, persistAcrossDays, mount} = require('../../main/resources/static/js/travel-plan-overview-reorder.js');
const PointerSort = require('../../main/resources/static/js/travel-plan-pointer-sort.js');

function state(ids, versions = {}) { return {ids, versions: Object.fromEntries(ids.map(id => [id, versions[id] ?? 0]))}; }
function swap(current, id, direction) {
    const ids = current.ids.slice(), at = ids.indexOf(id), next = at + (direction === 'up' ? -1 : 1), neighbour = ids[next];
    [ids[at], ids[next]] = [ids[next], ids[at]];
    return state(ids, {...current.versions, [id]: current.versions[id] + 1, [neighbour]: current.versions[neighbour] + 2});
}
test('visible gaps resolve against full order including ordinary schedules', () => {
    const ids = ['normal-first', 'a', 'normal-middle', 'b', 'normal-last'];
    assert.equal(targetIndex(ids, 'b', 'start'), 0);
    assert.equal(targetIndex(ids, 'b', 'before', 'a'), 1);
    assert.equal(targetIndex(ids, 'b', 'after', 'a'), 2);
    assert.equal(targetIndex(ids, 'a', 'end'), 4);
    assert.throws(() => targetIndex(ids, 'b', 'before', 'missing'));
});
test('adjacent steps use each latest response and verify every item version', async () => {
    const requests = [];
    const result = await persistMove(state(['a', 'normal', 'b']), 'b', 0, async (current, direction) => {
        requests.push([current.versions.b, direction]); return swap(current, 'b', direction);
    });
    assert.deepEqual(requests, [[0, 'up'], [1, 'up']]); assert.deepEqual(result.ids, ['b', 'a', 'normal']);
    await assert.rejects(persistMove(state(['a', 'b', 'normal']), 'b', 0, async current => {
        const moved = swap(current, 'b', 'up'); moved.versions.normal++; return moved;
    }), /Version changed/);
});
test('redirected 200 unchanged order, concurrent reorder and partial sequences stop immediately', async () => {
    await assert.rejects(persistMove(state(['a', 'b']), 'b', 0, async current => current), /Order changed/);
    let posts = 0;
    await assert.rejects(persistMove(state(['a', 'normal', 'b']), 'b', 0, async current => {
        posts++; if (posts === 2) throw new Error('409'); return swap(current, 'b', 'up');
    }), /409/);
    assert.equal(posts, 2);
});
test('cross DAY validates removal and append before moving through the full target order', async () => {
    const source = state(['a', 'ordinary']), target = state(['normal-first', 'b']);
    const requests = [];
    const result = await persistAcrossDays(source, target, 'a', 0, async () => ({
        source: state(['ordinary']), target: state(['normal-first', 'b', 'a'], {a: 1})
    }), async (current, direction) => { requests.push(current.versions.a); return swap(current, 'a', direction); });
    assert.deepEqual(result.source.ids, ['ordinary']); assert.deepEqual(result.target.ids, ['a', 'normal-first', 'b']);
    assert.deepEqual(requests, [1, 2]);
    await assert.rejects(persistAcrossDays(source, target, 'a', 0, async () => ({source, target}), () => assert.fail()), /Order changed/);
});

class Node extends FakeElement {
    constructor(...args) { super(...args); this.style = {}; }
    matches(selector) {
        return selector.split(',').some(part => {
            part = part.trim();
            const tag = /^[a-z]+/i.exec(part)?.[0];
            if (tag && this.tagName !== tag.toUpperCase()) return false;
            if ([...part.matchAll(/\.([\w-]+)/g)].some(([, name]) => !this.classList.contains(name))) return false;
            return [...part.matchAll(/\[([\w-]+)(\$?=)?["']?([^\]"']*)["']?\]/g)].every(([, name, operation, value]) =>
                this.attributes.has(name) && (!operation || (operation === '$=' ? this.getAttribute(name).endsWith(value) : this.getAttribute(name) === value)));
        });
    }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector) || null; }
    get parentNode() { return this.parent; }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    insertBefore(node, before) { node.remove(); node.parent = this; this.children.splice(before ? this.children.indexOf(before) : this.children.length, 0, node); }
    getBoundingClientRect() { return {top: this.top || 0, left: 0, width: 360, height: 100, bottom: (this.top || 0) + 100}; }
}
function fixture(options = {}) {
    const planner = new Node('main', {'data-plan-id': '1'});
    const document = new Node('document');
    document.createElement = tag => new Node(tag);
    const dayIds = ['7', '8'];
    let server = {7: state(['ordinary-first', 'a', 'ordinary-mid', 'b', 'ordinary-last']), 8: state(['target-normal', 'c'])};
    if (options.emptyTarget) server[8] = state([]);
    const page = () => {
        const root = new Node('main');
        for (const dayId of dayIds) {
            const day = new Node('section', {'data-travel-plan-day': '', 'data-day-id': dayId});
            const list = new Node('ol'); list.className = 'travel-plan-lines'; list.top = dayId === '7' ? 50 : 550;
            day.append(list);
            for (const id of server[dayId].ids) {
                const row = new Node('li', {'data-travel-plan-item': '', 'data-item-id': id, 'data-version': server[dayId].versions[id]});
                row.top = list.top + list.children.length * 100;
                row.append(new Node('button', {'data-item-drag-handle': ''}));
                for (const suffix of ['update', 'move-up', 'move-down']) {
                    const form = new Node('form', {action: `/travel-plans/1/days/${dayId}/items/${id}/${suffix}`});
                    form.action = form.getAttribute('action'); form.version = server[dayId].versions[id]; row.append(form);
                }
                list.append(row);
            }
            root.append(day);
        }
        return root;
    };
    page().children.slice().forEach(day => planner.append(day));
    const groups = dayIds.map((dayId, index) => {
        const summary = new Node('section', {'data-planner-summary': dayId});
        const host = new Node('div', {'data-planner-summary-items': ''}); host.top = index * 500 + 50;
        for (const id of server[dayId].ids.filter(id => ['a', 'b', 'c'].includes(id))) {
            const card = new Node('div', {'data-overview-item-id': id, 'data-type': 'FLIGHT'}); card.top = host.top + host.children.length * 100;
            const handle = new Node('button', {'data-overview-card-drag-handle': ''}); card.append(handle); host.append(card);
        }
        summary.append(host); planner.append(summary); return {dayId, summary, host};
    });
    const calls = [], refreshed = [], alerts = [], images = [];
    let responsePage;
    const window = {alert: message => alerts.push(message), travelPlanOverviewInsert: {isBusy: () => false, isDragging: () => false}, travelPlanRealtime: {
        refreshDay: async dayId => {
            refreshed.push(dayId);
            assert.equal(api.isBusy(), true); assert.equal(groups.find(group => group.dayId === dayId).summary.dataset.overviewReordering, undefined);
            assert.equal(planner.dataset.overviewCardDragging, 'true');
            if (options.failRefresh) throw new Error('offline');
            if (options.skipRefresh) return;
            const old = planner.querySelectorAll('[data-travel-plan-day]').find(day => day.dataset.dayId === dayId);
            old.remove(); planner.append(page().children.find(day => day.dataset.dayId === dayId));
        }
    }};
    class FormData extends Map { constructor(form) { super([['_csrf', 'test-token'], ['version', String(form.version)]]); } }
    class DOMParser { parseFromString() { return responsePage; } }
    const fetch = async (url, request) => {
        calls.push({url, ...request});
        if (options.httpError || calls.length === options.failAt) return {ok: false, status: 409};
        const [, dayId, id, action] = /days\/(\d+)\/items\/([^/]+)\/(.+)$/.exec(url);
        assert.equal(request.body.get('version'), String(server[dayId].versions[id]));
        if (!options.unchanged) {
            if (action === 'move') {
                const destination = request.body.get('targetDayId'), version = server[dayId].versions[id];
                server[dayId] = state(server[dayId].ids.filter(item => item !== id), server[dayId].versions);
                server[destination] = state([...server[destination].ids, id], {...server[destination].versions, [id]: version + 1});
            } else server[dayId] = swap(server[dayId], id, action.endsWith('up') ? 'up' : 'down');
        }
        responsePage = page();
        return {ok: true, text: async () => '<html>server response</html>'};
    };
    const api = mount(planner, {scope: options.detail ? 'detail' : 'overview', document, window, fetch, FormData, DOMParser, PointerSort, CustomEvent: class { constructor(type) { this.type = type; } }});
    const card = options.detail ? planner.querySelectorAll('[data-travel-plan-item]').find(row => row.dataset.itemId === (options.itemId || 'a')) : groups[0].host.children[0];
    const handle = options.detail ? card.querySelector('[data-item-drag-handle]') : card.children[0];
    function event(type, target, extra = {}) {
        const event = {type, target, clientY: 0, clientX: 0, pointerId: 1, button: 0,
            preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; }, ...extra};
        for (const listener of planner.listeners[type] || []) { listener(event); if (event.stopped) break; }
        return event;
    }
    const start = (grip = handle) => { const down = event('pointerdown', grip); event('pointermove', grip, {clientY: 6}); return down; };
    const detailHost = dayId => planner.querySelectorAll('[data-travel-plan-day]').find(day => day.dataset.dayId === dayId).querySelector('.travel-plan-lines');
    return {planner, groups, card, handle, api, window, document, event, start, detailHost, calls, refreshed, alerts, images, server: () => server};
}
test('only the new handle starts a whole-card drag and it never takes the insert/lower grip', () => {
    const f = fixture();
    f.start(f.card); assert.equal(f.api.isDragging(), false);
    const insert = new Node('button', {'data-overview-insert-handle': ''}); f.planner.append(insert);
    assert.equal(f.start(insert).stopped, undefined);
    const lower = new Node('button', {'data-item-drag-handle': ''}); f.planner.append(lower);
    assert.equal(f.start(lower).stopped, undefined);
    assert.equal(f.start().stopped, true); assert.equal(f.api.isDragging(), true);
    assert.equal(f.card.style.opacity, '1'); assert.equal(f.card.getAttribute('aria-busy'), 'true');
    assert.equal(f.event('click', f.card).defaultPrevented, true);
    assert.equal(f.event('submit', f.planner).defaultPrevented, true);
    f.event('pointercancel', f.handle); assert.equal(f.api.isDragging(), false); assert.equal(f.card.getAttribute('aria-busy'), null);
});
test('editors, drafts, insert drag, opening and remote editing reject drag before any POST', () => {
    for (const mode of ['editor', 'draft', 'busy', 'opening', 'insert', 'remote', 'alternative']) {
        const f = fixture();
        if (mode === 'opening') f.planner.dataset.editorOpening = 'true';
        else if (mode === 'insert') f.window.travelPlanOverviewInsert.isDragging = () => true;
        else if (mode === 'remote') f.window.travelPlanRealtime.isLockedByOther = () => true;
        else if (mode === 'alternative') f.window.travelPlanRealtime.hasAlternativeEditing = () => true;
        else { const node = new Node('div'); if (mode === 'busy') node.setAttribute('aria-busy', 'true'); else node.className = mode === 'draft' ? 'is-draft' : 'is-editing'; f.planner.append(node); }
        assert.equal(f.start().defaultPrevented, true, mode); assert.equal(f.api.isDragging(), false); assert.equal(f.calls.length, 0);
    }
});
test('same DAY drop updates overview immediately while full authoritative rows retain order until refresh', async () => {
    const f = fixture(); f.start();
    f.event('pointerup', f.groups[0].host, {clientY: 400});
    assert.equal(f.groups[0].host.children.at(-1), f.card);
    assert.deepEqual(f.planner.querySelectorAll('[data-travel-plan-item]').slice(0, 5).map(row => row.dataset.itemId), ['ordinary-first', 'a', 'ordinary-mid', 'b', 'ordinary-last']);
    await flush();
    assert.deepEqual(f.server()[7].ids, ['ordinary-first', 'ordinary-mid', 'b', 'ordinary-last', 'a']);
    assert.equal(f.calls.length, 3); assert.deepEqual(f.refreshed, ['7']); assert.equal(f.alerts.length, 0); assert.equal(f.api.isBusy(), false);
    assert.equal(f.calls[0].credentials, 'same-origin'); assert.equal(f.calls[0].body.get('_csrf'), 'test-token');
});
test('cross DAY uses existing move endpoint then refreshed forms and refreshes both DAYs', async () => {
    const f = fixture(); f.start();
    f.event('pointerup', f.groups[1].host, {clientY: 550}); await flush();
    assert.match(f.calls[0].url, /days\/7\/items\/a\/move$/); assert.equal(f.calls[0].body.get('targetDayId'), '8');
    assert.equal(f.calls[0].body.get('_csrf'), 'test-token'); assert.equal(f.calls[1].body.get('version'), '1');
    assert.deepEqual(f.server()[8].ids, ['a', 'target-normal', 'c']); assert.deepEqual(f.refreshed, ['7', '8']); assert.equal(f.alerts.length, 0);
});
test('HTTP 409 and redirected 200 with no move always read back and prohibit automatic retry', async () => {
    for (const options of [{httpError: true}, {unchanged: true}]) {
        const f = fixture(options); f.start(); f.event('pointerup', f.groups[1].host, {clientY: 900}); await flush();
        assert.equal(f.calls.length, 1); assert.deepEqual(f.refreshed, ['7', '8']); assert.equal(f.alerts.length, 1); assert.equal(f.api.isBusy(), true);
        assert.equal(f.start().defaultPrevented, true); assert.equal(f.calls.length, 1);
    }
});
test('failed or silently deferred authoritative refresh produces a reload warning after accepted movement', async () => {
    for (const options of [{failRefresh: true}, {skipRefresh: true}]) {
        const f = fixture(options); f.start(); f.event('pointerup', f.groups[0].host, {clientY: 400}); await flush();
        assert.equal(f.alerts.length, 1); assert.equal(f.api.isBusy(), true); assert.equal(f.card.getAttribute('aria-busy'), null);
    }
});
test('a cross DAY partial sequence reads back both DAYs without repeating the saved transfer', async () => {
    const f = fixture({failAt: 2}); f.start();
    f.event('pointerup', f.groups[1].host, {clientY: 550}); await flush();
    assert.equal(f.calls.length, 2); assert.deepEqual(f.refreshed, ['7', '8']);
    assert.equal(f.server()[7].ids.includes('a'), false); assert.deepEqual(f.server()[8].ids, ['target-normal', 'c', 'a']);
    assert.equal(f.alerts.length, 1); assert.equal(f.api.isBusy(), true);
});
test('target DAY remote lock arriving after drag rejects drop without changing either DAY', async () => {
    const f = fixture(); f.start();
    f.window.travelPlanRealtime.isLockedByOther = spot => spot.dayId === '8';
    f.event('pointerup', f.groups[1].host, {clientY: 900}); await flush();
    assert.equal(f.calls.length, 0); assert.equal(f.api.isDragging(), false); assert.equal(f.api.isBusy(), false);
});

test('detail ordinary row reorders through the complete persisted list and refreshes its shared summary', async () => {
    const f = fixture({detail: true, itemId: 'ordinary-first'}); f.start();
    assert.equal(f.api.isDragging(), true); assert.equal(f.card.style.opacity, '1');
    f.event('pointerup', f.detailHost('7'), {clientY: 1000}); await flush();
    assert.deepEqual(f.server()[7].ids, ['a', 'ordinary-mid', 'b', 'ordinary-last', 'ordinary-first']);
    assert.deepEqual(f.refreshed, ['7']); assert.equal(f.calls.length, 4); assert.equal(f.alerts.length, 0);
});

test('detail transfer into an empty DAY persists through the existing move route', async () => {
    const f = fixture({detail: true, emptyTarget: true}); f.start();
    f.event('pointerup', f.detailHost('8'), {clientY: 580}); await flush();
    assert.equal(f.calls.length, 1); assert.match(f.calls[0].url, /days\/7\/items\/a\/move$/);
    assert.deepEqual(f.server()[8].ids, ['a']); assert.deepEqual(f.refreshed, ['7', '8']); assert.equal(f.alerts.length, 0);
});

test('detail target headings are invalid and pointer cancellation restores source without saving', () => {
    const f = fixture({detail: true});
    f.start(); f.event('pointerup', f.groups[1].summary, {clientY: 500});
    assert.equal(f.calls.length, 0); assert.equal(f.api.isDragging(), false);
    f.start(); f.event('pointermove', f.detailHost('8'), {clientY: 580});
    assert.match(f.card.style.transform, /^translateY\(/);
    f.event('pointercancel', f.handle);
    assert.equal(f.card.parentNode, f.detailHost('7')); assert.equal(f.calls.length, 0);
    assert.equal(f.planner.querySelector('.tp-pointer-sort-gap'), null);
});

test('detail conflicts read back both DAYs and never retry a partially accepted transfer', async () => {
    const f = fixture({detail: true, failAt: 2}); f.start();
    f.event('pointerup', f.detailHost('8'), {clientY: 550}); await flush();
    assert.equal(f.calls.length, 2); assert.deepEqual(f.server()[8].ids, ['target-normal', 'c', 'a']);
    assert.deepEqual(f.refreshed, ['7', '8']); assert.equal(f.api.isBusy(), true); assert.equal(f.alerts.length, 1);
});
