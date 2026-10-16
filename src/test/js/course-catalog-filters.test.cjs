const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup() {
    const listeners = {};
    const filters = Array.from({length: 3}, () => ({
        open: false,
        listeners: {},
        summary: {focused: false, focus() { this.focused = true; }},
        addEventListener(name, callback) { this.listeners[name] = callback; },
        querySelector() { return this.summary; },
        contains(target) { return target === this || target === this.summary; }
    }));
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/course-catalog-filters.js', 'utf8'), {
        document: {
            querySelectorAll: () => filters,
            addEventListener(name, callback) { listeners[name] = callback; }
        }
    });
    return {filters, listeners};
}

test('opening a filter closes the other filters', () => {
    const {filters} = setup();
    filters[0].open = true;
    filters[1].open = true;
    filters[1].listeners.toggle();
    assert.equal(filters[0].open, false);
    assert.equal(filters[1].open, true);
});

test('an outside click closes filters while a click inside keeps the filter open', () => {
    const {filters, listeners} = setup();
    filters[0].open = true;
    listeners.click({target: filters[0].summary});
    assert.equal(filters[0].open, true);
    listeners.click({target: {}});
    assert.equal(filters[0].open, false);
});

test('Escape closes the open filter and returns focus to its summary', () => {
    const {filters, listeners} = setup();
    filters[2].open = true;
    let prevented = false;
    listeners.keydown({key: 'Escape', preventDefault() { prevented = true; }});
    assert.equal(filters[2].open, false);
    assert.equal(filters[2].summary.focused, true);
    assert.equal(prevented, true);
});

test('unrelated keys and Escape with no open filter leave focus alone', () => {
    const {filters, listeners} = setup();
    listeners.keydown({key: 'Enter', preventDefault: assert.fail});
    listeners.keydown({key: 'Escape', preventDefault: assert.fail});
    assert.equal(filters.some(filter => filter.summary.focused), false);
});
