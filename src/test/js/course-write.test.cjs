const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// 실제 화면 스크립트를 실행한다. DOM만 의존성 없이 대체한다.
class Element {
    constructor(tag = 'div') {
        this.tagName = tag; this.children = []; this.dataset = {}; this.value = '';
        this.hidden = false;
        this.listeners = {}; this.attributes = {}; this.classList = {toggle() {}, add() {}};
    }
    append(...children) { children.forEach(child => {
        if (child.parentElement) child.parentElement.children = child.parentElement.children.filter(node => node !== child);
        child.parentElement = this; this.children.push(child);
    }); }
    replaceChildren(...children) { this.children.forEach(child => { child.parentElement = null; }); this.children = []; this.append(...children); }
    contains(node) { return walk(this).includes(node); }
    remove() { if (this.parentElement) this.parentElement.children = this.parentElement.children.filter(node => node !== this); this.parentElement = null; }
    setAttribute(name, value) { this.attributes[name] = value; }
    addEventListener(event, action) { (this.listeners[event] ||= []).push(action); }
    querySelectorAll() { return this.initialItems || []; }
    focus() { this.focused = true; }
    scrollIntoView() {}
    closest(selector) { return ['button', 'input', 'textarea', 'select', 'a', 'label'].includes(this.tagName) && selector.includes(this.tagName) ? this : this.parentElement?.closest(selector); }
    fire(event, extra = {}) {
        const state = {target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, ...extra};
        for (const action of this.listeners[event] || []) action(state);
        return state;
    }
}
function walk(element) { return [element, ...element.children.flatMap(walk)]; }
const findClass = (element, name) => walk(element).filter(node => node.className?.split(' ').includes(name));
const button = (element, label) => walk(element).find(node => node.tagName === 'button' && node.textContent === label);
const initialDay = (id, title, items = []) => {
    const day = new Element(); day.dataset = {dayId: id, title};
    day.initialItems = items.map(data => { const item = new Element(); item.dataset = data; return item; }); return day;
};
async function setup(initialDays = [], destinations = [{id: 8, name: '<장소>', countryId: 1}, {id: 9, name: '다른 장소', countryId: 1}], restricted = false) {
    const ids = {};
    ['course-form', 'destination-search-input', 'destination-search-button', 'destination-search-results',
        'selected-destinations', 'destination-id-inputs', 'course-write-message', 'course-submit-button',
        'course-country-id', 'course-country-helper', 'course-add-day', 'course-day-tabs', 'destination-target-day',
        'selected-course-count', 'course-search-panel', 'course-search-close',
        'course-search-target', 'course-search-message'].forEach(id => { ids[id] = new Element(); });
    ids['course-search-panel'].hidden = true;
    ids['course-form'].dataset.countryRestriction = String(restricted);
    if (restricted) ids['course-country-id'].value = '1';
    let initialize;
    const document = {
        getElementById: id => ids[id] || Object.values(ids).flatMap(walk).find(node => node.id === id),
        createElement: tag => new Element(tag),
        addEventListener: (event, action) => { if (event === 'DOMContentLoaded') initialize = action; },
        querySelectorAll: selector => selector === '[data-initial-day]' ? initialDays : []
    };
    let confirmations = 0; let confirmed = true; const requests = [];
    const context = {
        document, URLSearchParams, AbortController,
        fetch: async url => { requests.push(url); return {ok: true, json: async () => destinations}; },
        window: {initQuillEditor() {}, addEventListener() {}, confirm: () => { confirmations++; return confirmed; }, setTimeout, clearTimeout}
    };
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-write.js'), 'utf8'), context);
    initialize();
    const flush = () => new Promise(resolve => setImmediate(resolve)); await flush();
    return {
        ids, requests, flush,
        payload: () => Object.fromEntries(ids['destination-id-inputs'].children.map(input => [input.name, input.value])),
        resultButtons: () => findClass(ids['destination-search-results'], 'destination-add-button'),
        add: (index = 0) => findClass(ids['destination-search-results'], 'destination-add-button')[index].fire('click'),
        days: () => findClass(ids['selected-destinations'], 'course-day-editor').filter(day => !day.hidden),
        tabs: () => findClass(ids['course-day-tabs'], 'course-day-tab'),
        rows() { return findClass(this.days()[0], 'selected-destination-card'); },
        editor(index = 0) { return findClass(this.rows()[index], 'course-item-inline-editor')[0]; },
        switchDay(index) { this.tabs()[index].fire('click'); },
        async openSearch() { button(this.days()[0], '+ 여행지 추가').fire('click'); await flush(); },
        openItem(index = 0) { findClass(this.rows()[index], 'course-item-edit')[0].fire('click'); },
        confirmations: () => confirmations,
        confirm(value) { confirmed = value; }
    };
}

