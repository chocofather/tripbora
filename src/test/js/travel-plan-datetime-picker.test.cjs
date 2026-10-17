const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parseDate, timeParts, label, commitFields, mount} = require('../../main/resources/static/js/travel-plan-datetime-picker.js');

// Small DOM fixture executes the actual delegated picker, including its dialog events.
// Browser layout and mobile numeric keyboard still require browser QA.
class Element {
    constructor(tag, doc) {
        this.tagName = tag.toUpperCase(); this.doc = doc; this.children = []; this.attrs = {};
        this.dataset = {}; this.listeners = {}; this.value = ''; this.style = {}; this.hidden = false;
        this.classList = {add: name => this.className = `${this.className || ''} ${name}`};
    }
    setAttribute(name, value) {
        this.attrs[name] = String(value);
        if (name === 'name') this.name = value;
        if (name === 'class') this.className = value;
        if (name.startsWith('data-')) this.dataset[name.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] = value;
    }
    matches(selector) {
        if (selector.includes(',')) return selector.split(',').some(part => this.matches(part.trim()));
        if (selector.startsWith('#')) return this.attrs.id === selector.slice(1);
        if (selector.startsWith('.')) return (this.className || '').split(' ').includes(selector.slice(1));
        const tag = selector.match(/^\w+/)?.[0];
        if (tag && this.tagName !== tag.toUpperCase()) return false;
        return [...selector.matchAll(/\[([^=\]]+)(?:="([^"]+)")?\]/g)].every(([, attr, value]) => {
            const current = attr === 'hidden' ? this.hidden ? '' : undefined : attr.startsWith('data-') ? this.dataset[attr.slice(5).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase())] : this.attrs[attr];
            return current !== undefined && (value === undefined || current === value);
        });
    }
    querySelectorAll(selector) { return this.children.flatMap(child => [...(child.matches(selector) ? [child] : []), ...child.querySelectorAll(selector)]); }
    querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
    closest(selector) { return this.matches(selector) ? this : this.parentNode?.closest(selector) || null; }
    append(...children) { children.forEach(child => { child.parentNode = this; this.children.push(child); }); }
    insertBefore(child, sibling) { child.parentNode = this; this.children.splice(this.children.indexOf(sibling), 0, child); }
    replaceChildren() { this.children = []; }
    remove() { this.parentNode.children = this.parentNode.children.filter(child => child !== this); this.parentNode = null; }
    get isConnected() { return this === this.doc || !!this.parentNode?.isConnected; }
    focus() { this.doc.activeElement = this; }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    dispatchEvent(event) {
        event.target ||= this;
        for (const listener of this.listeners[event.type] || []) listener(event);
        if (event.bubbles && !event.stopped) this.parentNode?.dispatchEvent(event);
    }
    set innerHTML(html) {
        const stack = [this];
        for (const match of html.matchAll(/<\/?([\w-]+)([^>]*)>/g)) {
            if (match[0].startsWith('</')) { stack.pop(); continue; }
            const element = new Element(match[1], this.doc);
            for (const attr of match[2].matchAll(/([\w-]+)(?:="([^"]*)")?/g)) element.setAttribute(attr[1], attr[2] || '');
            stack[stack.length - 1].append(element);
            if (element.tagName !== 'INPUT') stack.push(element);
        }
    }
}
class FakeEvent {
    constructor(type, options = {}) { this.type = type; Object.assign(this, options); }
    preventDefault() { this.defaultPrevented = true; }
    stopPropagation() { this.stopped = true; }
}
function fixture(withDate = true) {
    const doc = new Element('document'); doc.doc = doc;
    doc.createElement = tag => new Element(tag, doc);
    doc.defaultView = {Event: FakeEvent, setTimeout: fn => fn()};
    const body = doc.createElement('body'); doc.body = body; doc.append(body);
    const planner = doc.createElement('main'); planner.dataset.tripStart = '2026-10-18'; planner.dataset.tripEnd = '2026-10-21'; body.append(planner);
    function form() {
        const form = doc.createElement('form'); planner.append(form);
        function input(name, value) { const input = doc.createElement('input'); input.setAttribute('name', name); input.value = value; form.append(input); return input; }
        const date = withDate ? input('details.departureDate', '2026-10-19') : null;
        const time = input('startTime', '09:03');
        const trigger = doc.createElement('button'); trigger.dataset.datetimePicker = ''; trigger.dataset.pickerTitle = '출발 일시';
        if (withDate) trigger.dataset.dateField = 'details.departureDate';
        trigger.dataset.timeField = 'startTime'; form.append(trigger);
        return {form, date, time, trigger};
    }
    const initial = form(); const api = mount(doc);
    const click = target => target.dispatchEvent(new FakeEvent('click', {bubbles: true}));
    const query = selector => doc.querySelector(selector);
    return {doc, ...initial, api, createForm: form, click, query};
}

