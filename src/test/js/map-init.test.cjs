const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');

function setup() {
    const scripts = [];
    const context = {window: {}};
    context.document = {
        getElementById: id => scripts.find(script => script.id === id),
        createElement: () => ({src: '', listeners: {}, addEventListener(name, callback) { (this.listeners[name] ||= []).push(callback); }, remove() { scripts.splice(scripts.indexOf(this), 1); }}),
        head: {appendChild(script) { scripts.push(script); }}
    };
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/map-init.js'), 'utf8'), context);
    return {context, scripts};
}

test('Kakao 로딩 중 추가 요청은 같은 SDK를 기다리고 services 라이브러리를 사용한다', () => {
    const {context, scripts} = setup(); const called = [];
    context.loadKakaoScript(() => called.push('one'));
    context.loadKakaoScript(() => called.push('two'));
    assert.equal(scripts.length, 1);
    assert.equal(scripts[0].src.includes('libraries=services'), true);
    assert.deepEqual(called, []);
    context.kakao = context.window.kakao = {maps: {load: callback => callback()}};
    scripts[0].onload(); scripts[0].listeners.load.forEach(callback => callback());
    assert.deepEqual(called, ['one', 'two']);
});

test('실패한 SDK element는 제거하여 다음 위치보기에서 재시도할 수 있다', () => {
    const {context, scripts} = setup();
    context.loadKakaoScript(() => {});
    scripts[0].listeners.error.forEach(callback => callback());
    assert.equal(scripts.length, 0);
    context.loadKakaoScript(() => {});
    assert.equal(scripts.length, 1);
});