test('현재 Day 한 개만 DOM에 노출하고 탭 전환 시 제목과 전체 payload를 보존한다', async () => {
    const ui = await setup();
    assert.equal(ui.days().length, 1); assert.equal(ui.tabs().length, 1);
    const title = walk(ui.days()[0]).find(node => node.id === 'day-title-0');
    assert.equal(title.maxLength, 100); title.value = '첫째 날'; title.fire('input');
    ui.ids['course-add-day'].fire('click');
    assert.equal(ui.days().length, 1); assert.equal(ui.tabs().length, 2);
    assert.equal(ui.tabs()[1].attributes['aria-selected'], 'true'); ui.switchDay(0);
    assert.equal(walk(ui.days()[0]).find(node => node.id === 'day-title-0').value, '첫째 날');
    assert.equal(ui.payload()['days[0].title'], '첫째 날'); assert.equal(ui.payload()['days[1].dayNumber'], '2');
});

test('인라인 검색 연속 추가·전체 중복방지·제거 후 재추가가 동작한다', async () => {
    const ui = await setup([], undefined, true);
    assert.equal(ui.requests.length, 0, '검색을 열기 전에는 요청하지 않는다');
    await ui.openSearch(); assert.equal(ui.ids['course-search-panel'].hidden, false);
    assert.equal(ui.ids['course-search-panel'].parentElement, ui.days()[0]);
    assert.ok(ui.requests[0].includes('countryId=1')); ui.add();
    assert.equal(ui.resultButtons()[1].focused, true, '연속 추가할 다음 결과로 포커스를 옮긴다');
    ui.add(1);
    assert.equal(ui.ids['course-search-panel'].hidden, false);
    assert.equal(ui.resultButtons()[0].textContent, '추가됨'); assert.equal(ui.resultButtons()[0].disabled, true);
    ui.add(); assert.equal(ui.payload()['days[0].items[2].destinationId'], undefined);
    ui.ids['course-search-close'].fire('click');
    assert.equal(ui.ids['course-search-panel'].hidden, true);
    assert.equal(button(ui.days()[0], '+ 여행지 추가').focused, true);
    ui.ids['course-add-day'].fire('click'); await ui.openSearch(); ui.add();
    assert.equal(ui.payload()['days[1].items[0].destinationId'], undefined);
    ui.ids['course-search-close'].fire('click'); ui.switchDay(0); ui.openItem();
    button(ui.editor(), '일정에서 제거').fire('click'); await ui.openSearch();
    assert.equal(ui.resultButtons()[0].disabled, false); ui.add();
    assert.equal(ui.payload()['days[0].items[1].destinationId'], '8');
});

test('기본 행의 편집 입력은 숨기고 펼침·닫기·Day 전환에도 같은 입력 DOM과 payload를 보존한다', async () => {
    const ui = await setup(); await ui.openSearch(); ui.add(); ui.ids['course-search-close'].fire('click');
    assert.equal(ui.editor().hidden, true);
    assert.equal(findClass(ui.rows()[0], 'course-item-preview')[0].hidden, true, '빈 메모는 공간을 만들지 않는다'); ui.openItem();
    assert.equal(ui.editor().hidden, false);
    const times = walk(ui.editor()).filter(node => node.type === 'time');
    const memo = walk(ui.editor()).find(node => node.tagName === 'textarea');
    assert.equal(memo.maxLength, 1000); assert.equal(times.length, 1);
    assert.equal(times[0].fire('keydown', {key: 'Enter'}).defaultPrevented, true, '시간 입력 Enter로 코스를 저장하지 않는다');
    assert.equal(ui.payload()['days[0].items[0].startTime'], '');
    times[0].value = '23:00'; times[0].fire('input'); memo.value = '<script>메모</script>'; memo.fire('input');
    assert.equal(ui.payload()['days[0].items[0].endTime'], undefined); ui.openItem();
    assert.equal(ui.editor().hidden, true);
    ui.openItem(); assert.equal(walk(ui.editor()).find(node => node.tagName === 'textarea'), memo);
    assert.equal(memo.value, '<script>메모</script>');
    ui.ids['course-add-day'].fire('click'); ui.switchDay(0);
    assert.equal(walk(ui.editor()).find(node => node.tagName === 'textarea'), memo);
    assert.equal(times[0].value, '23:00');
    ui.switchDay(1); await ui.openSearch(); ui.add(1);
    assert.equal(ui.ids['course-form'].fire('submit').defaultPrevented, false);
    assert.equal(ui.payload()['days[0].items[0].memo'], '<script>메모</script>');
    assert.equal(ui.payload()['days[0].items[0].startTime'], '23:00');
});

