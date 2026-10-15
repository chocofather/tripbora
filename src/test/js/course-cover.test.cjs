const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function setup(existing = '', removed = false) {
    const elements = {};
    for (const key of ['#course-cover-file', '[data-cover-preview]', '[data-cover-image]',
        '[data-cover-select]', '[data-cover-remove]', '[data-cover-remove-value]', '[data-cover-message]']) {
        elements[key] = {hidden: false, value: '', files: [], listeners: {},
            addEventListener(event, callback) { this.listeners[event] = callback; },
            fire(event) { this.listeners[event]?.(); }, click() { this.clicked = true; },
            removeAttribute(name) { delete this[name]; }};
    }
    elements['[data-cover-remove-value]'].value = String(removed);
    const area = {dataset: {existingCover: existing}, querySelector: key => elements[key]};
    const revoked = []; const page = {}; let initialize; let index = 0;
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-cover.js'), 'utf8'), {
        document: {querySelector: () => area, addEventListener: (_, callback) => { initialize = callback; }},
        URL: {createObjectURL: () => `blob:preview-${++index}`, revokeObjectURL: url => revoked.push(url)},
        window: {addEventListener: (event, callback) => { page[event] = callback; }}
    });
    initialize();
    const file = elements['#course-cover-file'];
    return {elements, revoked, page,
        choose(type = 'image/png', size = 100) { file.files = [{type, size}]; file.fire('change'); }};
}

test('신규는 compact 버튼만 보이고 선택 즉시 preview를 표시한다', () => {
    const ui = setup(); const e = ui.elements;
    assert.equal(e['[data-cover-preview]'].hidden, true);
    assert.equal(e['[data-cover-remove]'].hidden, true);
    e['[data-cover-select]'].fire('click'); assert.equal(e['#course-cover-file'].clicked, true);
    ui.choose();
    assert.equal(e['[data-cover-image]'].src, 'blob:preview-1');
    assert.equal(e['[data-cover-preview]'].hidden, false);
    assert.equal(e['[data-cover-select]'].textContent, '이미지 변경');
});

test('기존 이미지 변경·삭제는 저장 의도를 보존하고 object URL을 해제한다', () => {
    const ui = setup('/uploads/course/cover/5/old.png'); const e = ui.elements;
    assert.equal(e['[data-cover-image]'].src, '/uploads/course/cover/5/old.png');
    ui.choose(); ui.choose('image/jpeg');
    assert.deepEqual(ui.revoked, ['blob:preview-1']);
    e['[data-cover-remove]'].fire('click');
    assert.equal(e['[data-cover-remove-value]'].value, 'true');
    assert.equal(e['[data-cover-preview]'].hidden, true);
    assert.equal(e['[data-cover-image]'].src, undefined);
    assert.match(e['[data-cover-message]'].textContent, /첫 번째 여행지/);
    ui.choose(); assert.equal(e['[data-cover-remove-value]'].value, 'false');
    ui.page.pagehide(); assert.equal(ui.revoked.length, 3);
});

test('취소는 preview를 유지하고 잘못된 형식과 용량은 저장 파일을 비운다', () => {
    const ui = setup('/uploads/course/cover/5/old.png'); const e = ui.elements;
    ui.choose(); e['#course-cover-file'].files = []; e['#course-cover-file'].fire('change');
    assert.equal(e['[data-cover-image]'].src, 'blob:preview-1');
    for (const [type, size] of [['image/svg+xml', 1], ['image/png', 10 * 1024 * 1024 + 1]]) {
        ui.choose(type, size);
        assert.equal(e['#course-cover-file'].value, '');
        assert.equal(e['[data-cover-image]'].src, '/uploads/course/cover/5/old.png');
        assert.match(e['[data-cover-message]'].textContent, /10MB/);
    }
});

test('삭제 의도 재표시와 이미지 로딩 실패에서도 broken preview를 숨긴다', () => {
    const ui = setup('/uploads/course/cover/5/old.png', true); const e = ui.elements;
    assert.equal(e['[data-cover-preview]'].hidden, true);
    ui.choose(); e['[data-cover-image]'].fire('error');
    assert.equal(e['[data-cover-preview]'].hidden, true);
    assert.equal(e['[data-cover-remove]'].hidden, false);
    assert.match(e['[data-cover-message]'].textContent, /표시할 수 없습니다/);
});
