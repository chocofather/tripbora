const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const picker = require('../../main/resources/static/js/travel-plan-emoji-picker.js');
const data = JSON.parse(fs.readFileSync(require.resolve('../../main/resources/static/data/travel-plan-emoji.json'), 'utf8'));

test('Unicode data covers every standard group, has Korean labels and no duplicate emoji', () => {
    assert.equal(picker.CATEGORIES.length, 10);
    assert.ok(data.emoji.length > 1500);
    assert.equal(new Set(data.emoji.map(item => item.emoji)).size, data.emoji.length);
    for (const category of picker.CATEGORIES.filter(item => item.id !== 'recent')) {
        assert.ok(data.emoji.filter(item => item.category === category.id).length >= 20, category.id);
    }
    assert.ok(data.emoji.every(item => item.ko && item.en && item.tags));
    assert.ok(data.emoji.filter(item => /[가-힣]/.test(item.ko)).length > 1900);
    assert.match(data.license, /Permission is hereby granted/);
});

test('search matches Korean, English, multiple keywords and emoji across categories', () => {
    const find = query => picker.search(data.emoji, query).map(item => item.emoji);
    assert.ok(find('비행기').includes('✈️'));
    assert.ok(find('AIRPLANE').includes('✈️'));
    assert.ok(find('대한민국').includes('🇰🇷'));
    assert.ok(find('red heart').includes('❤️'));
    assert.ok(find('커피').includes('☕'));
    assert.deepEqual(find('nonexistent-emoji-thing'), []);
    assert.ok(find('👩‍💻').includes('👩‍💻'));
});

test('recents accept only catalog emoji, deduplicate, cap and tolerate malformed storage', () => {
    const allowed = new Set(data.emoji.map(item => item.emoji));
    assert.deepEqual(picker.cleanRecent('["😀","<script>","😀",{"emoji":"😎"},"🇰🇷"]', allowed), ['😀', '🇰🇷']);
    assert.deepEqual(picker.cleanRecent('{bad', allowed), []);
    assert.deepEqual(picker.cleanRecent('{}', allowed), []);
    assert.equal(picker.cleanRecent(JSON.stringify([...allowed].slice(0, 40)), allowed).length, 24);
});

test('insertion preserves selected text boundaries, native UTF-16 maxlength and full sequences', () => {
    assert.deepEqual(picker.insertAt('앞뒤', 1, 1, '👩‍💻', 2000), {value: '앞👩‍💻뒤', caret: 6});
    assert.deepEqual(picker.insertAt('앞선택뒤', 1, 3, '🇰🇷', 2000), {value: '앞🇰🇷뒤', caret: 5});
    assert.equal(picker.insertAt('a'.repeat(1999), 1999, 1999, '😀', 2000), null);
    assert.equal(picker.insertAt('a'.repeat(1999), 1999, 1999, '👨‍👩‍👧‍👦', 2000), null);
    assert.equal(picker.insertAt('a'.repeat(2000), 1998, 2000, '😀', 2000).value.length, 2000);
});

class Element {
    constructor(tag, doc) {
        this.tagName = tag; this.ownerDocument = doc; this.children = []; this.listeners = {};
        this.style = {}; this.attrs = {}; this.dataset = {}; this.value = ''; this.textContent = '';
        this.hidden = false; this.selectionStart = 0; this.selectionEnd = 0; this.maxLength = 2000;
        this.classList = {add: name => { this.className = `${this.className || ''} ${name}`; }};
    }
    append(...nodes) { nodes.forEach(node => { node.parentNode = this; this.children.push(node); }); }
    replaceChildren(...nodes) { this.children = []; this.append(...nodes); }
    setAttribute(key, value) { this.attrs[key] = String(value); }
    addEventListener(type, listener) { (this.listeners[type] ||= []).push(listener); }
    dispatchEvent(event) { for (const listener of this.listeners[event.type] || []) listener(event); }
    emit(type, properties = {}) {
        const event = {type, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {}, ...properties};
        this.dispatchEvent(event); return event;
    }
    focus() { this.ownerDocument.activeElement = this; }
    setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; }
    getBoundingClientRect() { return {left: 100, top: 550}; }
    find(className) { return this.children.flatMap(child => [...((child.className || '').split(' ').includes(className) ? [child] : []), ...child.find(className)]); }
}
function fixture(options = {}) {
    const saved = {};
    const view = {
        innerWidth: 320, innerHeight: 700, addEventListener() {},
        Event: class { constructor(type, properties) { this.type = type; Object.assign(this, properties); } },
        localStorage: {getItem: key => saved[key], setItem: (key, value) => { saved[key] = value; }}
    };
    const doc = {defaultView: view};
    doc.createElement = tag => new Element(tag, doc);
    const input = doc.createElement('textarea');
    const toggle = doc.createElement('button');
    const panel = doc.createElement('div'); panel.hidden = true;
    let insertCount = 0;
    const api = picker.mount({input, toggle, panel, data: data.emoji, onInsert: () => { insertCount++; }, ...options});
    return {api, input, toggle, panel, saved, doc, view, insertCount: () => insertCount};
}

