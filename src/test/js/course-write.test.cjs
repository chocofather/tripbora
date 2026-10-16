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
    insertBefore(child, reference) { this.append(child); if (reference && this.children.includes(reference)) { this.children.pop(); this.children.splice(this.children.indexOf(reference), 0, child); } }
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
    day.initialItems = items.map(data => { const item = new Element(); item.dataset = {countryId: '1', ...data}; return item; }); return day;
};
async function setup(initialDays = [], destinations = [{id: 8, name: '<장소>', countryId: 1}, {id: 9, name: '다른 장소', countryId: 1}], formConfig = {}) {
    const ids = {};
    ['course-form', 'destination-search-input', 'destination-search-button', 'destination-search-results',
        'selected-destinations', 'destination-id-inputs', 'course-write-message', 'course-submit-button',
        'course-add-day', 'course-day-tabs', 'destination-target-day',
        'selected-course-count', 'course-search-panel', 'course-search-close',
        'course-search-target', 'course-search-message'].forEach(id => { ids[id] = new Element(); });
    ids['course-search-panel'].hidden = true;
    Object.assign(ids['course-form'].dataset, formConfig);
    const countries = [{countryId: '1', countryCode: 'KR', countryName: '대한민국'}, {countryId: '2', countryCode: 'JP', countryName: '일본'}, {countryId: '3', countryCode: 'FR', countryName: '프랑스'}]
        .map(data => { const option = new Element('option'); option.dataset = data; return option; });
    let initialize;
    const document = {
        getElementById: id => ids[id] || Object.values(ids).flatMap(walk).find(node => node.id === id),
        createElement: tag => new Element(tag),
        addEventListener: (event, action) => { if (event === 'DOMContentLoaded') initialize = action; },
        querySelectorAll: selector => selector === '[data-initial-day]' ? initialDays : selector === '[data-course-country]' ? countries : []
    };
    let confirmations = 0; let confirmed = true; const requests = [];
    const attachedLocations = [];
    const quillCalls = [];
    const context = {
        document, URLSearchParams, AbortController,
        fetch: async url => { requests.push(url); return {ok: true, json: async () => url.startsWith('/api/search/regions')
            ? [{id: 11, regionName: '제주'}, {id: 12, regionName: '서울'}] : destinations}; },
        window: {CourseLocation: {attach(container, item, onChange) { attachedLocations.push({container, item, onChange, countryCode: item.countryCode}); }},
            initQuillEditor(...args) { quillCalls.push(args); },
            addEventListener() {}, confirm: () => { confirmations++; return confirmed; }, setTimeout, clearTimeout}
    };
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-write.js'), 'utf8'), context);
    initialize();
    const flush = () => new Promise(resolve => setImmediate(resolve)); await flush();
    return {
        ids, requests, flush, attachedLocations, quillCalls,
        payload: () => Object.fromEntries(ids['destination-id-inputs'].children.map(input => [input.name, input.value])),
        resultButtons: () => findClass(ids['destination-search-results'], 'destination-add-button'),
        add: (index = 0) => findClass(ids['destination-search-results'], 'destination-add-button')[index].fire('click'),
        days: () => findClass(ids['selected-destinations'], 'course-day-editor').filter(day => !day.hidden),
        tabs: () => findClass(ids['course-day-tabs'], 'course-day-tab'),
        rows() { return findClass(this.days()[0], 'selected-destination-card'); },
        editor(index = 0) { return findClass(this.rows()[index], 'course-item-inline-editor')[0]; },
        switchDay(index) { this.tabs()[index].fire('click'); },
        async openSearch(empty = false) {
            // 기존 결과 조작 테스트는 이제 검색어를 명시해 검색한다.
            if (!empty && !ids['destination-search-input'].value) ids['destination-search-input'].value = '장소';
            button(this.days()[0], '+ 여행지 추가').fire('click'); await flush();
        },
        openItem(index = 0) { findClass(this.rows()[index], 'course-item-edit')[0].fire('click'); },
        confirmations: () => confirmations,
        confirm(value) { confirmed = value; }
    };
}

test('검증 코스 폼은 소개 에디터 없이 기존 일정 검증과 payload를 사용한다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '8', name: '서울'}])], [], {
        contentEditor: 'none'
    });
    assert.equal(ui.quillCalls.length, 0);
    assert.equal(ui.ids['course-form'].fire('submit').defaultPrevented, false);
    assert.equal(ui.payload()['days[0].items[0].destinationId'], '8');
    assert.equal(ui.ids['course-submit-button'].disabled, true);
});