test('키보드 탭 탐색은 한 Day만 바꾸고 새 탭 포커스를 복원한다', async () => {
    const ui = await setup([initialDay('1', '하나'), initialDay('2', '둘'), initialDay('3', '셋')]);
    assert.equal(ui.tabs()[0].tabIndex, 0);
    assert.equal(ui.tabs()[0].fire('keydown', {key: 'ArrowRight'}).defaultPrevented, true);
    assert.equal(ui.tabs()[1].attributes['aria-selected'], 'true');
    assert.equal(ui.tabs()[1].focused, true);
    ui.tabs()[1].fire('keydown', {key: 'End'});
    assert.equal(ui.tabs()[2].attributes['aria-selected'], 'true');
    ui.tabs()[2].fire('keydown', {key: 'Home'});
    assert.equal(ui.tabs()[0].attributes['aria-selected'], 'true');
    assert.equal(ui.days().length, 1);
});

test('행 클릭과 편집 버튼은 하나만 토글하고 편집 입력 클릭은 닫지 않는다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '8'}, {destinationId: '9'}])]);
    ui.rows()[0].fire('click');
    assert.equal(ui.editor().hidden, false);
    const edit = findClass(ui.rows()[0], 'course-item-edit')[0];
    assert.equal(edit.attributes['aria-expanded'], 'true');
    assert.equal(edit.attributes['aria-controls'], ui.editor().id);
    const memo = walk(ui.editor()).find(node => node.tagName === 'textarea');
    memo.value = '보존할 메모'; memo.fire('input');
    ui.rows()[0].fire('click', {target: memo});
    assert.equal(ui.editor().hidden, false);
    ui.openItem(1);
    assert.equal(ui.editor().hidden, true); assert.equal(ui.editor(1).hidden, false);
    assert.equal(edit.attributes['aria-expanded'], 'false');
    ui.openItem(1); assert.equal(ui.editor(1).hidden, true);
    ui.openItem(); assert.equal(walk(ui.editor()).find(node => node.tagName === 'textarea'), memo);
    assert.equal(memo.value, '보존할 메모');
});

test('Day 전환은 검색만 닫고 재열기는 현재 Day를 대상으로 검색어와 결과를 유지한다', async () => {
    const ui = await setup([initialDay('1', ''), initialDay('2', '')]);
    await ui.openSearch(); ui.ids['destination-search-input'].value = '검색어';
    ui.switchDay(1);
    assert.equal(ui.ids['course-search-panel'].hidden, true);
    await ui.openSearch(); ui.add();
    assert.equal(ui.ids['destination-search-input'].value, '검색어');
    assert.equal(ui.ids['course-search-panel'].parentElement, ui.days()[0]);
    assert.equal(ui.payload()['days[1].items[0].destinationId'], '8');
    assert.equal(ui.payload()['days[0].items[0].destinationId'], undefined);
    ui.ids['course-search-close'].fire('click'); ui.openItem();
    button(ui.editor(), '일정에서 제거').fire('click'); await ui.openSearch();
    assert.equal(ui.resultButtons()[0].disabled, false);
});

test('첫·마지막 순서 경계와 제거 후 재번호 및 열린 편집 상태를 정리한다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '8'}, {destinationId: '9'}])]);
    ui.openItem(); assert.equal(button(ui.editor(), '위로').disabled, true);
    button(ui.editor(), '위로').fire('click');
    assert.equal(ui.payload()['days[0].items[0].destinationId'], '8');
    ui.openItem(1); assert.equal(button(ui.editor(1), '아래로').disabled, true);
    button(ui.editor(1), '아래로').fire('click');
    button(ui.editor(1), '일정에서 제거').fire('click');
    assert.equal(ui.rows().length, 1); assert.equal(ui.editor().hidden, true);
    assert.equal(ui.payload()['days[0].items[0].displayOrder'], '1');
    assert.equal(ui.payload()['days[0].items[1].destinationId'], undefined);
});

test('검색 Enter는 저장을 막고 현재 Day에 검색어를 인코딩해 요청한다', async () => {
    const ui = await setup(); await ui.openSearch();
    ui.ids['destination-search-input'].value = '한글 & 장소';
    assert.equal(ui.ids['destination-search-input'].fire('keydown', {key: 'Enter'}).defaultPrevented, true);
    await ui.flush();
    const request = new URL(ui.requests.at(-1), 'https://example.test');
    assert.equal(request.searchParams.get('q'), '한글 & 장소');
});

