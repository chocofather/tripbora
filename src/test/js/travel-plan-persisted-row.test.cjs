const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/travel-plan-scheduler.js'), 'utf8');
function actualFunction(name) {
    const start = new RegExp(`^    (?:async )?function ${name}\\(`, 'm').exec(source);
    assert.ok(start, `${name} exists`);
    const rest = source.slice(start.index);
    const end = /^    }\r?$/m.exec(rest);
    assert.ok(end, `${name} closes`);
    return rest.slice(0, end.index + end[0].length);
}

// Minimal DOM for the actual helpers, not a browser/layout substitute.
class Element {
    constructor(tag = 'div') {
        this.tagName = tag.toUpperCase(); this.children = []; this.dataset = {}; this.attrs = {};
        this.classes = new Set();
        this.classList = {add: value => this.classes.add(value), remove: value => this.classes.delete(value), contains: value => this.classes.has(value)};
    }
    set className(value) { this.classes = new Set(value.split(/\s+/)); }
    setAttribute(name, value) { this.attrs[name] = String(value); }
    append(...children) { children.forEach(child => { child.parentNode = this; this.children.push(child); }); }
    before(child) { child.parentNode = this.parentNode; this.parentNode.children.splice(this.parentNode.children.indexOf(this), 0, child); }
    remove() { if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
    closest() { return this.parentNode; }
    querySelectorAll(selector) {
        return this.children.flatMap(child => [...(selector === '[data-travel-plan-item]' && child.dataset.itemId ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    querySelector(selector) {
        return this.children.flatMap(child => [child, ...child.descendants()]).find(child => selector === '[role=status]' && child.attrs.role === 'status') || null;
    }
    descendants() { return this.children.flatMap(child => [child, ...child.descendants()]); }
}
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return {promise, resolve}; };
function fixture(options = {}) {
    const day = new Element(); const slot = new Element('li'); slot.dataset.dayId = '7'; day.append(slot);
    const form = {action: '/travel-plans/1/days/7/items'};
    const posts = [], errors = [], locks = [], events = [];
    let actual = null;
    const live = {
        releaseLock() { events.push('unlock'); },
        async refreshDay(id) {
            events.push(`refresh:${id}`);
            if (options.refreshError) throw new Error('refresh failed');
            if (options.missingActual) return;
            actual = new Element('li'); actual.dataset.itemId = '91'; day.append(actual);
        }
    };
    const context = vm.createContext({
        submitting: false, creatingCard: false, editorOpening: false,
        window: {travelPlanDayTitle: {isEditing: () => Boolean(options.titleEditing)}, location: {reload() { events.push('reload'); }}},
        document: {createElement: tag => new Element(tag)},
        planner: {querySelector: selector => selector.includes('[data-travel-plan-slot]') ? slot : actual},
        formOf: () => form,
        finishCurrentLine: () => options.finish ? options.finish.promise : Promise.resolve(options.finishAllowed !== false),
        closeAlt: () => events.push('closeAlt'),
        realtime: () => options.offline ? null : live,
        requestEditorLock: async (_, request) => { locks.push(request); return {granted: options.lockDenied !== true}; },
        FormData: class extends Map { constructor() { super(); } },
        fetch: async (url, request) => {
            posts.push({url, ...request});
            if (options.post) await options.post.promise;
            return {ok: !options.postError, text: async () => '추가 실패', json: async () => {
                if (options.jsonError) throw new Error('Invalid response');
                return {itemId: 91};
            }};
        },
        showSaveError: (host, message) => errors.push({host, message}),
        notifyEditorIdle: () => events.push('idle')
    });
    vm.runInContext(['pendingDetailRow', 'createPersistedRow'].map(actualFunction).join('\n'), context);
    return {context, day, slot, form, posts, errors, locks, events, live, actual: () => actual};
}

for (const type of ['SCHEDULE', 'FLIGHT', 'ACCOMMODATION', 'TRANSPORT']) {
    test(`${type}: shows a pending row synchronously, then stores an empty row without opening an editor`, async () => {
        const finish = deferred(); const f = fixture({finish});
        const promise = f.context.createPersistedRow(f.slot, type);
        const pending = f.day.children[0];
        assert.notEqual(pending, f.slot);
        assert.equal(pending.dataset.itemType, type);
        assert.equal(pending.classList.contains('is-creating'), true);
        assert.equal(pending.descendants().some(node => node.tagName === 'FORM'), false);
        assert.equal(pending.descendants().filter(node => node.tagName === 'BUTTON').every(node => node.disabled), true);
        assert.equal(f.posts.length, 0);
        finish.resolve(true); await promise;
        assert.equal(f.posts.length, 1);
        assert.equal(f.posts[0].method, 'POST');
        assert.equal(f.posts[0].body.get('emptyCard'), 'true');
        assert.equal(f.posts[0].body.get('itemType'), type);
        assert.equal(f.posts[0].body.get('content'), '');
        assert.equal(f.locks[0].mode, 'ADD');
        assert.equal(f.events.includes('refresh:7'), true);
        assert.equal(pending.parentNode, null);
        assert.equal(f.actual().classList.contains('tp-item-enter'), true);
        assert.equal(f.actual().classList.contains('is-editing'), false);
        assert.equal(f.context.creatingCard, false);
    });
}

test('pending creation blocks duplicate clicks until the POST and refresh complete', async () => {
    const post = deferred(); const f = fixture({post});
    const promise = f.context.createPersistedRow(f.slot, 'FLIGHT');
    await new Promise(resolve => setImmediate(resolve));
    await f.context.createPersistedRow(f.slot, 'TRANSPORT');
    assert.equal(f.day.children.length, 2);
    assert.equal(f.posts.length, 1);
    post.resolve(); await promise;
    assert.equal(f.events.filter(event => event === 'unlock').length, 1);
});

test('POST failure removes pending row, releases lock and reports a retryable error', async () => {
    const f = fixture({postError: true});
    await f.context.createPersistedRow(f.slot, 'SCHEDULE');
    assert.deepEqual(f.day.children, [f.slot]);
    assert.equal(f.errors[0].message, '추가 실패');
    assert.equal(f.errors[0].host, f.slot);
    assert.equal(f.events.filter(event => event === 'unlock').length, 1);
    assert.equal(f.context.creatingCard, false);
});

test('refresh failure after commit preserves saved status and never repeats the POST', async () => {
    const f = fixture({refreshError: true});
    await f.context.createPersistedRow(f.slot, 'ACCOMMODATION');
    assert.equal(f.posts.length, 1);
    const pending = f.day.children[0];
    assert.equal(pending.classList.contains('is-editing'), false);
    assert.equal(pending.querySelector('[role=status]').textContent, '저장 완료');
    assert.match(f.errors[0].message, /저장됐습니다/);
    assert.equal(f.errors[0].host, pending);
    assert.equal(f.events.filter(event => event === 'unlock').length, 1);
});

test('lock denial and unsaved-editor refusal remove pending rows before posting', async () => {
    for (const options of [{lockDenied: true}, {finishAllowed: false}]) {
        const f = fixture(options);
        await f.context.createPersistedRow(f.slot, 'TRANSPORT');
        assert.equal(f.posts.length, 0);
        assert.deepEqual(f.day.children, [f.slot]);
        assert.equal(f.context.creatingCard, false);
    }
});

test('missing stored row retries only the fragment refresh and reports that storage succeeded', async () => {
    const f = fixture({missingActual: true});
    await f.context.createPersistedRow(f.slot, 'FLIGHT');
    assert.equal(f.posts.length, 1);
    assert.equal(f.events.filter(event => event === 'refresh:7').length, 2);
    assert.match(f.errors[0].message, /저장됐습니다/);
    assert.equal(f.day.children[0].querySelector('[role=status]').textContent, '저장 완료');
});

test('submission, editor opening and DAY title editing gate creation before DOM mutation', async () => {
    for (const state of ['submitting', 'editorOpening', 'creatingCard', 'titleEditing']) {
        const f = fixture({titleEditing: state === 'titleEditing'});
        if (state !== 'titleEditing') f.context[state] = true;
        await f.context.createPersistedRow(f.slot, 'SCHEDULE');
        assert.equal(f.posts.length, 0);
        assert.deepEqual(f.day.children, [f.slot]);
    }
});

test('without realtime, successful POST reloads to display the stored row', async () => {
    const f = fixture({offline: true});
    await f.context.createPersistedRow(f.slot, 'SCHEDULE');
    assert.equal(f.posts.length, 1);
    assert.equal(f.events.includes('reload'), true);
    assert.equal(f.locks.length, 0);
});

test('accepted creation with an unreadable response reports uncertain saved state without another POST', async () => {
    const f = fixture({jsonError: true});
    await f.context.createPersistedRow(f.slot, 'SCHEDULE');
    assert.equal(f.posts.length, 1);
    const pending = f.day.children[0];
    assert.equal(pending.classList.contains('is-editing'), false);
    assert.equal(pending.querySelector('[role=status]').textContent, '저장 결과 확인 필요');
    assert.match(f.errors[0].message, /새로고침/);
});
