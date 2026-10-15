const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

class Element {
    constructor(dataset = {}) {
        this.dataset = dataset;
        this.hidden = false;
        this.listeners = {};
        this.attributes = {};
    }
    addEventListener(type, handler) { this.listeners[type] = handler; }
    setAttribute(name, value) { this.attributes[name] = value; }
    fire(type, event = {}) { this.listeners[type]?.(event); }
}

function setup({dayCount = 3, failedHero = false} = {}) {
    const days = Array.from({length: dayCount}, (_, index) => new Element({courseDay: String(index + 1)}));
    const buttons = ['all', ...days.map(day => day.dataset.courseDay)].map(courseDayFilter => new Element({courseDayFilter}));
    const nav = new Element();
    nav.hidden = true;
    nav.querySelectorAll = () => buttons;
    const hero = new Element();
    const image = new Element();
    image.complete = failedHero;
    image.naturalWidth = failedHero ? 0 : 1200;
    image.closest = () => hero;
    const document = {
        addEventListener() {},
        querySelectorAll: selector => ({
            '[data-course-day]': days,
            '[data-course-day-nav]': [nav],
            '[data-course-hero-image]': [image]
        }[selector] || [])
    };
    vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/course-detail.js'), 'utf8'), {document});
    return {days, buttons, nav, hero, image};
}

test('Day 선택은 해당 일정만 보이고 전체 선택은 원래 순서로 모든 일정을 복원한다', () => {
    const {days, buttons, nav} = setup();
    assert.equal(nav.hidden, false);
    assert.deepEqual(days.map(day => day.hidden), [false, false, false]);
    buttons[2].fire('click');
    assert.deepEqual(days.map(day => day.hidden), [true, false, true]);
    assert.deepEqual(buttons.map(button => button.attributes['aria-pressed']), ['false', 'false', 'true', 'false']);
    buttons[0].fire('click');
    assert.deepEqual(days.map(day => day.hidden), [false, false, false]);
    assert.equal(buttons[0].attributes['aria-pressed'], 'true');
});

test('단일 Day에서는 불필요한 필터를 열지 않고 일정은 읽을 수 있다', () => {
    const {nav, days} = setup({dayCount: 1});
    assert.equal(nav.hidden, true);
    assert.equal(days[0].hidden, false);
});

test('대표 이미지 오류와 캐시된 로딩 실패는 빈 hero까지 숨긴다', () => {
    const {hero, image} = setup();
    image.fire('error');
    assert.equal(hero.hidden, true);
    assert.equal(setup({failedHero: true}).hero.hidden, true);
});

test('JavaScript 없이도 Day 전체가 기본 표시되고 필터만 숨겨진다', () => {
    const template = fs.readFileSync(path.resolve(__dirname, '../../main/resources/templates/course/detail.html'), 'utf8');
    assert.match(template, /<nav[^>]*data-course-day-nav[^>]*hidden/s);
    const daySection = template.match(/<section[^>]*data-course-day[^>]*>/s)?.[0];
    assert.ok(daySection);
    assert.doesNotMatch(daySection, /\bhidden\b/);
});
