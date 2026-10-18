const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {FakeElement} = require('./fake-dom.cjs');

class Node extends FakeElement {
    matches(selector) {
        return selector.split(',').some(part => {
            const pieces = part.trim().split(/\s+/);
            const last = pieces.pop();
            const classes = [...last.matchAll(/\.([\w-]+)/g)].map(match => match[1]);
            if (classes.some(name => !this.classList.contains(name))) return false;
            const attrs = [...last.matchAll(/\[([\w-]+)(?:=["']?([^\]"']+)["']?)?\]/g)];
            if (attrs.some(([, key, value]) => !this.attributes.has(key) || (value !== undefined && this.getAttribute(key) !== value))) return false;
            const tag = /^[a-z][a-z0-9]*/i.exec(last)?.[0];
            if (tag && this.tagName !== tag.toUpperCase()) return false;
            return !pieces.length || Boolean(this.parent?.closest(pieces.join(' ')));
        });
    }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector) || null; }
    get parentNode() { return this.parent; }
    get id() { return this.getAttribute('id'); }
    remove() { super.remove(); this.parent = null; }
    append(...nodes) {
        for (const node of nodes) {
            if (node.tagName === '#FRAGMENT') { this.append(...node.children.slice()); continue; }
            node.remove(); super.append(node);
        }
    }
    replaceChildren(...nodes) { this.children.slice().forEach(node => node.remove()); this.append(...nodes); }
    insertBefore(node, before) {
        node.remove();
        const index = before ? this.children.indexOf(before) : this.children.length;
        assert.ok(index >= 0); node.parent = this; this.children.splice(index, 0, node);
    }
    cloneNode(deep) {
        const copy = new Node(this.tagName, Object.fromEntries(this.attributes));
        copy.className = this.className; copy.textContent = this.textContent; copy.hidden = this.hidden;
        if (deep) copy.append(...this.children.map(child => child.cloneNode(true)));
        return copy;
    }
    focus() {}
    getBoundingClientRect() { return {top: 0, bottom: 100, height: 100}; }
}

function fixture({start = '2026-10-17', end = '2026-10-19', today = '2026-10-18'} = {}) {
    const make = (tag, attrs) => new Node(tag, attrs);
    const document = make('document');
    document.createElement = tag => make(tag);
    document.createDocumentFragment = () => make('#fragment');
    const planning = make('div'); planning.className = 'is-planning'; document.append(planning);
    const planner = make('main', {'data-plan-id': '1', 'data-trip-start': start, 'data-trip-end': end}); planning.append(planner);
    const calendar = make('div', {'data-planner-calendar': ''});
    const summaries = make('div', {'data-planner-summaries': ''}); planner.append(calendar, summaries);
    const groups = ['7', '8', '9'].map((id, index) => {
        const planDate = new Date(`${start}T00:00:00Z`); planDate.setUTCDate(planDate.getUTCDate() + index);
        const day = make('section', {'data-travel-plan-day': '', 'data-day-id': id, 'data-plan-date': planDate.toISOString().slice(0, 10), id: `day-${id}`});
        const summary = make('section', {'data-planner-summary': id});
        const host = make('div', {'data-planner-summary-items': ''}); summary.append(host); summaries.append(summary); planner.append(day);
        return {id, day, summary, host};
    });
    function addItem(group, id, type = 'FLIGHT') {
        const row = make('li', {'data-travel-plan-item': '', 'data-item-id': id, 'data-item-type': type});
        const view = make('div', {'data-item-view': ''}); view.hidden = true;
        const title = make('h3'); title.textContent = '구간 / 장소 입력하기';
        const edit = make('button', {'data-item-edit-button': ''});
        view.append(title, edit); row.append(view); group.day.append(row); return row;
    }
    addItem(groups[0], 'a'); addItem(groups[0], 'b', 'TRANSPORT'); addItem(groups[1], 'ordinary', 'NORMAL');
    const bar = make('div', {'data-overview-insert-bar': '', 'data-day-id': '9', 'data-insert-placement': 'end', 'data-insert-default': 'true', 'data-before-item-id': ''});
    const grip = make('button', {'data-overview-insert-handle': ''});
    bar.append(grip, make('span', {'data-overview-insert-status': ''})); groups[2].host.append(bar);
    let currentDay = today;
    // The wall clock intentionally differs from its UTC ISO date. Calendar dates
    // still use the native UTC methods, as the production renderer does.
    class ClockDate extends Date {
        constructor(...args) { super(...(args.length ? args : ['2027-01-01T03:00:00Z'])); this.wallClock = !args.length; }
        getFullYear() { return this.wallClock ? Number(currentDay.slice(0, 4)) : super.getFullYear(); }
        getMonth() { return this.wallClock ? Number(currentDay.slice(5, 7)) - 1 : super.getMonth(); }
        getDate() { return this.wallClock ? Number(currentDay.slice(8, 10)) : super.getDate(); }
    }
    const window = {addEventListener() {}, requestAnimationFrame: () => 1};
    const context = vm.createContext({document, window, Date: ClockDate, FormData: class {}, CustomEvent: class {}, console});
    // Execute both actual deferred scripts, in detail.html's current order.
    for (const name of ['travel-plan-overview.js', 'travel-plan-overview-insert.js']) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js', name), 'utf8'), context, {filename: name});
    }
    document.dispatchEvent({type: 'DOMContentLoaded'});
    const update = () => document.dispatchEvent({type: 'travelplan:schedule-updated'});
    const key = value => {
        for (const listener of planner.listeners.keydown || []) listener({target: grip, key: value, preventDefault() {}, stopImmediatePropagation() {}});
    };
    return {document, planner, calendar, groups, bar, window, update, key, addItem, setToday: value => { currentDay = value; }};
}

