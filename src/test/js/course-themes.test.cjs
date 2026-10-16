const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup(initial = []) {
    let ready;
    const inputs = {children: initial.map(value => ({value})), querySelectorAll() { return this.children; },
        replaceChildren(...nodes) { this.children = nodes; }};
    const status = {};
    const chips = Array.from({length: 10}, (_, index) => ({dataset: {themeId: String(index + 1)}, attributes: {},
        setAttribute(key, value) { this.attributes[key] = value; },
        addEventListener(event, action) { this[event] = action; }}));
    const picker = {querySelectorAll: () => chips,
        querySelector: selector => selector === '[data-course-theme-inputs]' ? inputs : status};
    const document = {querySelector: () => picker, createElement: () => ({}),
        addEventListener(event, callback) { ready = callback; }};
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-themes.js'), 'utf8'), {document});
    ready();
    return {chips, status, payload: () => inputs.children.map(input => input.value)};
}

test('테마 0~3개를 클릭 순서로 전송하고 네 번째 선택은 안내하며 기존 선택을 보존한다', () => {
    const ui = setup(); assert.deepEqual(ui.payload(), []);
    ui.chips[6].click(); ui.chips[3].click(); ui.chips[7].click();
    assert.deepEqual(ui.payload(), ['7', '4', '8']);
    ui.chips[0].click(); assert.deepEqual(ui.payload(), ['7', '4', '8']);
    assert.match(ui.status.textContent, /최대 3개/);
    assert.equal(ui.chips.length, 10);
    ui.chips[3].click(); ui.chips[3].click();
    assert.deepEqual(ui.payload(), ['7', '8', '4']);
    ui.chips[6].click(); ui.chips[7].click(); ui.chips[3].click();
    assert.deepEqual(ui.payload(), []);
});

test('기존 mapping 순서와 selected state를 복원하고 아무 조작 없이 저장해도 보존한다', () => {
    const ui = setup(['8', '1', '4']);
    assert.deepEqual(ui.payload(), ['8', '1', '4']);
    for (const [index, expected] of [[7, 'true'], [0, 'true'], [3, 'true'], [2, 'false']]) {
        assert.equal(ui.chips[index].attributes['aria-pressed'], expected);
    }
});
