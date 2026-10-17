const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// Execute actual scheduler helpers with small DOM fixtures; this is not browser QA.
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/travel-plan-scheduler.js'), 'utf8');
function actualFunction(name) {
    const start = new RegExp(`^    (?:async )?function ${name}\\(`, 'm').exec(source);
    assert.ok(start, `scheduler function ${name} exists`);
    const rest = source.slice(start.index);
    const end = /^    }\r?$/m.exec(rest);
    assert.ok(end, `scheduler function ${name} closes`);
    return rest.slice(0, end.index + end[0].length);
}

class Element {
    constructor(tag = 'div', attrs = {}, value = '') {
        this.tagName = tag.toUpperCase(); this.attrs = attrs; this.children = [];
        this.dataset = {}; this.value = value; this.disabled = false; this.hidden = false;
        this.name = attrs.name; this.type = attrs.type;
        this.classes = new Set(); this.classList = {
            add: name => this.classes.add(name), remove: name => this.classes.delete(name),
            contains: name => this.classes.has(name)
        };
        for (const [key, val] of Object.entries(attrs)) {
            if (key.startsWith('data-')) this.dataset[key.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = val;
        }
    }
    matches(selector) {
        if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
        const exclusions = [...selector.matchAll(/:not\(([^)]+)\)/g)];
        if (exclusions.some(match => this.matches(match[1]))) return false;
        selector = selector.replace(/:not\([^)]+\)/g, '');
        const tag = selector.match(/^\w+/)?.[0];
        if (tag && this.tagName !== tag.toUpperCase()) return false;
        if ([...selector.replace(/\[[^\]]+\]/g, '').matchAll(/\.([\w-]+)/g)]
            .some(([, name]) => !this.classes.has(name))) return false;
        return [...selector.matchAll(/\[([^=\]]+)(?:=["']?([^\]"']+)["']?)?\]/g)]
            .every(([, attr, value]) => {
                const current = attr === 'disabled' ? this.disabled ? '' : undefined : this.attrs[attr];
                return current !== undefined && (value === undefined || current === value);
            });
    }
    querySelectorAll(selector) {
        return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]);
    }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    setAttribute(name, value) {
        this.attrs[name] = String(value);
        if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, c) => c.toUpperCase())] = String(value);
    }
    getAttribute(name) { return this.attrs[name] ?? null; }
    set className(value) { this.classes = new Set(value.split(/\s+/).filter(Boolean)); }
    get className() { return [...this.classes].join(' '); }
    hasAttribute(name) { return Object.hasOwn(this.attrs, name); }
    removeAttribute(name) { delete this.attrs[name]; }
    closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) || null; }
    remove() {
        if (this.parentNode) this.parentNode.children = this.parentNode.children.filter(node => node !== this);
        this.parentNode = null;
    }
    cloneNode(deep) {
        const clone = new Element(this.tagName, {...this.attrs}, this.value);
        clone.action = this.action; clone.hidden = this.hidden; clone.disabled = this.disabled;
        clone.dataset = {...this.dataset}; this.classes.forEach(name => clone.classes.add(name));
        if (deep) this.children.forEach(child => clone.append(child.cloneNode(true)));
        return clone;
    }
    get nextSibling() { return this.parentNode?.children[this.parentNode.children.indexOf(this) + 1] || null; }
    get isConnected() { return this.parentNode ? this.parentNode.isConnected : Boolean(this.connected); }
    reset() {}
    focus() { this.focused = true; }
    addEventListener(name, callback) {
        this.listeners ||= {}; (this.listeners[name] ||= []).push(callback);
    }
    append(...children) { children.forEach(child => this.insertBefore(child, null)); }
    before(child) { this.parentNode.insertBefore(child, this); }
    insertBefore(child, sibling) {
        if (child.parentNode) child.parentNode.children = child.parentNode.children.filter(node => node !== child);
        const index = sibling ? this.children.indexOf(sibling) : -1;
        this.children.splice(index < 0 ? this.children.length : index, 0, child);
        child.parentNode = this;
    }
}

