const {test} = require('node:test');
const assert = require('node:assert/strict');
const {FakeElement} = require('./fake-dom.cjs');
const {mount} = require('../../main/resources/static/js/travel-plan-overview-insert.js');

class Node extends FakeElement {
    constructor(...args) { super(...args); this.style = {}; }
    getAttributeNames() { return [...this.attributes.keys()]; }
    cloneNode(deep) {
        const clone = new Node(this.tagName, Object.fromEntries(this.attributes));
        clone.className = this.className; clone.textContent = this.textContent; clone.hidden = this.hidden;
        if (deep) clone.append(...this.children.map(child => child.cloneNode(true)));
        return clone;
    }
    matches(selector) {
        return selector.split(',').some(part => {
            part = part.trim();
            const classes = [...part.matchAll(/\.([\w-]+)/g)].map(match => match[1]);
            if (classes.some(name => !this.classList.contains(name))) return false;
            const attrs = [...part.matchAll(/\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]/g)];
            if (attrs.some(([, key, value]) => !this.attributes.has(key) || (value !== undefined && this.getAttribute(key) !== value))) return false;
            const tag = /^[a-z]+/i.exec(part)?.[0];
            return !tag || this.tagName === tag.toUpperCase();
        });
    }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector) || null; }
    get parentNode() { return this.parent; }
    remove() { super.remove(); this.parent = null; }
    insertBefore(node, before) {
        node.remove();
        node.parent = this;
        const index = before ? this.children.indexOf(before) : this.children.length;
        assert.ok(index >= 0);
        this.children.splice(index, 0, node);
    }
    getBoundingClientRect() { const top = this.top || 0; const height = this.height || 100; return {top, left: this.left || 0, width: this.width || 360, height, bottom: top + height}; }
    focus() { this.focused = true; }
}
const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return {promise, resolve, reject};
};
const tick = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
function fixture(options = {}) {
    const make = (tag, attrs) => new Node(tag, attrs);
    const planner = make('main', {'data-plan-id': '1'});
    const summaries = make('div', {'data-planner-summaries': ''});
    const bar = make('div', {'data-overview-insert-bar': '', 'data-day-id': '9', 'data-before-item-id': '', 'data-insert-placement': 'end', 'data-insert-default': 'true'});
    bar.height = 46; bar.className = 'tp-overview-insert-bar';
    const grip = make('button', {'data-overview-insert-handle': ''});
    const add = make('button', {'data-overview-insert-type': 'FLIGHT'});
    const status = make('span', {'data-overview-insert-status': ''});
    const location = make('button', {'data-overview-insert-location': ''});
    const menu = make('div', {'data-overview-insert-menu': ''}); menu.hidden = true;
    bar.append(grip, add, status, location, menu);
    const data = options.data || [['7', ['normal-first', 'a', 'normal-middle', 'b', 'normal-last']], ['8', []], ['9', ['c', 'normal-nine']]];
    const groups = data.map(([id, ids], dayIndex) => {
        const summary = make('section', {'data-planner-summary': id}); summary.top = dayIndex * 600;
        const host = make('div', {'data-planner-summary-items': ''}); host.top = summary.top + 100;
        const day = make('section', {'data-travel-plan-day': '', 'data-day-id': id});
        const cards = [];
        ids.forEach(itemId => {
            const row = make('li', {'data-travel-plan-item': '', 'data-item-id': itemId}); day.append(row);
            if (itemId.startsWith('normal')) return;
            const card = make('div', {'data-overview-item-id': itemId}); card.className = 'tp-overview-item';
            card.top = summary.top + 150 + cards.length * 150; cards.push(card); host.append(card);
        });
        const slot = make('div', {'data-travel-plan-slot': '', 'data-day-id': id});
        const form = make('form', {'data-travel-plan-slot-form': ''}); form.action = `/travel-plans/1/days/${id}/items`; slot.append(form); day.append(slot);
        summary.append(host); summaries.append(summary); planner.append(day);
        return {id, summary, host, day, slot, form, cards};
    });
    bar.dataset.dayId = groups.at(-1).id; groups.at(-1).host.append(bar); planner.append(summaries);
    const {host, cards, form} = groups[0];
    const document = make('document'); document.createElement = tag => make(tag);
    const calls = [], locks = [], releases = [], reloads = [];
    const window = {location: {reload: () => reloads.push(true)}, travelPlanRealtime: {
        requestLock: async spot => { locks.push(spot); return {granted: true}; },
        releaseLock: () => releases.push(true),
        refreshDay: async dayId => {
            const row = make('li', {'data-travel-plan-item': '', 'data-item-id': 'new'}); groups.find(group => group.id === dayId).day.append(row);
        }
    }};
    Object.assign(window.travelPlanRealtime, options.live);
    class FormData extends Map {
        constructor(source) { super([['_csrf', 'test-csrf'], ['beforeItemId', 'stale']]); assert.ok(groups.some(group => group.form === source)); }
    }
    const fetch = options.fetch || (async (url, request) => { calls.push({url, ...request}); return {ok: true, json: async () => ({itemId: 'new'})}; });
    const api = mount(planner, {document, window, FormData, fetch, CustomEvent: class {constructor(type) { this.type = type; }}});
    function event(type, target, extra = {}) {
        const event = {type, target, pointerId: 1, button: 0, clientX: 0, clientY: 0, preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; }, ...extra};
        for (const listener of planner.listeners[type] || []) { listener(event); if (event.stopped) break; }
        return event;
    }
    const start = (target = grip, extra = {}) => {
        const down = event('pointerdown', target, extra);
        event('pointermove', target, {clientY: (extra.clientY || 0) + 6, clientX: extra.clientX || 0});
        return down;
    };
    return {planner, summaries, groups, host, bar, grip, add, status, location, menu, cards, document, window, api, calls, locks, releases, reloads, event, start};
}