test('mounted picker keeps selections open, replaces selected draft and persists only Unicode text', async () => {
    const f = fixture();
    f.input.value = '앞선택뒤'; f.input.setSelectionRange(1, 3);
    await f.api.open();
    const first = f.panel.find('travel-plan-chat-emoji-item')[0];
    const emoji = first.textContent;
    assert.equal(first.emit('pointerdown').defaultPrevented, true);
    first.emit('click');
    assert.equal(f.input.value, `앞${emoji}뒤`);
    first.emit('click');
    assert.equal(f.input.value, `앞${emoji}${emoji}뒤`);
    assert.equal(f.panel.hidden, false);
    assert.equal(f.doc.activeElement, f.input);
    assert.equal(f.insertCount(), 2);
    assert.deepEqual(JSON.parse(f.saved['travelPlan.chat.recentEmoji']), [emoji]);
    assert.equal(f.panel.style.width, '296px');
    assert.equal(f.panel.style.left, '12px');
    f.api.close();
    assert.equal(f.panel.hidden, true);
    assert.equal(f.toggle.attrs['aria-expanded'], 'false');
});

test('mounted picker protects IME and maxlength without losing the draft or storing failed selection', async () => {
    const f = fixture();
    await f.api.open();
    const first = f.panel.find('travel-plan-chat-emoji-item')[0];
    f.input.emit('compositionstart');
    first.emit('click');
    assert.equal(f.input.value, '');
    assert.equal(f.saved['travelPlan.chat.recentEmoji'], undefined);
    f.input.emit('compositionend');
    f.input.value = 'a'.repeat(2000); f.input.setSelectionRange(2000, 2000); f.input.emit('select');
    first.emit('click');
    assert.equal(f.input.value, 'a'.repeat(2000));
    assert.match(f.panel.find('tp-emoji-status')[0].textContent, /2,000/);
    assert.equal(f.saved['travelPlan.chat.recentEmoji'], undefined);
});

test('mounted search spans categories and empty recents are explanatory; Enter never submits', async () => {
    const f = fixture();
    await f.api.open();
    f.panel.find('tp-emoji-category').find(button => button.dataset.emojiCategory === 'recent').emit('click');
    assert.equal(f.panel.find('tp-emoji-empty')[0].hidden, false);
    assert.match(f.panel.find('tp-emoji-empty')[0].textContent, /여기에/);
    const search = f.panel.find('tp-emoji-search')[0];
    search.value = 'airplane'; search.emit('input');
    assert.ok(f.panel.find('travel-plan-chat-emoji-item').some(button => button.textContent === '✈️'));
    assert.equal(search.emit('keydown', {key: 'Enter'}).defaultPrevented, true);
    assert.equal(picker.mount({input: f.input, toggle: f.toggle, panel: f.panel}), f.api);
});

test('blocked localStorage preserves selection and toggle close returns focus to the composer', async () => {
    const f = fixture();
    f.view.localStorage = {getItem() { throw new Error('blocked'); }, setItem() { throw new Error('blocked'); }};
    await f.api.open();
    const first = f.panel.find('travel-plan-chat-emoji-item')[0];
    first.emit('click');
    assert.equal(f.input.value, first.textContent);
    f.panel.find('tp-emoji-search')[0].focus();
    f.toggle.emit('click');
    assert.equal(f.panel.hidden, true);
    assert.equal(f.doc.activeElement, f.input);
});