test('local calendar dates and time labels preserve midnight and validate leap days', () => {
    assert.equal(parseDate('2025-02-29'), null);
    assert.ok(parseDate('2024-02-29'));
    assert.equal(parseDate('2026-02-31'), null);
    assert.deepEqual(timeParts('24:00'), ['', '']);
    assert.equal(label('', '00:05:00', false), '00:05');
    assert.equal(label('2026-10-19', '09:03', true), '10월 19일 (월) · 09:03');
    assert.equal(label('2026-10-19', '', true), '10월 19일 (월) · 시간 미정');
});
test('commit dispatches bubbling input and change only for modified values', () => {
    const events = []; const field = {value: '09:00', dispatchEvent: event => events.push(event)};
    commitFields([null, field], ['', '10:05'], FakeEvent);
    assert.equal(field.value, '10:05');
    assert.deepEqual(events.map(event => [event.type, event.bubbles]), [['input', true], ['change', true]]);
    commitFields([field], ['10:05'], FakeEvent);
    assert.equal(events.length, 2);
});
test('cancel, Escape and backdrop preserve original values and restore focus', () => {
    for (const action of ['cancel', 'escape', 'backdrop']) {
        const f = fixture(); f.doc.body.style.overflow = 'auto'; f.click(f.trigger);
        f.click(f.query('[data-picker-date="2026-10-20"]'));
        if (action === 'cancel') f.click(f.query('[data-picker-action="cancel"]'));
        if (action === 'escape') f.query('[role="dialog"]').dispatchEvent(new FakeEvent('keydown', {key: 'Escape', bubbles: true}));
        if (action === 'backdrop') f.click(f.query('[role="dialog"]').parentNode);
        assert.equal(f.date.value, '2026-10-19'); assert.equal(f.time.value, '09:03');
        assert.equal(f.query('[role="dialog"]'), null); assert.equal(f.doc.activeElement, f.trigger);
        assert.equal(f.doc.body.style.overflow, 'auto');
    }
});
test('date, month navigation, trip highlight, confirm and clear share the component', () => {
    const f = fixture(); const changes = [];
    f.form.addEventListener('change', event => changes.push(event.target.name));
    f.click(f.trigger);
    assert.match(f.query('[data-picker-date="2026-10-20"]').className, /is-trip-date/);
    f.click(f.query('[data-picker-action="next"]'));
    assert.equal(f.query('[data-picker-month]').textContent, '2026년 11월');
    f.click(f.query('[data-picker-action="previous"]'));
    f.click(f.query('[data-picker-date="2026-10-20"]'));
    f.query('[data-picker-hour]').value = '00'; f.query('[data-picker-minute]').value = '17';
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(f.date.value, '2026-10-20'); assert.equal(f.time.value, '00:17');
    assert.deepEqual(changes, ['details.departureDate', 'startTime']);
    assert.equal(f.trigger.textContent, '10월 20일 (화) · 00:17');
    f.click(f.trigger); f.click(f.query('[data-picker-action="clear"]'));
    assert.equal(f.time.value, '00:17');
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(f.date.value, ''); assert.equal(f.time.value, '');
});
test('time-only cloned forms work through delegated clicks and refresh events', () => {
    const f = fixture(false); const cloned = f.createForm();
    f.click(cloned.trigger);
    assert.equal(f.query('[data-picker-calendar]').hidden, true);
    f.query('[data-picker-hour]').value = '13'; f.query('[data-picker-minute]').value = '';
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(cloned.time.value, '09:03');
    assert.equal(f.query('[data-picker-error]').textContent, '시와 분을 모두 입력해 주세요.');
    f.query('[data-picker-minute]').value = '48';
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(cloned.time.value, '13:48'); assert.equal(f.time.value, '09:03');
    cloned.time.value = '';
    f.doc.dispatchEvent(new FakeEvent('travelplan:editor-opened'));
    assert.equal(cloned.trigger.textContent, '시간 선택');
});
test('Tab focus is contained inside the dialog in both directions', () => {
    const f = fixture(); f.click(f.trigger);
    const dialog = f.query('[role="dialog"]');
    const first = f.query('[data-picker-action="cancel"]');
    const last = f.query('[data-picker-action="confirm"]');
    first.focus(); dialog.dispatchEvent(new FakeEvent('keydown', {key: 'Tab', shiftKey: true, bubbles: true}));
    assert.ok(f.doc.activeElement === last, 'Shift+Tab wraps to confirm');
    dialog.dispatchEvent(new FakeEvent('keydown', {key: 'Tab', bubbles: true}));
    assert.ok(f.doc.activeElement === first, 'Tab wraps to close');
});
test('fragment replacement event cancels the picker and refreshes new field values', () => {
    const f = fixture(); f.click(f.trigger);
    f.click(f.query('[data-picker-date="2026-10-20"]'));
    f.form.remove();
    const replacement = f.createForm(); replacement.time.value = '11:30';
    f.doc.dispatchEvent(new FakeEvent('travelplan:schedule-updated'));
    assert.equal(f.query('[role="dialog"]'), null);
    assert.equal(f.date.value, '2026-10-19');
    assert.equal(replacement.trigger.textContent, '10월 19일 (월) · 11:30');
});
test('unrelated Day updates preserve staged calendar and time selections', () => {
    const f = fixture(); f.click(f.trigger);
    f.click(f.query('[data-picker-date="2026-10-20"]'));
    const hour = f.query('[data-picker-hour]'); const minute = f.query('[data-picker-minute]');
    hour.value = '16'; minute.value = '25';
    minute.dispatchEvent(new FakeEvent('change', {bubbles: true}));
    f.doc.dispatchEvent(new FakeEvent('travelplan:schedule-updated', {detail: {root: f.doc.createElement('section')}}));
    assert.ok(f.query('[role="dialog"]'));
    assert.equal(f.query('[data-picker-selection]').textContent, '2026년 10월 20일 · 16:25');
    assert.equal(f.query('[data-picker-date="2026-10-20"]').attrs['aria-pressed'], 'true');
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(f.date.value, '2026-10-20'); assert.equal(f.time.value, '16:25');
});
test('visible selection summary tracks calendar, hour, minute and staged clear', () => {
    const f = fixture(); f.time.value = '';
    f.api.refresh();
    assert.equal(f.trigger.textContent, '10월 19일 (월) · 시간 미정');
    f.click(f.trigger);
    const summary = f.query('[data-picker-selection]');
    const hour = f.query('[data-picker-hour]');
    const minute = f.query('[data-picker-minute]');
    assert.equal(summary.textContent, '2026년 10월 19일 · 시간 미정');
    f.click(f.query('[data-picker-date="2026-10-21"]'));
    assert.equal(summary.textContent, '2026년 10월 21일 · 시간 미정');
    hour.value = '00'; hour.dispatchEvent(new FakeEvent('change', {bubbles: true}));
    assert.equal(summary.textContent, '2026년 10월 21일 · 시간 미정');
    minute.value = '35'; minute.dispatchEvent(new FakeEvent('change', {bubbles: true}));
    assert.equal(summary.textContent, '2026년 10월 21일 · 00:35');
    f.click(f.query('[data-picker-action="clear"]'));
    assert.equal(summary.textContent, '날짜 미정 · 시간 미정');
    assert.equal(f.date.value, '2026-10-19');
});