function fixture(type = 'FLIGHT') {
    const form = new Element('form'); form.action = '/items/1/update';
    const add = (parent, tag, attrs, value) => {
        const field = new Element(tag, attrs, value); parent.append(field); return field;
    };
    const typeInput = add(form, 'input', {name: 'itemType', type: 'hidden'}, type);
    const content = add(form, 'textarea', {name: 'content'}, '제목');
    const note = add(form, 'textarea', {'data-item-note-input': ''}, '');
    add(form, 'input', {name: 'version', type: 'hidden'}, '1');
    add(form, 'input', {name: '_csrf', type: 'hidden'}, 'test-only-placeholder');
    const groups = {};
    for (const key of ['GENERAL', 'ROUTE', 'FLIGHT', 'ACCOMMODATION', 'SPECIAL']) groups[key] = add(form, 'div', {'data-item-type-fields': key});
    const title = add(groups.GENERAL, 'input', {name: 'title', 'data-item-title-input': ''}, '');
    const time = add(groups.GENERAL, 'input', {name: 'startTime', type: 'time'}, '09:00');
    const flight = add(groups.FLIGHT, 'input', {name: 'details.flightNumber'}, 'KE123');
    const departure = add(groups.ROUTE, 'input', {name: 'details.departurePlace'}, 'ICN');
    const arrival = add(groups.ROUTE, 'input', {name: 'details.arrivalPlace'}, 'NRT');
    const stay = add(groups.ACCOMMODATION, 'input', {name: 'details.stayName'}, '호텔');
    const bookingGroup = add(form, 'label', {'data-item-booking-field': ''});
    const booking = add(bookingGroup, 'textarea', {name: 'details.bookingMemo'}, '예약 메모');
    add(form, 'button', {type: 'submit'});
    return {form, typeInput, title, content, note, groups, time, flight, departure, arrival, stay, booking, bookingGroup};
}

function runtime(extra = {}, names = ['setEditorType', 'syncContent', 'editorState', 'setSaving', 'restorePortal']) {
    const context = vm.createContext({activePortal: null, editorOpening: false, window: {}, ...extra});
    vm.runInContext(names.map(actualFunction).join('\n'), context);
    return context;
}

test('type switching excludes inactive fields and toggles the shared booking memo', () => {
    const f = fixture(); const code = runtime();
    code.setEditorType(f.form, 'FLIGHT');
    assert.equal(f.groups.FLIGHT.hidden, false);
    assert.equal(f.flight.disabled, false);
    assert.equal(f.stay.disabled, true);
    assert.equal(f.time.disabled, true);
    assert.equal(f.booking.disabled, false);
    code.setEditorType(f.form, 'ACCOMMODATION');
    assert.equal(f.flight.disabled, true);
    assert.equal(f.stay.disabled, false);
    code.setEditorType(f.form, 'SCHEDULE');
    assert.equal(f.time.disabled, false);
    assert.equal(f.booking.disabled, true);
    assert.equal(f.bookingGroup.hidden, true);
    assert.equal(f.typeInput.value, 'SCHEDULE');
});

test('content composition keeps multiline notes and applies flight/stay/route title rules', () => {
    const f = fixture('SCHEDULE'); const code = runtime();
    f.title.value = '  박물관  '; f.note.value = '첫 메모\n둘째 메모';
    code.syncContent(f.form);
    assert.equal(f.content.value, '박물관\n첫 메모\n둘째 메모');
    f.typeInput.value = 'FLIGHT'; code.syncContent(f.form);
    assert.equal(f.content.value, '박물관\n첫 메모\n둘째 메모');
    f.title.value = ''; code.syncContent(f.form);
    assert.equal(f.content.value, 'KE123\n첫 메모\n둘째 메모');
    f.flight.value = ''; f.title.value = ''; f.note.value = '';
    code.syncContent(f.form); assert.equal(f.content.value, 'ICN → NRT');
    f.departure.value = ''; code.syncContent(f.form); assert.equal(f.content.value, 'NRT');
    f.typeInput.value = 'ACCOMMODATION'; code.syncContent(f.form);
    assert.equal(f.content.value, '호텔');
});

test('transport enables only route fields and derives content without a visible title', () => {
    const f = fixture('TRANSPORT'); const code = runtime();
    code.setEditorType(f.form, 'TRANSPORT');
    assert.equal(f.groups.ROUTE.hidden, false);
    assert.equal(f.title.disabled, true);
    assert.equal(f.flight.disabled, true);
    assert.equal(f.stay.disabled, true);
    f.note.value = '공항 철도'; code.syncContent(f.form);
    assert.equal(f.content.value, 'ICN → NRT\n공항 철도');
    f.title.value = ''; f.departure.value = ''; f.arrival.value = '';
    code.syncContent(f.form);
    assert.equal(f.content.value, '이동\n공항 철도');
});

function deferred() {
    let resolve, reject;
    const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
    return {promise, resolve, reject};
}