test('one global bar defaults after the last DAY and keyboard moves across DAYs including an empty DAY', () => {
    const f = fixture();
    assert.equal(f.groups.at(-1).host.children.at(-1), f.bar); assert.equal(f.bar.dataset.insertPlacement, 'end');
    assert.equal(f.bar.dataset.dayId, '9');
    f.event('keydown', f.grip, {key: 'Home'});
    f.event('keydown', f.grip, {key: 'ArrowDown'});
    assert.equal(f.bar.dataset.beforeItemId, 'a'); assert.equal(f.bar.dataset.insertPlacement, 'before');
    f.event('keydown', f.grip, {key: 'End'});
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    f.event('keydown', f.grip, {key: 'ArrowUp'}); f.event('keydown', f.grip, {key: 'ArrowUp'});
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertPlacement, 'start');
    f.event('keydown', f.grip, {key: 'ArrowUp'});
    assert.equal(f.bar.dataset.dayId, '8'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    f.event('keydown', f.grip, {key: 'Home'});
    assert.equal(f.host.children[0], f.bar);
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
});

test('handle-only drag crosses DAY content areas and rejects headings', () => {
    const f = fixture();
    f.start(f.cards[0]);
    f.event('pointermove', f.cards[1], {clientY: 151});
    assert.equal(f.bar.dataset.insertPlacement, 'end'); assert.equal(f.bar.dataset.dayId, '9');
    assert.equal(f.start().stopped, true);
    f.event('pointermove', f.cards[1], {clientY: 301});
    assert.equal(f.bar.dataset.beforeItemId, ''); assert.equal(f.bar.parentNode, f.groups[2].host);
    f.event('pointermove', f.cards[1], {clientY: 399});
    assert.equal(f.bar.dataset.insertPlacement, 'end');
    f.event('pointermove', f.groups[1].host, {clientY: 700});
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    f.event('pointermove', f.groups[2].summary, {clientY: 1201});
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    assert.equal(f.planner.querySelectorAll('.is-insert-target').length, 0);
    f.event('pointercancel', f.grip);
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertDefault, 'true');
});