function assertOneBar(f, host) {
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
    assert.equal(host.querySelector('[data-overview-insert-bar]'), f.bar);
    assert.equal(host.children.at(-1), f.bar);
}

test('actual script initialization retains the SSR insertion bar on the empty last DAY and through repeated refreshes', () => {
    const f = fixture();
    assertOneBar(f, f.groups[2].host);
    assert.ok(f.window.travelPlanOverviewInsert);
    assert.equal(f.bar.dataset.dayId, '9'); assert.equal(f.bar.dataset.insertDefault, 'true');
    f.update(); f.update();
    assertOneBar(f, f.groups[2].host);
});

test('actual overview refresh preserves a manually selected keyboard anchor across card rebuilds', () => {
    const f = fixture();
    f.key('Home'); f.key('ArrowDown'); f.key('ArrowDown');
    assert.equal(f.bar.dataset.beforeItemId, 'b');
    f.update(); f.update();
    assert.equal(f.bar.dataset.dayId, '7'); assert.equal(f.bar.dataset.insertDefault, 'false');
    assert.equal(f.bar.dataset.beforeItemId, 'b');
    const host = f.groups[0].host;
    assert.equal(host.children[host.children.indexOf(f.bar) + 1].dataset.overviewItemId, 'b');
    assert.equal(f.planner.querySelectorAll('[data-overview-insert-bar]').length, 1);
});

test('authoritative lower empty typed card addition and removal rebuild upper cards without losing the global bar', () => {
    const f = fixture();
    const row = f.addItem(f.groups[2], 'new', 'ACCOMMODATION'); f.update();
    const card = f.groups[2].host.querySelector('[data-overview-item-id="new"]');
    assert.ok(card); assert.equal(card.dataset.type, 'ACCOMMODATION');
    assert.equal(card.querySelector('h3').textContent, '구간 / 장소 입력하기');
    assert.equal(card.querySelector('[data-overview-item-edit="new"]').getAttribute('aria-expanded'), 'false');
    assertOneBar(f, f.groups[2].host);
    row.remove(); f.update();
    assert.equal(f.groups[2].host.querySelector('[data-overview-item-id]'), null);
    assertOneBar(f, f.groups[2].host);
});

test('calendar marks only the browser local today and preserves its trip link', () => {
    const f = fixture();
    const today = f.calendar.querySelectorAll('.is-today');
    assert.equal(today.length, 1);
    assert.equal(today[0].textContent, '18'); assert.equal(today[0].getAttribute('aria-current'), 'date');
    assert.equal(today[0].classList.contains('is-trip-date'), true); assert.equal(today[0].href, '#day-8');
    const otherMonth = fixture({start: '2026-12-01', end: '2026-12-03'});
    assert.equal(otherMonth.calendar.querySelectorAll('.is-today').length, 0);
});

test('calendar local today handles December/January boundaries without using UTC today', () => {
    const f = fixture({start: '2026-12-31', end: '2027-01-02', today: '2026-12-31'});
    const months = f.calendar.querySelectorAll('.tp-planner-month');
    assert.equal(months.length, 2);
    assert.equal(months[0].querySelector('.is-today').textContent, '31');
    assert.equal(months[0].querySelector('.is-today').getAttribute('aria-current'), 'date');
    assert.equal(months[1].querySelector('.is-today'), null);
});

test('returning to the visible page refreshes local today after midnight', () => {
    const f = fixture({start: '2026-12-31', end: '2027-01-02', today: '2026-12-31'});
    f.setToday('2027-01-01'); f.document.hidden = false;
    f.document.dispatchEvent({type: 'visibilitychange'});
    const today = f.calendar.querySelectorAll('.is-today');
    assert.equal(today.length, 1); assert.equal(today[0].textContent, '1');
    assert.equal(today[0].href, '#day-8'); assert.equal(today[0].getAttribute('aria-current'), 'date');
    assert.equal(f.calendar.querySelectorAll('.tp-planner-month')[0].querySelector('.is-today'), null);
});