test('소개 에디터가 없어도 빈 Day 저장은 거절하고 저장 버튼을 유지한다', async () => {
    const ui = await setup([], [], {contentEditor: 'none'});
    assert.equal(ui.ids['course-form'].fire('submit').defaultPrevented, true);
    assert.equal(ui.ids['course-submit-button'].disabled, false);
});

test('일반 여행자 폼은 기존 Quill 초기화 계약을 유지한다', async () => {
    const ui = await setup([], [], {initialContentId: 'initial-content'});
    assert.deepEqual(ui.quillCalls[0], ['#editor', 'content-input', 'course-form', 'initial-content']);
});

test('기존 일정과 검색에서 추가한 일정은 서버의 동일 locationLabel을 표시한다', async () => {
    const ui = await setup([initialDay('1', '', [
        {destinationId: '15', name: '경복궁', regionName: '종로구', locationLabel: '서울 종로구'}
    ])], [{id: 16, name: '오사카성', regionName: '오사카', parentRegionName: '기존 값', locationLabel: '일본 오사카'}]);
    assert.equal(findClass(ui.rows()[0], 'selected-card-region')[0].textContent, '장소 · 서울 종로구');
    await ui.openSearch();
    assert.equal(findClass(ui.ids['destination-search-results'], 'destination-card-region')[0].textContent, '지역 일본 오사카');
    ui.add();
    assert.equal(findClass(ui.rows()[1], 'selected-card-region')[0].textContent, '장소 · 일본 오사카');
    assert.ok(Object.keys(ui.payload()).every(key => !key.includes('locationLabel')));
});

test('빈 검색은 조회하지 않고 keyword-only 검색은 가능하다', async () => {
    const ui = await setup(); await ui.openSearch(true);
    assert.equal(ui.requests.length, 0);
    assert.match(ui.ids['course-search-message'].textContent, /지역을 선택하거나/);
    ui.ids['destination-search-input'].value = '에펠탑';
    ui.ids['destination-search-button'].fire('click'); await ui.flush();
    assert.equal(new URL(ui.requests.at(-1), 'https://test').searchParams.get('q'), '에펠탑');
});

test('직접 상세정보는 DOM과 값을 보존하고 국가 변경은 좌표만 초기화한다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '', name: '호텔', itemType: 'STAY', latitude: '37', longitude: '127', address: '직접 쓴 주소'}])]);
    ui.openItem();
    const details = findClass(ui.editor(), 'course-direct-details')[0];
    const toggle = findClass(ui.editor(), 'course-direct-details-toggle')[0];
    assert.equal(details.hidden, true); toggle.fire('click');
    assert.equal(toggle.attributes['aria-expanded'], 'true');
    const memo = walk(details).find(node => node.tagName === 'textarea');
    memo.value = '예약 정보'; memo.fire('input'); toggle.fire('click');
    assert.equal(ui.payload()['days[0].items[0].memo'], '예약 정보');
    assert.equal(ui.payload()['days[0].items[0].latitude'], '37');
    const country = walk(ui.editor()).find(node => node.dataset.directField === 'countryId');
    country.value = '2'; country.fire('change');
    assert.equal(ui.payload()['days[0].items[0].latitude'], '');
    assert.equal(ui.payload()['days[0].items[0].longitude'], '');
    assert.equal(ui.payload()['days[0].items[0].address'], '직접 쓴 주소');
});

test('국내 지역과 해외 국가는 검색 조건만 바꾸고 혼합 일정과 중복 상태를 유지한다', async () => {
    const ui = await setup(); await ui.openSearch(true);
    const geography = findClass(ui.ids['course-search-panel'], 'course-search-geography')[0];
    const selects = walk(geography).filter(node => node.tagName === 'select');
    const [country, region] = selects;
    button(geography, '국내').fire('click'); await ui.flush();
    assert.equal(country.hidden, true); assert.equal(region.hidden, false);
    region.value = '11'; region.fire('change'); await ui.flush();
    let params = new URL(ui.requests.at(-1), 'https://test').searchParams;
    assert.equal(params.get('scope'), 'domestic'); assert.equal(params.get('regionId'), '11');
    region.value = '12'; region.fire('change'); await ui.flush();
    assert.equal(new URL(ui.requests.at(-1), 'https://test').searchParams.get('regionId'), '12');
    button(geography, '해외').fire('click'); country.value = '2'; country.fire('change'); await ui.flush();
    params = new URL(ui.requests.at(-1), 'https://test').searchParams;
    assert.equal(params.get('countryId'), '2'); assert.equal(params.has('regionId'), false);
    ui.add(); country.value = '3'; country.fire('change'); await ui.flush(); ui.add(1);
    assert.equal(ui.payload()['days[0].items[0].destinationId'], '8');
    assert.equal(ui.payload()['days[0].items[1].destinationId'], '9');
    assert.equal(ui.resultButtons()[0].textContent, '추가됨');
    button(geography, '전체').fire('click'); await ui.flush();
    assert.equal(ui.payload()['days[0].items[1].destinationId'], '9');
    assert.equal(country.hidden, true); assert.equal(region.hidden, true);
});