test('pointer sensor activates only after 6px, ignores other pointers and never starts on add buttons', () => {
    const f = fixture();
    f.event('pointerdown', f.add, {clientX: 10, clientY: 20});
    f.event('pointermove', f.add, {clientX: 40, clientY: 60});
    assert.equal(f.api.isDragging(), false);
    f.event('pointerdown', f.grip, {clientX: 10, clientY: 20});
    f.event('pointermove', f.grip, {clientX: 10, clientY: 25});
    assert.equal(f.api.isDragging(), false); assert.equal(f.bar.style.position, undefined);
    f.event('pointermove', f.grip, {pointerId: 2, clientY: 900});
    assert.equal(f.api.isDragging(), false);
    f.event('pointermove', f.grip, {clientX: 10, clientY: 26});
    assert.equal(f.api.isDragging(), true); assert.equal(f.bar.style.transform, 'translateY(6px)');
    f.event('pointerup', f.grip, {pointerId: 2}); assert.equal(f.api.isDragging(), true);
    f.event('pointercancel', f.grip); assert.equal(f.api.isDragging(), false);
    assert.equal(f.calls.length, 0);
});

test('captured pointer hit testing uses the underlying DAY, and capture loss cancels without saving', () => {
    const f = fixture(); let held = false;
    f.grip.setPointerCapture = id => { assert.equal(id, 1); held = true; };
    f.grip.hasPointerCapture = () => held;
    f.grip.releasePointerCapture = () => { held = false; };
    f.document.elementFromPoint = () => f.groups[1].host;
    f.start(); assert.equal(held, true);
    f.event('pointermove', f.grip, {clientY: 700});
    f.event('pointerup', f.grip, {clientY: 700});
    assert.equal(f.bar.dataset.dayId, '8'); assert.equal(held, false);
    f.start(); f.event('lostpointercapture', f.grip);
    assert.equal(f.api.isDragging(), false); assert.equal(f.bar.dataset.dayId, '8');
    assert.equal(f.planner.querySelector('.tp-overview-insert-placeholder'), null);
    assert.equal(f.calls.length, 0);
});

test('pending card appears before awaiting lock and request preserves CSRF and requested anchor', async () => {
    const wait = deferred();
    const f = fixture({live: {requestLock: () => wait.promise}});
    f.event('keydown', f.grip, {key: 'Home'});
    f.event('click', f.add);
    const pending = f.host.querySelector('.tp-overview-pending');
    assert.ok(pending); assert.equal(f.host.children[0], pending); assert.equal(f.host.children[1], f.bar);
    assert.equal(f.calls.length, 0);
    const duplicate = f.event('click', f.add); assert.equal(duplicate.defaultPrevented, true);
    assert.equal(f.event('submit', f.planner).defaultPrevented, true);
    assert.equal(f.start().defaultPrevented, true);
    wait.resolve({granted: true}); await tick();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].body.get('_csrf'), 'test-csrf');
    assert.equal(f.calls[0].body.get('beforeItemId'), 'normal-first');
    assert.equal(f.calls[0].body.get('emptyCard'), 'true');
    assert.equal(f.calls[0].body.get('itemType'), 'FLIGHT');
    assert.equal(f.calls[0].body.get('content'), '');
    assert.equal(f.calls[0].headers['X-Requested-With'], 'XMLHttpRequest');
    assert.equal(f.host.querySelector('.tp-overview-pending'), null);
    assert.equal(f.releases.length, 1); assert.equal(f.planner.dataset.editorOpening, undefined);
});

test('append removes stale anchor copied from form and obtains ADD lock', async () => {
    const f = fixture(); f.event('keydown', f.grip, {key: 'Home'}); f.event('keydown', f.grip, {key: 'ArrowDown'}); f.event('keydown', f.grip, {key: 'ArrowDown'}); f.event('keydown', f.grip, {key: 'ArrowDown'});
    assert.equal(f.bar.dataset.insertPlacement, 'end'); f.event('click', f.add); await tick();
    assert.equal(f.calls[0].body.has('beforeItemId'), false);
    assert.deepEqual(f.locks, [{mode: 'ADD', dayId: '7', itemId: null}]);
});

test('active editor rejects addition without autosaving or requesting lock', async () => {
    const f = fixture(); const editor = new Node('form'); editor.className = 'is-editing'; f.planner.append(editor);
    f.event('click', f.add); await tick();
    assert.equal(f.calls.length, 0); assert.equal(f.locks.length, 0); assert.equal(f.host.querySelector('.tp-overview-pending'), null);
    assert.match(f.status.textContent, /편집/);
});