test('pending editor lock excludes a second request and always clears its opening flags', async () => {
    for (const outcome of ['granted', 'denied', 'rejected']) {
        const request = deferred(); let requests = 0;
        const planner = {dataset: {}};
        const live = {requestLock: () => {requests++; return request.promise;}};
        const code = runtime({planner}, ['requestEditorLock']);
        const pending = code.requestEditorLock(live, {mode: 'ADD', dayId: '44', itemId: null});
        assert.equal(code.editorOpening, true);
        assert.equal(planner.dataset.editorOpening, 'true');
        const second = await code.requestEditorLock(live, {mode: 'EDIT', dayId: '44', itemId: '77'});
        assert.equal(second.granted, false); assert.equal(requests, 1);
        assert.equal(code.editorOpening, true);
        if (outcome === 'rejected') {
            request.reject(new Error('offline'));
            await assert.rejects(pending, /offline/);
        } else {
            request.resolve({granted: outcome === 'granted'});
            assert.equal((await pending).granted, outcome === 'granted');
        }
        assert.equal(code.editorOpening, false);
        assert.equal(Object.hasOwn(planner.dataset, 'editorOpening'), false);
        const retry = await code.requestEditorLock({requestLock: async () => ({granted: true})}, {mode: 'ADD'});
        assert.equal(retry.granted, true);
    }
});

function draftRuntime({finish = async () => true, lock = async () => ({granted: true}),
    fetch = async () => ({ok: true})} = {}) {
    const events = [], f = fixture('SCHEDULE');
    f.form.action = '/travel-plans/42/days/44/items';
    f.form.setAttribute('data-travel-plan-slot-form', '');
    f.title.setAttribute('id', 'original-title');
    const day = new Element('section', {'data-travel-plan-day-id': '44', 'data-plan-date': '2026-10-18'});
    day.connected = true;
    const slot = new Element('li', {'data-travel-plan-slot': '', 'data-day-id': '44'});
    const summaryItems = new Element(); day.append(slot, summaryItems); slot.append(f.form);
    class FormDataFixture extends Map {
        constructor(form) {
            super(form.querySelectorAll('input[name], textarea[name], select[name]')
                .filter(field => !field.disabled).map(field => [field.name, field.value]));
        }
    }
    const planner = {dataset: {}, querySelector: selector => selector.includes('data-planner-summary') ? summaryItems :
        selector.includes('data-travel-plan-slot') ? slot : day.querySelector(selector)};
    const code = runtime({submitting: false, creatingCard: false, activeLine: null, activeAlt: null,
        draftSummaries: new WeakMap(), initialEditorStates: new WeakMap(), FormData: FormDataFixture, planner,
        document: {createElement: tag => new Element(tag), dispatchEvent: event => events.push(event.type)},
        CustomEvent: class {constructor(type) {this.type = type;}}, window: {},
        finishCurrentLine: finish, closeAlt() {}, bind: line => events.push(['bind', line]),
        focusEditor: form => events.push(['focus', form]), notifyEditorIdle: () => events.push('idle'),
        clearSaveError() {}, showSaveError: (_, text) => events.push(['error', text]),
        fetch: (url, request) => {events.push(['POST', url, request]); return fetch(url, request);},
        realtime: () => ({requestLock: spot => {events.push(['lock', spot]); return lock(spot);},
            releaseLock: () => events.push('release'), refreshDay: async id => events.push(['refresh', id])})
    }, ['formOf', 'textareaOf', 'isItem', 'spotOf', 'setEditorType', 'syncContent', 'editorState',
        'setSaving', 'restorePortal', 'attachPortal', 'closeActive', 'requestEditorLock', 'createDraftCard', 'save']);
    return {code, events, slot, day, summaryItems, original: f.form,
        draft: () => day.querySelector('[data-travel-plan-draft]')};
}

