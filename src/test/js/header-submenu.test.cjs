const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup(heights = [68, 96, 96]) {
    const properties = new Map();
    const events = {};
    let resize;
    let fonts;
    const groups = heights.map(height => ({getBoundingClientRect: () => ({top:70, height})}));
    const nav = {getBoundingClientRect: () => ({top:0}), style: {setProperty: (key, value) => properties.set(key, value)}};
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/menu.js', 'utf8'), {
        document: {
            querySelector: () => nav,
            querySelectorAll: () => groups,
            fonts: {ready: {then: callback => { fonts = callback; }}}
        },
        window: {addEventListener: (event, callback) => { events[event] = callback; }},
        ResizeObserver: class { constructor(callback) { resize = callback; } observe() {} }
    });
    return {properties, groups, events, resize: () => resize(), fonts: () => fonts()};
}

test('backdrop fits the tallest submenu without writing horizontal offsets', () => {
    const state = setup([68, 96.3, 82]);
    assert.deepEqual([...state.properties], [['--header-submenu-top', '70px'], ['--header-submenu-height', '97px']]);
});

test('viewport, font and content changes resize the shared backdrop', () => {
    const state = setup();
    for (const update of [state.events.resize, state.fonts, state.resize]) {
        state.groups[0].getBoundingClientRect = () => ({top:68.5, height:125.2});
        update();
        assert.equal(state.properties.get('--header-submenu-height'), '126px');
        assert.equal(state.properties.get('--header-submenu-top'), '68.5px');
    }
});

test('pages without a header or submenu remain safe', () => {
    assert.doesNotThrow(() => vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/menu.js', 'utf8'), {
        document: {querySelector: () => null, querySelectorAll: () => []}
    }));
});