test('draft, busy operation and an opening editor also reject addition', async () => {
    for (const state of ['draft', 'busy', 'opening']) {
        const f = fixture();
        if (state === 'opening') f.planner.dataset.editorOpening = 'true';
        else {
            const node = new Node('div');
            if (state === 'draft') node.className = 'is-draft';
            else node.setAttribute('aria-busy', 'true');
            f.planner.append(node);
        }
        f.event('click', f.add); await tick();
        assert.equal(f.calls.length, 0); assert.equal(f.locks.length, 0);
    }
});

test('busy guard remains active after POST acceptance until authoritative refresh completes', async () => {
    const wait = deferred();
    const f = fixture({live: {refreshDay: () => wait.promise}});
    f.event('click', f.add); await tick();
    assert.equal(f.calls.length, 1); assert.equal(f.api.isBusy(), true);
    assert.equal(f.host.querySelector('.tp-overview-pending'), null);
    assert.equal(f.event('click', f.add).defaultPrevented, true);
    assert.equal(f.event('submit', f.planner).defaultPrevented, true);
    f.groups.at(-1).day.append(new Node('li', {'data-travel-plan-item': '', 'data-item-id': 'new'}));
    wait.resolve(); await tick();
    assert.equal(f.api.isBusy(), false); assert.equal(f.calls.length, 1);
});

test('lock denial cleans pending without sending POST', async () => {
    const f = fixture({live: {requestLock: async () => ({granted: false})}});
    f.event('click', f.add); await tick();
    assert.equal(f.calls.length, 0); assert.equal(f.releases.length, 0);
    assert.equal(f.host.querySelector('.tp-overview-pending'), null); assert.match(f.status.textContent, /참여자/);
});

test('network uncertainty blocks duplicate POST until reload and releases lock', async () => {
    let posts = 0;
    const f = fixture({fetch: async () => { posts++; throw new Error('lost connection'); }});
    f.event('click', f.add); await tick(); f.event('click', f.add); await tick();
    assert.equal(posts, 1); assert.equal(f.releases.length, 1);
    assert.match(f.status.textContent, /새로고침/); assert.equal(f.host.querySelector('.tp-overview-pending'), null);
});

test('accepted save with failed or deferred refresh never repeats POST', async () => {
    for (const refreshDay of [async () => { throw new Error('offline'); }, async () => {}]) {
        const f = fixture({live: {refreshDay}});
        f.event('click', f.add); await tick(); f.event('click', f.add); await tick();
        assert.equal(f.calls.length, 1); assert.equal(f.releases.length, 1);
        assert.match(f.status.textContent, /저장.*새로고침/); assert.equal(f.host.querySelector('.tp-overview-pending'), null);
    }
});

test('explicit HTTP rejection permits deliberate retry and always unlocks', async () => {
    let posts = 0;
    const f = fixture({fetch: async () => { posts++; return {ok: false, text: async () => '<html>bad</html>'}; }});
    f.event('click', f.add); await tick(); f.event('click', f.add); await tick();
    assert.equal(posts, 2); assert.equal(f.releases.length, 2);
    assert.equal(f.status.textContent.includes('<html>'), false);
});

test('server error response requires readback before any new creation', async () => {
    let posts = 0;
    const f = fixture({fetch: async () => { posts++; return {ok: false, status: 500}; }});
    f.event('click', f.add); await tick(); f.event('click', f.add); await tick();
    assert.equal(posts, 1); assert.match(f.status.textContent, /새로고침/);
});

test('standalone page without realtime reloads after one accepted save', async () => {
    const f = fixture(); delete f.window.travelPlanRealtime;
    f.event('click', f.add); await tick();
    assert.equal(f.calls.length, 1); assert.equal(f.reloads.length, 1);
    assert.equal(f.api.isBusy(), false); assert.equal(f.host.querySelector('.tp-overview-pending'), null);
});

test('renderer hooks preserve the same global bar through every host replacement', () => {
    const f = fixture();
    f.event('keydown', f.grip, {key: 'Home'});
    f.event('keydown', f.grip, {key: 'ArrowDown'});
    f.event('keydown', f.grip, {key: 'ArrowDown'});
    const anchor = f.bar.dataset.beforeItemId;
    for (const group of f.groups) {
        f.api.beforeRefresh(group.host);
        assert.notEqual(f.bar.parentNode, f.summaries);
        const updated = group.cards.map(card => new Node('div', {'data-overview-item-id': card.dataset.overviewItemId}));
        group.host.replaceChildren(...updated);
    }
    f.api.restore();
    assert.equal(f.host.children.find(node => node.dataset.overviewInsertBar !== undefined), f.bar);
    assert.equal(f.bar.dataset.beforeItemId, anchor); assert.equal(f.bar.dataset.dayId, '7');
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
    assert.equal(f.window.travelPlanOverviewInsert, f.api);
});