test('all types insert local cards synchronously before previous save and ADD lock finish', async () => {
    for (const type of ['SCHEDULE', 'FLIGHT', 'ACCOMMODATION', 'TRANSPORT']) {
        const finish = deferred(), lock = deferred();
        const env = draftRuntime({finish: () => finish.promise, lock: () => lock.promise});
        const pending = env.code.createDraftCard(env.slot, type);
        const draft = env.draft(), form = env.code.formOf(draft);
        assert.ok(draft); assert.equal(env.day.children[0], draft);
        assert.equal(draft.dataset.itemType, type);
        assert.equal(form.querySelector('[name=itemType]').value, type);
        assert.equal(form.querySelector('[id]'), null);
        assert.equal(form.querySelector('[name=_csrf]').value, 'test-only-placeholder');
        assert.equal(form.querySelector('[data-item-title-input]').value, '');
        assert.equal(form.querySelector('[name="details.flightNumber"]').value, '');
        assert.equal(form.querySelector('[name="details.flightNumber"]').disabled, true);
        assert.equal(env.events.length, 0);
        await env.code.createDraftCard(env.slot, type);
        assert.equal(env.day.querySelectorAll('[data-travel-plan-draft]').length, 1);
        finish.resolve(true); await new Promise(resolve => setImmediate(resolve));
        assert.equal(env.events[0][0], 'lock'); assert.equal(env.code.activeLine, null);
        assert.equal(env.draft(), draft);
        lock.resolve({granted: true}); await pending;
        assert.equal(env.code.activeLine, draft);
        assert.equal(form.querySelector('[name=itemType]').value, type);
        assert.equal(env.events.some(event => event[0] === 'POST'), false);
        assert.equal(env.code.creatingCard, false);
    }
});

test('cancel removes local card and overview summary without any create or delete request', async () => {
    const env = draftRuntime();
    await env.code.createDraftCard(env.slot, 'FLIGHT', true);
    const draft = env.draft(), form = env.code.formOf(draft);
    assert.equal(env.summaryItems.children.length, 1);
    assert.equal(env.summaryItems.children[0].querySelector('form'), form);
    env.code.closeActive();
    assert.equal(env.draft(), null); assert.equal(env.summaryItems.children.length, 0);
    assert.equal(env.code.activeLine, null); assert.equal(env.code.activePortal, null);
    assert.equal(env.code.draftSummaries.has(draft), false);
    assert.equal(env.events.includes('release'), true);
    assert.equal(env.events.some(event => event[0] === 'POST'), false);
});

test('failed previous save and denied lock clean up temporary cards and summaries', async () => {
    for (const options of [{finish: async () => false}, {lock: async () => ({granted: false})}]) {
        const env = draftRuntime(options);
        await env.code.createDraftCard(env.slot, 'TRANSPORT', true);
        assert.equal(env.draft(), null); assert.equal(env.summaryItems.children.length, 0);
        assert.equal(env.code.activeLine, null); assert.equal(env.code.creatingCard, false);
        assert.equal(env.events.some(event => event[0] === 'POST'), false);
    }
});

test('draft save posts entered fields and CSRF to existing create endpoint then removes local card', async () => {
    const env = draftRuntime(); await env.code.createDraftCard(env.slot, 'FLIGHT');
    const form = env.code.formOf(env.draft());
    form.querySelector('[name="details.flightNumber"]').value = 'KE123';
    await env.code.save(env.draft());
    const [, url, request] = env.events.find(event => event[0] === 'POST');
    assert.equal(url, '/travel-plans/42/days/44/items'); assert.equal(request.method, 'POST');
    assert.equal(request.body.get('itemType'), 'FLIGHT'); assert.equal(request.body.get('content'), 'KE123');
    assert.equal(request.body.get('details.flightNumber'), 'KE123');
    assert.equal(request.body.get('_csrf'), 'test-only-placeholder');
    assert.equal(request.body.has('emptyCard'), false);
    assert.equal(env.draft(), null); assert.equal(env.code.activeLine, null);
    assert.equal(env.events.some(event => event[0] === 'refresh' && event[1] === '44'), true);
});

test('rejected draft save retains local card, typed input and CSRF for retry', async () => {
    const env = draftRuntime({fetch: async () => ({ok: false, text: async () => 'rejected'})});
    await env.code.createDraftCard(env.slot, 'ACCOMMODATION', true);
    const draft = env.draft(), form = env.code.formOf(draft);
    form.querySelector('[name="details.stayName"]').value = 'Hotel';
    await env.code.save(draft);
    assert.equal(env.draft(), draft); assert.equal(env.code.activeLine, draft);
    assert.equal(env.summaryItems.children.length, 1);
    assert.equal(form.querySelector('[name="details.stayName"]').value, 'Hotel');
    assert.equal(form.querySelector('[name="details.stayName"]').disabled, false);
    assert.equal(form.querySelector('[name=_csrf]').value, 'test-only-placeholder');
    assert.equal(env.events.find(event => event[0] === 'POST')[2].body.has('emptyCard'), false);
    assert.equal(env.code.submitting, false);
    assert.equal(env.events.some(event => event[0] === 'error' && event[1] === 'rejected'), true);
});