test('국가 변경은 검색이 채운 주소만 비우고 사용자가 고친 주소는 보존한다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '', name: '호텔', itemType: 'STAY'}])]);
    ui.openItem();
    const location = ui.attachedLocations.at(-1);
    location.item.address = '검색 주소'; location.item.locationAddress = '검색 주소';
    location.item.latitude = '37'; location.item.longitude = '127'; location.onChange();
    const country = walk(ui.editor()).find(node => node.dataset.directField === 'countryId');
    country.value = '2'; country.fire('change');
    assert.equal(ui.payload()['days[0].items[0].address'], '');
    location.item.address = '다시 검색'; location.item.locationAddress = '다시 검색'; location.onChange();
    const address = walk(ui.editor()).find(node => node.dataset.directField === 'address');
    address.value = '사용자가 수정한 주소'; address.fire('input');
    country.value = '3'; country.fire('change');
    assert.equal(ui.payload()['days[0].items[0].address'], '사용자가 수정한 주소');
});

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
    const ui = await setup();
    assert.equal(ui.requests.length, 0, '검색을 열기 전에는 요청하지 않는다');
    await ui.openSearch(); assert.equal(ui.ids['course-search-panel'].hidden, false);
    assert.equal(ui.ids['course-search-panel'].parentElement, ui.days()[0]);
    assert.equal(new URL(ui.requests[0], 'https://example.test').searchParams.has('countryId'), false); ui.add();
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

test('직접 항목은 같은 이름도 보존하고 연결 항목과 구분해 전송한다', async () => {
    const ui = await setup([initialDay('1', '', [
        {destinationId: '', itemId: '31', name: '우리 숙소', itemType: 'STAY', address: '서울', latitude: '37.5', longitude: '127', startTime: '14:00', memo: '예약'},
        {destinationId: '', itemId: '32', name: '우리 숙소', itemType: 'ETC'},
        {destinationId: '8', name: '등록 장소', itemType: 'PLACE', address: '전송 금지'}
    ])]);
    assert.equal(ui.rows().length, 3);
    const payload = ui.payload();
    assert.equal(payload['days[0].items[0].destinationId'], '');
    assert.equal(payload['days[0].items[0].placeName'], '우리 숙소');
    assert.equal(payload['days[0].items[0].latitude'], '37.5');
    assert.equal(payload['days[0].items[1].itemType'], 'ETC');
    assert.equal(payload['days[0].items[2].address'], undefined);
    ui.openItem(); ui.openItem(1);
    assert.notEqual(ui.editor().id, ui.editor(1).id);
    assert.equal(findClass(ui.rows()[0], 'selected-card-image').length, 0);
});

test('직접 추가 모드는 유형 검증과 입력 보존 및 이름 중복 추가를 지원한다', async () => {
    const ui = await setup(); await ui.openSearch();
    const mode = button(ui.ids['course-search-panel'], '직접 추가'); assert.ok(mode); mode.fire('click');
    const draft = findClass(ui.ids['course-search-panel'], 'course-direct-draft')[0];
    const name = walk(draft).find(node => node.dataset.directField === 'name');
    assert.deepEqual(findClass(draft, 'course-direct-types')[0].children.map(option => option.textContent), ['장소', '숙소', '식당·카페', '교통', '기타']);
    button(draft, '일정에 추가').fire('click'); assert.equal(ui.rows().length, 0);
    button(draft, '식당·카페').fire('click'); name.value = '동네 카페'; name.fire('input');
    const country = walk(draft).find(node => node.dataset.directField === 'countryId');
    country.value = '1'; country.fire('change');
    ui.ids['course-search-close'].fire('click'); await ui.openSearch(); assert.equal(name.value, '동네 카페');
    button(draft, '일정에 추가').fire('click');
    name.value = '동네 카페'; name.fire('input'); button(draft, '식당·카페').fire('click');
    country.value = '2'; country.fire('change');
    button(draft, '일정에 추가').fire('click'); assert.equal(ui.rows().length, 2);
    assert.equal(ui.payload()['days[0].items[1].placeName'], '동네 카페');
    assert.ok(walk(draft).every(node => !node.required), '저장하지 않은 draft는 outer form native validation을 막지 않는다');
});