test('기존 다른 Day 마지막에 이동하고 연속 재정렬해도 같은 여행지를 편집한다', async () => {
    const ui = await setup([
        initialDay('1', '원본', [{destinationId: '8', name: '첫 장소'}, {destinationId: '9', name: '둘째 장소'}, {destinationId: '11', name: '셋째 장소'}]),
        initialDay('2', '대상', [{destinationId: '10', name: '기존 장소'}])
    ]);
    ui.openItem();
    button(ui.editor(), '아래로').fire('click');
    button(ui.editor(1), '아래로').fire('click');
    assert.equal(ui.payload()['days[0].items[2].destinationId'], '8');
    button(ui.editor(2), '위로').fire('click');
    assert.equal(ui.payload()['days[0].items[1].destinationId'], '8');
    const move = walk(ui.editor(1)).find(node => node.tagName === 'select'); move.value = '1'; move.fire('change');
    assert.equal(ui.payload()['days[1].items[0].destinationId'], '10');
    assert.equal(ui.payload()['days[1].items[1].destinationId'], '8');
    assert.equal(ui.payload()['days[1].items[1].displayOrder'], '2');
    assert.equal(ui.tabs()[1].attributes['aria-selected'], 'true');
});

test('상세 패널 순서 변경과 Day 이동은 메모를 보존하고 대상 끝에 넣어 재번호한다', async () => {
    const ui = await setup(); await ui.openSearch(); ui.add(); ui.add(1); ui.ids['course-search-close'].fire('click');
    ui.ids['course-add-day'].fire('click'); ui.switchDay(0); ui.openItem();
    const memo = walk(ui.editor()).find(node => node.tagName === 'textarea'); memo.value = '이동 메모'; memo.fire('input');
    button(ui.editor(), '아래로').fire('click'); assert.equal(ui.payload()['days[0].items[1].destinationId'], '8');
    const move = walk(ui.editor(1)).find(node => node.tagName === 'select'); move.value = '1'; move.fire('change');
    assert.equal(ui.tabs()[1].attributes['aria-selected'], 'true');
    assert.equal(ui.rows().length, 1); assert.equal(ui.payload()['days[1].items[0].destinationId'], '8');
    assert.equal(ui.payload()['days[1].items[0].memo'], '이동 메모'); assert.equal(ui.payload()['days[0].items[0].displayOrder'], '1');
});

test('빈 Day도 삭제 확인을 거치고 취소·마지막 Day 보호 및 재번호를 유지한다', async () => {
    const ui = await setup(); assert.equal(button(ui.days()[0], 'Day 삭제').disabled, true); ui.ids['course-add-day'].fire('click');
    const menu = walk(ui.days()[0]).find(node => node.tagName === 'details'); assert.ok(menu);
    ui.confirm(false); button(menu, 'Day 삭제').fire('click'); assert.equal(ui.tabs().length, 2); assert.equal(ui.confirmations(), 1);
    ui.confirm(true); button(ui.days()[0], 'Day 삭제').fire('click'); assert.equal(ui.tabs().length, 1);
    assert.equal(ui.payload()['days[0].dayNumber'], '1'); assert.equal(ui.payload()['days[1].dayNumber'], undefined);
    button(ui.days()[0], 'Day 삭제').fire('click'); assert.equal(ui.tabs().length, 1);
});

test('초기 편집값과 50곳 제한, 전체 Day hidden payload 및 90일 제한을 유지한다', async () => {
    const day = initialDay('25', '기존 제목', Array.from({length: 50}, (_, index) => ({destinationId: String(index + 1), name: '장소', startTime: '23:00', endTime: '01:00', memo: '기존 메모'})));
    const ui = await setup([day], [{id: 51, name: '추가 장소', countryId: 1}]);
    assert.equal(ui.payload()['days[0].id'], '25'); assert.equal(ui.payload()['days[0].title'], '기존 제목');
    assert.equal(ui.payload()['days[0].items[49].endTime'], undefined); await ui.openSearch(); assert.equal(ui.resultButtons()[0].disabled, true); ui.add();
    assert.equal(ui.payload()['days[0].items[50].destinationId'], undefined); ui.ids['course-search-close'].fire('click');
    for (let index = 0; index < 95; index++) ui.ids['course-add-day'].fire('click');
    assert.equal(ui.tabs().length, 90); assert.equal(ui.days().length, 1); assert.equal(ui.ids['course-add-day'].disabled, true);
    assert.equal(ui.payload()['days[89].dayNumber'], '90'); assert.equal(ui.payload()['days[90].dayNumber'], undefined);
    assert.equal(ui.payload()['days[0].items[49].memo'], '기존 메모');
    assert.equal(ui.ids['course-form'].fire('submit').defaultPrevented, true);
    assert.ok(Object.keys(ui.payload()).every(name => !/name|image|snapshot|destinationIds/.test(name)));
});
