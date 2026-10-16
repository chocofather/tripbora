const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

class Element {
    constructor(tag = 'div') { this.tagName = tag; this.children = []; this.dataset = {}; this.listeners = {}; this.hidden = false; this.value = ''; this.classList = {add() {}}; }
    append(...items) { this.children.push(...items); }
    replaceChildren(...items) { this.children = items; }
    setAttribute(name, value) { this[name] = value; }
    addEventListener(name, action) { this.listeners[name] = action; }
    fire(name, extra = {}) { const e = {preventDefault() { this.defaultPrevented = true; }, ...extra}; this.listeners[name]?.(e); return e; }
}
const walk = node => [node, ...node.children.flatMap(walk)];
const named = (root, name) => walk(root).find(node => node.textContent === name);
function setup() {
    const maps = [];
    class Map { constructor(container, options) { this.center = options.center; maps.push(this); } setCenter(center) { this.center = center; } getCenter() { return this.center; } relayout() { this.relayouts = (this.relayouts || 0) + 1; } }
    class LatLng { constructor(lat, lng) { this.lat = lat; this.lng = lng; } getLat() { return this.lat; } getLng() { return this.lng; } }
    class Marker { setPosition(position) { this.position = position; } setMap() {} }
    const window = {kakao: {maps: {Map, LatLng, Marker, event: {addListener(map, name, action) { map[name] = action; }},
        services: {Status: {OK: 'OK'}, Places: class { keywordSearch(q, cb) { cb([{place_name: '검색 결과', y: '37.5', x: '127.1', road_address_name: '검색 주소'}], 'OK'); } }}}}};
    window.kakao.maps.load = action => action();
    const document = {createElement: tag => new Element(tag), getElementById() {}, querySelectorAll: () => []};
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-location.js'), 'utf8'), {window, document, setTimeout, clearTimeout, URLSearchParams});
    return {api: window.CourseLocation, maps, LatLng, window, document};
}

test('좌표는 0을 포함한 pair/range만 허용하고 빈값·한쪽·NaN·Infinity를 거부한다', () => {
    const {api} = setup();
    assert.equal(api.validCoordinates(0, 0), true);
    for (const pair of [[null, null], ['', ''], [' ', '0'], [null, 2], [1, undefined], [91, 0], [0, -181], ['NaN', 0], [Infinity, 2]]) assert.equal(api.validCoordinates(...pair), false);
});

test('위치 검색·선택은 임시 좌표이며 위치 사용 시에만 저장하고 지우면 pair를 함께 비운다', async () => {
    const {api} = setup(); const container = new Element(); const item = {name: '사용자 이름', address: '사용자 주소', countryCode: 'KR'}; let changes = 0;
    api.attach(container, item, () => changes++);
    named(container, '위치 찾기').fire('click');
    await named(container, '검색').listeners.click();
    named(container, '검색 결과').fire('click');
    assert.equal(item.latitude, undefined); assert.equal(changes, 0);
    named(container, '위치 사용').fire('click');
    assert.equal(Number(item.latitude), 37.5); assert.equal(Number(item.longitude), 127.1);
    assert.equal(item.name, '사용자 이름'); assert.equal(item.address, '검색 주소');
    assert.equal(item.locationAddress, '검색 주소');
    named(container, '위치 지우기').fire('click');
    assert.equal(item.latitude, null); assert.equal(item.longitude, null); assert.equal(changes, 2);
});

test('해외는 Kakao 검색과 좌표 입력 없이 기존 좌표를 보존하고 명시적으로 지운다', () => {
    const {api} = setup(); const container = new Element(); const item = {latitude: 48, longitude: 2, countryCode: 'FR'};
    api.attach(container, item, () => {});
    assert.equal(named(container, '위치 찾기'), undefined);
    assert.equal(walk(container).filter(node => node.tagName === 'input' && node.type !== 'hidden').length, 0);
    assert.equal(item.latitude, 48);
    named(container, '위치 지우기').fire('click');
    assert.equal(item.latitude, null); assert.equal(item.longitude, null);
});

test('작성 위치 편집의 SDK 실패는 기존 좌표를 바꾸지 않는다', async () => {
    const {api, window} = setup(); delete window.kakao;
    const root = new Element(); const item = {latitude: '37', longitude: '127', countryCode: 'KR'};
    api.attach(root, item, () => assert.fail('SDK 실패로 저장값을 바꾸면 안 된다'));
    named(root, '위치 찾기').fire('click');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(item.latitude, '37'); assert.equal(item.longitude, '127');
    assert.match(walk(root).find(node => node.className === 'course-location-status').textContent, /지도를 불러오지 못했습니다/);
});

test('검색을 닫거나 편집기를 교체한 뒤 늦게 온 결과는 표시하지 않는다', async () => {
    const {api, window} = setup(); let receive;
    window.kakao.maps.services.Places = class { keywordSearch(q, callback) { receive = callback; } };
    const root = new Element();
    api.attach(root, {name: '검색', countryCode: 'KR'}, () => {}); named(root, '위치 찾기').fire('click');
    await named(root, '검색').listeners.click(); named(root, '위치 찾기').fire('click');
    receive([{place_name: '늦은 결과', x: '127', y: '37'}], 'OK');
    assert.equal(named(root, '늦은 결과'), undefined);
    api.attach(root, {name: '새 위치'}, () => {});
    receive([{place_name: '이전 검색 결과', x: '127', y: '37'}], 'OK');
    assert.equal(named(root, '이전 검색 결과'), undefined);
});

test('SDK 로딩 중 위치를 지우면 늦은 지도 초기화가 지도를 다시 표시하지 않는다', async () => {
    const {api, window, maps} = setup(); let ready;
    window.kakao.maps.load = callback => { ready = callback; };
    const root = new Element(); const item = {latitude: 37, longitude: 127, countryCode: 'KR'};
    api.attach(root, item, () => {}); named(root, '위치 찾기').fire('click');
    named(root, '위치 지우기').fire('click'); ready();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(maps.length, 0);
    assert.equal(walk(root).find(node => node.className === 'course-location-canvas').hidden, true);
});

test('국내 좌표는 모두 hidden이고 지도 클릭으로 선택한 좌표를 적용한다', async () => {
    const {api, maps} = setup(); const root = new Element();
    api.attach(root, {latitude: 37, longitude: 127, countryCode: 'KR'}, () => {});
    named(root, '위치 찾기').fire('click'); await new Promise(resolve => setImmediate(resolve));
    const values = walk(root).filter(node => node.className === 'course-coordinate-input');
    assert.ok(values.every(input => input.type === 'hidden'));
    maps[0].click({latLng: {getLat: () => 38, getLng: () => 128}}); named(root, '위치 사용').fire('click');
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(maps[0].center.getLat(), 38); assert.equal(maps[0].center.getLng(), 128);
});