test('직접 항목 편집값과 DOM은 혼합 일정 재정렬·Day 이동·제거 중 보존된다', async () => {
    const ui = await setup([
        initialDay('1', '', [{destinationId: '', name: '이동', itemType: 'TRANSPORT'}, {destinationId: '8', name: '연결'}]),
        initialDay('2', '', [{destinationId: '', name: '기타', itemType: 'ETC'}])
    ]);
    ui.openItem();
    const name = walk(ui.editor()).find(node => node.dataset.directField === 'name');
    name.value = '공항 버스'; name.fire('input');
    button(ui.editor(), '아래로').fire('click');
    assert.equal(walk(ui.editor(1)).find(node => node.dataset.directField === 'name'), name);
    assert.equal(ui.payload()['days[0].items[1].placeName'], '공항 버스');
    const move = walk(ui.editor(1)).find(node => node.tagName === 'select' && !node.dataset.directField);
    move.value = '1'; move.fire('change'); ui.openItem(1);
    assert.equal(walk(ui.editor(1)).find(node => node.dataset.directField === 'name'), name);
    assert.equal(ui.payload()['days[1].items[1].displayOrder'], '2');
    button(ui.editor(1), '일정에서 제거').fire('click');
    assert.equal(ui.payload()['days[1].items[1].placeName'], undefined);
    assert.equal(ui.payload()['days[0].items[0].destinationId'], '8');
});

test('직접 항목의 잘못된 좌표쌍과 빈 이름은 저장을 막고 해당 편집을 펼친다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '', name: '장소', itemType: 'PLACE', latitude: '37'}])]);
    assert.equal(ui.ids['course-form'].fire('submit').defaultPrevented, true);
    assert.equal(ui.editor().hidden, false);
    const valid = await setup([initialDay('1', '', [{destinationId: '', name: '장소', itemType: 'PLACE'}])]);
    valid.openItem(); const name = walk(valid.editor()).find(node => node.dataset.directField === 'name');
    name.value = ' '; name.fire('input');
    assert.equal(valid.ids['course-form'].fire('submit').defaultPrevented, true);
    name.value = '복원'; name.fire('input');
    assert.equal(valid.ids['course-form'].fire('submit').defaultPrevented, false);
});

test('항목별 국가 선택은 위치 UI와 직접 payload에 반영하고 다른 항목을 보존한다', async () => {
    const ui = await setup([initialDay('1', '', [{destinationId: '', name: '숙소', itemType: 'STAY'}])]);
    ui.openItem(); assert.equal(ui.attachedLocations.at(-1).countryCode, 'KR');
    const initialName = walk(ui.editor()).find(node => node.dataset.directField === 'name');
    const initialCountry = walk(ui.editor()).find(node => node.dataset.directField === 'countryId');
    initialCountry.value = '2'; initialCountry.fire('change');
    assert.equal(ui.attachedLocations.at(-1).countryCode, 'JP');
    assert.equal(walk(ui.editor()).find(node => node.dataset.directField === 'name'), initialName);
    await ui.openSearch(); button(ui.ids['course-search-panel'], '직접 추가').fire('click');
    const draft = findClass(ui.ids['course-search-panel'], 'course-direct-draft')[0];
    button(draft, '장소').fire('click');
    for (const [field, value] of [['countryId', '1'], ['name', '직접 장소']]) {
        const input = walk(draft).find(node => node.dataset.directField === field); input.value = value; input.fire(field === 'itemType' || field === 'countryId' ? 'change' : 'input');
    }
    button(draft, '일정에 추가').fire('click');
    ui.openItem(1); assert.equal(ui.attachedLocations.at(-1).countryCode, 'KR');
    assert.equal(ui.payload()['days[0].items[0].countryId'], '2');
    assert.equal(ui.payload()['days[0].items[1].countryId'], '1');
    assert.ok(Object.keys(ui.payload()).every(key => !key.includes('countryCode')));
});

test('다른 국가의 등록 여행지는 국가 선택 없이 연속 추가하며 직접 항목만 국가를 검증한다', async () => {
    const ui = await setup([], [{id: 8, name: '한국 장소', countryId: 1}, {id: 9, name: '일본 장소', countryId: 2}]);
    await ui.openSearch(); ui.add(); ui.add(1);
    assert.equal(ui.rows().length, 2); assert.equal(ui.confirmations(), 0);
    assert.equal(ui.payload()['days[0].items[1].destinationId'], '9');
    assert.equal(ui.payload()['days[0].items[1].countryId'], undefined);
    const invalid = await setup([initialDay('1', '', [{destinationId: '', countryId: '', name: '직접 장소', itemType: 'PLACE'}])]);
    assert.equal(invalid.ids['course-form'].fire('submit').defaultPrevented, true);
    assert.ok(invalid.ids['course-write-message'].textContent.includes('국가'));
});