test('lock reply opens the current row when realtime replaced the Day while waiting', async () => {
    const old = fixture(), fresh = fixture(); old.form.hidden = true; fresh.form.hidden = true;
    const oldView = {}, freshView = {};
    const row = (form, view) => ({dataset: {itemId: '77'}, isConnected: true,
        querySelector: selector => selector === '[data-item-view]' ? view : selector === '.tp-detail-item-shell' ? {} : null,
        classList: {add() {}}});
    const oldRow = row(old.form, oldView), freshRow = row(fresh.form, freshView);
    let focused, released = 0;
    const live = {requestLock: async () => {oldRow.isConnected = false; return {granted: true};},
        isLockedByOther: () => false, releaseLock: () => released++, sendDraft() {}};
    const code = runtime({submitting: false, creatingCard: true, activeLine: null, activeAlt: null,
        document: {dispatchEvent() {}}, CustomEvent: class {},
        initialEditorStates: new WeakMap(),
        formOf: line => line === oldRow ? old.form : fresh.form,
        finishCurrentLine: async () => true, isItem: () => true,
        planner: {dataset: {}, querySelector: () => freshRow}, spotOf: () => ({itemId: '77', dayId: '44'}),
        realtime: () => live, closeActive() {}, closeAlt() {}, setEditorType() {}, syncContent() {},
        editorState: () => '{}', focusEditor: form => {focused = form;}, textareaOf: () => fresh.content
    }, ['requestEditorLock', 'open']);
    await code.open(oldRow, null, true);
    assert.equal(code.activeLine, freshRow); assert.equal(focused, fresh.form);
    assert.equal(old.form.hidden, true); assert.equal(fresh.form.hidden, false);
    assert.equal(freshView.hidden, false);
    assert.equal(released, 0);
});

test('editor state detects details-only edits and ignores inactive fields, version and CSRF', () => {
    const f = fixture(); const code = runtime(); code.setEditorType(f.form, 'FLIGHT');
    const baseline = code.editorState(f.form);
    f.stay.value = '전송하지 않는 숙소';
    f.form.querySelector('[name=version]').value = '8';
    f.form.querySelector('[name=_csrf]').value = 'different-test-placeholder';
    assert.equal(code.editorState(f.form), baseline);
    f.booking.value = '예약번호 수정'; assert.notEqual(code.editorState(f.form), baseline);
});

test('saving disables controls and restores only fields belonging to the active type', () => {
    const f = fixture(); const code = runtime(); code.setEditorType(f.form, 'FLIGHT');
    code.setSaving(f.form, true);
    assert.equal(f.flight.disabled, true); assert.equal(f.title.disabled, true);
    assert.equal(f.form.attrs['aria-busy'], 'true');
    code.setSaving(f.form, false);
    assert.equal(f.flight.disabled, false); assert.equal(f.booking.disabled, false);
    assert.equal(f.stay.disabled, true); assert.equal(f.time.disabled, true);
    assert.equal(f.form.attrs['aria-busy'], 'false');
});

test('restoring a portal returns the same form before its original sibling', () => {
    const parent = new Element(); const host = new Element(); const button = new Element('button');
    const form = new Element('form'); const sibling = new Element();
    parent.append(form, sibling); host.append(form); host.classes.add('is-editing');
    button.setAttribute('aria-expanded', 'true');
    const code = runtime({activePortal: {form, parent, sibling, host, button}});
    code.restorePortal();
    assert.equal(parent.children[0], form); assert.equal(parent.children[1], sibling);
    assert.equal(host.children.length, 0); assert.equal(host.classes.has('is-editing'), false);
    assert.equal(button.attrs['aria-expanded'], 'false'); assert.equal(code.activePortal, null);
});

function saveRuntime(f, fetch) {
    const calls = {closed: 0, refreshed: [], errors: [], focused: 0};
    class FormDataFixture extends Map {
        constructor(form) {
            super(form.querySelectorAll('input[name], textarea[name], select[name]')
                .filter(field => !field.disabled).map(field => [field.name, field.value]));
        }
    }
    const line = {dataset: {itemType: 'FLIGHT', startTime: '09:00'}};
    const code = runtime({submitting: false, initialEditorStates: new WeakMap(), FormData: FormDataFixture, fetch,
        textareaOf: () => f.content, formOf: () => f.form, isItem: () => true,
        originalContentOf: () => 'KE123', closeActive: () => { calls.closed++; },
        clearSaveError: () => {}, showSaveError: (_, text) => calls.errors.push(text),
        focusEditor: () => calls.focused++, spotOf: () => ({dayId: '44'}),
        realtime: () => ({refreshDay: async id => calls.refreshed.push(id)})
    }, ['setEditorType', 'syncContent', 'editorState', 'setSaving', 'save']);
    code.setEditorType(f.form, 'FLIGHT'); code.syncContent(f.form);
    code.initialEditorStates.set(f.form, code.editorState(f.form));
    f.booking.value = 'details-only edit';
    return {code, calls, line};
}