test('removed or moved anchor falls back to the end of its selected DAY', () => {
    const f = fixture();
    f.event('keydown', f.grip, {key: 'Home'});
    f.event('keydown', f.grip, {key: 'ArrowDown'});
    const anchor = f.groups[0].day.querySelectorAll('[data-travel-plan-item]').find(row => row.dataset.itemId === 'a');
    anchor.remove(); f.groups[2].day.append(anchor);
    f.api.beforeRefresh(f.host); f.cards[0].remove(); f.api.restore();
    assert.equal(f.bar.dataset.dayId, '7'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    assert.equal(f.host.children.at(-1), f.bar); assert.match(f.status.textContent, /같은 날짜/);
});

test('click resolves current full order for start, then freezes day and anchor while lock is pending', async () => {
    const wait = deferred(); const f = fixture({live: {requestLock: () => wait.promise}});
    f.event('keydown', f.grip, {key: 'Home'});
    const first = new Node('li', {'data-travel-plan-item': '', 'data-item-id': 'latest-first'});
    f.groups[0].day.insertBefore(first, f.groups[0].day.children[0]);
    f.event('click', f.add);
    assert.equal(f.bar.dataset.beforeItemId, 'latest-first');
    first.remove(); f.api.restore();
    assert.equal(f.bar.dataset.beforeItemId, 'normal-first');
    wait.resolve({granted: true}); await tick();
    assert.equal(f.calls[0].body.get('beforeItemId'), 'latest-first');
    assert.match(f.calls[0].url, /days\/7\/items$/);
});

test('mobile location menu selects cross-DAY empty/end placement and closes on Escape and outside click', async () => {
    const f = fixture(); f.event('click', f.location);
    assert.equal(f.menu.hidden, false);
    const empty = f.menu.children.find(button => button.textContent === 'DAY 2 마지막');
    assert.ok(empty); f.event('click', empty);
    assert.equal(f.menu.hidden, true); assert.equal(f.bar.dataset.dayId, '8');
    assert.equal(f.bar.dataset.insertPlacement, 'end'); assert.equal(f.bar.parentNode, f.groups[1].host);
    f.event('click', f.location); f.event('keydown', f.location, {key: 'Escape'}); assert.equal(f.menu.hidden, true);
    f.event('click', f.location); f.event('click', f.groups[0].summary); assert.equal(f.menu.hidden, true);
    f.event('click', f.add); await tick();
    assert.match(f.calls[0].url, /days\/8\/items$/); assert.equal(f.calls[0].body.has('beforeItemId'), false);
    assert.deepEqual(f.locks, [{mode: 'ADD', dayId: '8', itemId: null}]);
});

test('a menu opened before live changes preserves its chosen DAY instead of using changed list indexes', () => {
    const f = fixture(); f.event('click', f.location);
    const target = f.menu.children.find(button => button.textContent === 'DAY 2 마지막');
    f.cards[0].remove();
    f.event('click', target);
    assert.equal(f.bar.dataset.dayId, '8'); assert.equal(f.bar.dataset.insertPlacement, 'end');
});

test('drag gap uses next visible item as anchor while hidden ordinary rows retain their order', async () => {
    const f = fixture();
    f.start();
    f.event('pointermove', f.cards[1], {clientY: 301});
    f.event('pointerup', f.cards[1], {clientY: 301});
    assert.equal(f.bar.dataset.beforeItemId, 'b'); assert.equal(f.bar.classList.contains('is-insert-dragging'), false);
    f.event('click', f.add); await tick();
    assert.equal(f.calls[0].body.get('beforeItemId'), 'b');
    assert.deepEqual(f.groups[0].day.querySelectorAll('[data-travel-plan-item]').slice(0, 5).map(row => row.dataset.itemId),
        ['normal-first', 'a', 'normal-middle', 'b', 'normal-last']);
});

test('created overview card keeps its entry animation after the final renderer refresh', async () => {
    const f = fixture();
    f.event('keydown', f.grip, {key: 'Home'});
    f.document.addEventListener('travelplan:schedule-updated', () => {
        f.api.beforeRefresh(f.host);
        const created = new Node('div', {'data-overview-item-id': 'new'});
        f.host.replaceChildren(...f.cards, created); f.api.restore();
    });
    f.event('click', f.add); await tick();
    assert.equal(f.host.querySelectorAll('[data-overview-item-id]').find(card => card.dataset.overviewItemId === 'new').classList.contains('tp-item-enter'), true);
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
});

test('drag moves the real bar without a clone and summary footer is an invalid target', () => {
    const f = fixture(); f.start();
    assert.equal(f.planner.querySelector('.tp-overview-insert-ghost'), null);
    f.event('pointermove', f.cards[1], {clientY: 301});
    f.event('pointermove', f.groups[2].summary, {clientY: f.groups[2].host.getBoundingClientRect().bottom + 20});
    assert.equal(f.planner.querySelectorAll('.is-insert-target').length, 0);
    f.event('pointerup', f.groups[2].summary, {clientY: f.groups[2].host.getBoundingClientRect().bottom + 20});
    assert.equal(f.bar.parentNode, f.groups[2].host);
    assert.equal(f.bar.classList.contains('is-insert-dragging'), false);
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
});

test('one, two, three and five DAY trips all default to their actual empty final DAY', () => {
    for (const count of [1, 2, 3, 5]) {
        const f = fixture({data: Array.from({length: count}, (_, index) => [String(index + 1), []])});
        assert.equal(f.bar.dataset.dayId, String(count));
        assert.equal(f.bar.dataset.insertPlacement, 'end');
        assert.equal(f.groups.at(-1).host.children.at(-1), f.bar);
        assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
    }
});

test('default follows the last actual DAY including a sparse numbered empty final DAY', () => {
    const f = fixture({data: [['1', ['a']], ['2', []], ['3', ['b']], ['5', []]]});
    assert.equal(f.bar.dataset.dayId, '5'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    assert.equal(f.groups.at(-1).host.children.at(-1), f.bar);
    const summary = new Node('section', {'data-planner-summary': '6'});
    const host = new Node('div', {'data-planner-summary-items': ''}); summary.append(host); f.summaries.append(summary);
    f.api.restore();
    assert.equal(f.bar.dataset.dayId, '6'); assert.equal(host.children.at(-1), f.bar);
    assert.equal(f.bar.dataset.insertDefault, 'true');
});

test('manual position survives new final DAYs and falls back safely only when its DAY disappears', () => {
    const f = fixture(); f.event('keydown', f.grip, {key: 'Home'}); f.event('keydown', f.grip, {key: 'ArrowDown'});
    assert.equal(f.bar.dataset.insertDefault, 'false');
    const summary = new Node('section', {'data-planner-summary': '10'});
    const host = new Node('div', {'data-planner-summary-items': ''}); summary.append(host); f.summaries.append(summary);
    f.api.beforeRefresh(f.host); f.api.restore();
    assert.equal(f.bar.dataset.dayId, '7'); assert.equal(f.bar.dataset.beforeItemId, 'a');
    f.api.beforeRefresh(f.host); f.groups[0].summary.remove(); f.groups[0].day.remove(); f.api.restore();
    assert.equal(f.bar.dataset.dayId, '10'); assert.equal(f.bar.dataset.insertPlacement, 'end');
    assert.equal(f.bar.dataset.insertDefault, 'false'); assert.match(f.status.textContent, /날짜.*마지막/);
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
});

test('insert drag exposes mutual exclusion state and clears it on release, Escape and cancellation', () => {
    const f = fixture();
    for (const end of ['pointerup', 'keydown', 'pointercancel']) {
        f.start();
        assert.equal(f.api.isDragging(), true); assert.equal(f.planner.dataset.overviewInsertDragging, 'true');
        f.event(end, end === 'pointerup' ? f.groups[1].host : f.grip, {key: 'Escape', clientY: 700});
        assert.equal(f.api.isDragging(), false); assert.equal(f.planner.dataset.overviewInsertDragging, undefined);
    }
});

test('default addition targets an empty last actual DAY without a before-item anchor', async () => {
    const wait = deferred();
    const f = fixture({data: [['1', ['a']], ['2', []], ['3', ['b']], ['5', []]], live: {requestLock: () => wait.promise}});
    f.event('click', f.add);
    assert.ok(f.groups.at(-1).host.querySelector('.tp-overview-pending'));
    assert.equal(f.groups.at(-1).host.children.at(-1), f.bar);
    wait.resolve({granted: true}); await tick();
    assert.match(f.calls[0].url, /days\/5\/items$/); assert.equal(f.calls[0].body.has('beforeItemId'), false);
    assert.equal(f.bar.dataset.insertDefault, 'true');
});

test('upper card dragging and busy refresh reject insertion, menu movement and grip dragging', async () => {
    for (const mode of ['dragging-marker', 'dragging-api', 'refreshing-api']) {
        const f = fixture();
        if (mode === 'dragging-marker') f.planner.dataset.overviewCardDragging = 'true';
        else f.window.travelPlanOverviewReorder = {isBusy: () => mode === 'refreshing-api', isDragging: () => mode === 'dragging-api'};
        f.event('click', f.add); await tick();
        assert.equal(f.calls.length, 0); assert.equal(f.locks.length, 0);
        f.event('keydown', f.grip, {key: 'Home'}); f.event('click', f.location);
        assert.equal(f.bar.dataset.insertDefault, 'true'); assert.equal(f.menu.hidden, true);
        assert.equal(f.start().defaultPrevented, true);
        assert.equal(f.api.isDragging(), false);
    }
});

test('all DAY headings reject drag candidates and cancelled drag restores its original manual position', () => {
    for (const cancel of ['pointerup', 'pointercancel', 'Escape']) {
        const f = fixture(); f.event('keydown', f.grip, {key: 'Home'}); f.event('keydown', f.grip, {key: 'ArrowDown'});
        f.start();
        f.event('pointermove', f.groups[1].host, {clientY: 700});
        assert.equal(f.bar.dataset.dayId, '7');
        for (const group of f.groups) {
            f.event('pointermove', group.summary, {clientY: group.summary.top});
            assert.equal(f.planner.querySelectorAll('.is-insert-target').length, 0);
        }
        if (cancel === 'Escape') f.event('keydown', f.grip, {key: 'Escape'});
        else f.event(cancel, cancel === 'pointerup' ? f.groups[1].summary : f.grip);
        assert.equal(f.bar.dataset.dayId, '7'); assert.equal(f.bar.dataset.insertPlacement, 'before');
        assert.equal(f.bar.dataset.beforeItemId, 'a'); assert.equal(f.bar.dataset.insertDefault, 'false');
        assert.equal(f.bar.parentNode, f.host); assert.equal(f.api.isDragging(), false);
    }
});

test('valid content drops commit start, between, end and empty DAY positions without leaving DAY hosts', () => {
    const f = fixture();
    const cases = [
        {target: f.cards[0], y: 151, dayId: '7', placement: 'start', anchor: 'normal-first'},
        {target: f.cards[1], y: 301, dayId: '7', placement: 'before', anchor: 'b'},
        {target: f.cards[1], y: 450, dayId: '7', placement: 'end', anchor: ''},
        {target: f.groups[1].host, y: 700, dayId: '8', placement: 'end', anchor: ''}
    ];
    for (const target of cases) {
        f.start();
        f.event('pointermove', target.target, {clientY: target.y});
        assert.equal(f.bar.parentNode, f.groups.find(group => group.id === f.bar.dataset.dayId).host);
        assert.equal(f.planner.querySelector('.tp-overview-insert-placeholder').parentNode, f.groups.find(group => group.id === target.dayId).host);
        assert.equal(f.event('pointerup', target.target, {clientY: target.y}).defaultPrevented, true);
        assert.equal(f.bar.dataset.dayId, target.dayId); assert.equal(f.bar.dataset.insertPlacement, target.placement);
        assert.equal(f.bar.dataset.beforeItemId, target.anchor); assert.equal(f.bar.dataset.insertDefault, 'false');
        assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
    }
});

test('pointer drag keeps one real full-size bar, freezes data, and creates no item or ghost', () => {
    const f = fixture(); const sourceParent = f.bar.parentNode;
    f.bar.setAttribute('id', 'only-source'); f.add.setAttribute('id', 'only-add'); f.add.setAttribute('name', 'add-item');
    f.bar.style.width = '90%'; f.bar.style.opacity = '.95';
    f.start(f.grip, {clientX: 400, clientY: 900});
    assert.equal(f.bar.style.width, '360px'); assert.equal(f.bar.style.height, '46px');
    assert.equal(f.bar.style.opacity, '.95');
    assert.equal(f.planner.querySelector('.tp-overview-insert-ghost'), null);
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
    const placeholder = f.planner.querySelector('.tp-overview-insert-placeholder');
    assert.equal(placeholder.style.height, '46px');
    assert.equal(f.bar.style.pointerEvents, 'none');
    f.event('pointermove', f.cards[1], {clientX: 500, clientY: 301});
    assert.equal(f.bar.style.transform, 'translateY(-599px)');
    assert.equal(placeholder.parentNode, f.host); assert.equal(f.bar.parentNode, sourceParent);
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertDefault, 'true');
    f.api.beforeRefresh(sourceParent); f.api.restore(); assert.equal(f.bar.parentNode, sourceParent);
    assert.equal(f.calls.length, 0);
    f.event('keydown', f.grip, {key: 'Escape'});
    assert.equal(f.bar.style.width, '90%'); assert.equal(f.bar.style.opacity, '.95');
    assert.equal(f.bar.classList.contains('is-insert-dragging'), false);
    assert.equal(f.planner.querySelector('.tp-overview-insert-ghost'), null);
    assert.equal(f.planner.querySelector('.tp-overview-insert-placeholder'), null);
});

test('repeated previews and hovering the gap do not move the placeholder or restart card animations', async () => {
    const f = fixture(); let moves = 0, animations = 0, cancelled = 0, layoutEvents = 0;
    f.document.addEventListener('travelplan:overview-layout', () => { layoutEvents++; });
    const insert = f.host.insertBefore.bind(f.host);
    f.host.insertBefore = (...args) => { moves++; return insert(...args); };
    for (const card of f.cards) {
        card.getBoundingClientRect = () => ({left: 0, width: 360, height: 100, top: f.host.querySelector('.tp-overview-insert-placeholder') ? card.top + 46 : card.top});
        card.offsetParent = f.host; card.offsetTop = card.top - f.host.top; card.offsetHeight = 100;
        card.animate = () => {
            animations++;
            const wait = deferred();
            return {finished: wait.promise, cancel() { cancelled++; wait.reject(new Error('cancelled')); }};
        };
    }
    f.start();
    f.event('pointermove', f.cards[1], {clientY: 301});
    const first = {moves, animations}; assert.ok(animations > 0);
    f.event('pointermove', f.cards[1], {clientY: 301});
    const placeholder = f.planner.querySelector('.tp-overview-insert-placeholder');
    f.event('pointermove', placeholder, {clientY: 310});
    assert.deepEqual({moves, animations}, first);
    f.event('pointerup', placeholder, {clientY: 310}); await tick();
    assert.equal(f.bar.parentNode, f.host); assert.equal(f.bar.dataset.beforeItemId, 'b');
    assert.ok(cancelled > 0); assert.ok(layoutEvents > 0);
    assert.equal(f.planner.querySelector('.tp-overview-insert-placeholder'), null);
});

test('reduced motion and repeated cancelled drags leave no visual artifacts or duplicate controls', () => {
    const f = fixture(); f.window.matchMedia = () => ({matches: true});
    f.cards.forEach(card => { card.animate = () => { throw new Error('reduced motion must skip animations'); }; });
    for (let i = 0; i < 3; i++) {
        f.start();
        f.event('pointermove', f.cards[1], {clientY: 301}); f.event('pointercancel', f.grip);
        assert.equal(f.bar.parentNode, f.groups[2].host); assert.equal(f.bar.dataset.insertDefault, 'true');
        assert.equal(f.planner.querySelector('.tp-overview-insert-ghost'), null);
        assert.equal(f.planner.querySelector('.tp-overview-insert-placeholder'), null);
        assert.equal(f.planner.querySelectorAll('[data-overview-insert-type]').length, 1);
    }
});