test('numeric text fields update live, pad on blur and advance from hour to minute', () => {
    const f = fixture(false); f.click(f.trigger);
    const hour = f.query('[data-picker-hour]'), minute = f.query('[data-picker-minute]');
    for (const input of [hour, minute]) {
        assert.equal(input.tagName, 'INPUT');
        assert.equal(input.attrs.type, 'text');
        assert.equal(input.attrs.inputmode, 'numeric');
        assert.equal(input.attrs.maxlength, '2');
    }
    hour.value = '7'; hour.dispatchEvent(new FakeEvent('input'));
    minute.value = '5'; minute.dispatchEvent(new FakeEvent('input'));
    assert.equal(f.query('[data-picker-selection]').textContent, '07:05');
    hour.dispatchEvent(new FakeEvent('blur')); minute.dispatchEvent(new FakeEvent('blur'));
    assert.equal(hour.value, '07'); assert.equal(minute.value, '05');
    hour.value = '23'; hour.focus(); hour.dispatchEvent(new FakeEvent('input'));
    assert.equal(f.doc.activeElement, minute);
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(f.time.value, '23:05');
});

test('invalid hour and minute cannot commit and correction clears the error', () => {
    for (const [hourValue, minuteValue] of [['25', '30'], ['23', '70'], ['25', '70'], ['-1', '00']]) {
        const f = fixture(false); f.click(f.trigger);
        const hour = f.query('[data-picker-hour]'), minute = f.query('[data-picker-minute]');
        hour.value = hourValue; minute.value = minuteValue;
        f.click(f.query('[data-picker-action="confirm"]'));
        assert.equal(f.time.value, '09:03');
        assert.ok(f.query('[role="dialog"]'));
        assert.match(f.query('[data-picker-error]').textContent, /00.*23.*00.*59/);
        hour.value = '0'; minute.value = '0'; minute.dispatchEvent(new FakeEvent('input'));
        assert.equal(f.query('[data-picker-error]').textContent, '');
        f.click(f.query('[data-picker-action="confirm"]'));
        assert.equal(f.time.value, '00:00');
    }
});

test('pasted text is limited to digits and wheel cannot change time', () => {
    const f = fixture(false); f.click(f.trigger);
    const hour = f.query('[data-picker-hour]'), minute = f.query('[data-picker-minute]');
    hour.value = 'a1b2c3'; hour.dispatchEvent(new FakeEvent('input'));
    assert.equal(hour.value, '12');
    minute.value = '59'; minute.dispatchEvent(new FakeEvent('input'));
    hour.dispatchEvent(new FakeEvent('wheel')); minute.dispatchEvent(new FakeEvent('wheel'));
    assert.equal(hour.value, '12'); assert.equal(minute.value, '59');
    f.click(f.query('[data-picker-action="confirm"]'));
    assert.equal(f.time.value, '12:59');
});