test('actual save serializes active fields before disabling and suppresses concurrent submit', async () => {
    const f = fixture(); let resolve; let requests = 0; let body;
    const env = saveRuntime(f, (_, request) => {
        requests++; body = request.body;
        assert.equal(f.flight.disabled, true);
        return new Promise(done => { resolve = done; });
    });
    const pending = env.code.save(env.line);
    await env.code.save(env.line);
    assert.equal(requests, 1);
    assert.equal(body.get('content'), 'KE123');
    assert.equal(body.get('details.bookingMemo'), 'details-only edit');
    assert.equal(body.has('details.stayName'), false); assert.equal(body.has('startTime'), false);
    resolve({ok: true}); await pending;
    assert.equal(env.code.submitting, false); assert.equal(env.calls.closed, 1);
    assert.deepEqual(env.calls.refreshed, ['44']); assert.equal(f.flight.disabled, false);
});

test('HTTP rejection retains edited values and unlocks the form for retry', async () => {
    const f = fixture(); const env = saveRuntime(f, async () => ({ok: false, text: async () => '저장 충돌'}));
    await env.code.save(env.line);
    assert.equal(env.calls.closed, 0); assert.equal(env.calls.errors[0], '저장 충돌');
    assert.equal(f.booking.value, 'details-only edit'); assert.equal(f.flight.disabled, false);
    assert.equal(f.stay.disabled, true); assert.equal(env.code.submitting, false);
});

test('network rejection retains draft, clears submitting and restores active controls', async () => {
    const f = fixture(); const env = saveRuntime(f, async () => { throw new Error('offline'); });
    await env.code.save(env.line);
    assert.equal(env.calls.closed, 0); assert.equal(env.calls.errors.length, 1);
    assert.equal(env.calls.focused, 1); assert.equal(f.booking.value, 'details-only edit');
    assert.equal(env.code.submitting, false); assert.equal(f.flight.disabled, false);
    assert.equal(f.stay.disabled, true);
});

test('confirmed editor deletion uses native formaction without turning into an item save', () => {
    let confirmations = 0, prevented = false;
    const code = runtime({submitting: false, creatingCard: false,
        window: {confirm: () => { confirmations++; return true; }}, realtime: () => null
    }, ['handleItemDelete']);
    const event = {submitter: {hasAttribute: () => true, dataset: {deleteConfirm: '일정을 삭제할까요?'}},
        preventDefault: () => { prevented = true; }};
    assert.equal(code.handleItemDelete(event, {dataset: {itemId: '7'}}), true);
    assert.equal(confirmations, 1); assert.equal(prevented, false);
    assert.equal(code.handleItemDelete({submitter: {hasAttribute: () => false}}, {}), false);
});

test('declined deletion keeps the edited form and suppresses native POST', () => {
    let prevented = false;
    const code = runtime({submitting: false, creatingCard: false,
        window: {confirm: () => false}, realtime: () => null
    }, ['handleItemDelete']);
    const event = {submitter: {hasAttribute: () => true, dataset: {}}, preventDefault: () => {prevented = true;}};
    assert.equal(code.handleItemDelete(event, {dataset: {itemId: '7'}}), true);
    assert.equal(prevented, true);
});

test('remote alternative editing blocks editor deletion before confirmation', () => {
    let prevented = false, error = '';
    const code = runtime({submitting: false, creatingCard: false,
        window: {confirm: () => {throw Error('Must not confirm');}},
        realtime: () => ({hasAlternativeEditing: id => id === '7'}), showSaveError: (_, message) => {error = message;}
    }, ['handleItemDelete']);
    const event = {submitter: {hasAttribute: () => true, dataset: {}}, preventDefault: () => {prevented = true;}};
    assert.equal(code.handleItemDelete(event, {dataset: {itemId: '7'}}), true);
    assert.equal(prevented, true); assert.match(error, /공동 편집/);
});
