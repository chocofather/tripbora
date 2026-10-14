const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {FakeElement, FakeEvent, flush} = require('./fake-dom.cjs');

function load(roots = []) {
    const listeners = {};
    const document = {activeElement: null, addEventListener: (name, fn) => { listeners[name] = fn; },
        createElement: tag => new FakeElement(tag), execCommand: () => {}, querySelector: () => null,
        querySelectorAll: () => roots};
    const window = {crypto: require('node:crypto').webcrypto, confirm: () => true};
    const uploads = [];
    const fetch = async url => {
        uploads.push(url);
        return {ok: true, json: async () => ({url: '/uploads/example/content/aaaaaaaa-1111-4222-8333-444444444444.jpg',
            width: 10, height: 20})};
    };
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/admin-structured-editor.js', 'utf8'),
        {document, window, URL, FormData, fetch, CSS: {escape: value => value}});
    return {window, uploads, listeners};
}

function fixture(id, prefix) {
    const form = new FakeElement('form');
    const root = new FakeElement('div');
    root.id = id;
    root.closest = () => form;
    root.contains = node => root.descendants().includes(node);
    const input = new FakeElement('input');
    input.value = JSON.stringify({version: 1, blocks: [{type: 'FULL_IMAGE', id: 'same',
        image: {url: prefix + 'aaaaaaaa-1111-4222-8333-444444444444.jpg', width: 10, height: 20}}]});
    return {root, input, form};
}

test('two editor contexts restore unchanged JSON, isolate state and DOM ids, and init only once', () => {
    const {window} = load();
    assert.equal(typeof window.StructuredEditor?.init, 'function');
    const first = fixture('first', '/uploads/travel-info/content/');
    const second = fixture('second', '/uploads/example/content/');
    const a = window.StructuredEditor.init({...first, imageUploadUrl: '/admin/api/travel-info/content-images',
        imageUrlPrefix: '/uploads/travel-info/content/'});
    const b = window.StructuredEditor.init({...second, imageUploadUrl: '/admin/api/example/content-images',
        imageUrlPrefix: '/uploads/example/content/'});
    assert.equal(a.serialize(), first.input.value);
    assert.equal(b.serialize(), second.input.value);
    a.setActive(false);
    assert.equal(b.isActive(), true);
    const ids = first.root.descendants().map(n => n.getAttribute('id')).filter(Boolean);
    const secondIds = second.root.descendants().map(n => n.getAttribute('id')).filter(Boolean);
    assert.equal(ids.some(id => secondIds.includes(id)), false);
    assert.equal(window.StructuredEditor.init({...first}), a);
    assert.equal(first.form.listeners.submit.length, 1);
    assert.equal(window.TravelInfoStructuredEditor, window.StructuredEditor);
});

test('image upload uses the instance endpoint and accepts only that instance prefix', async () => {
    const {window, uploads} = load();
    assert.equal(typeof window.StructuredEditor?.init, 'function');
    const page = fixture('example', '/uploads/example/content/');
    const editor = window.StructuredEditor.init({...page, imageUploadUrl: '/admin/api/example/content-images',
        imageUrlPrefix: '/uploads/example/content/'});
    const file = page.root.descendants().find(n => n.getAttribute('type') === 'file');
    file.files = [new Blob(['image'], {type: 'image/jpeg'})];
    file.dispatchEvent(new FakeEvent('change'));
    await flush();
    assert.deepEqual(uploads, ['/admin/api/example/content-images']);
    assert.equal(JSON.parse(editor.serialize()).blocks[0].image.url,
        '/uploads/example/content/aaaaaaaa-1111-4222-8333-444444444444.jpg');
    const foreign = fixture('foreign', '/uploads/travel-info/content/');
    const wrong = window.StructuredEditor.init({...foreign, imageUploadUrl: '/admin/api/example/content-images',
        imageUrlPrefix: '/uploads/example/content/'});
    assert.equal(JSON.parse(wrong.serialize()).blocks[0].image, undefined);
});

test('data configuration initializes every root and submits to its own hidden input', () => {
    const first = fixture('first', '/uploads/travel-info/content/');
    const second = fixture('second', '/uploads/example/content/');
    for (const [page, prefix, endpoint] of [[first, '/uploads/travel-info/content/', '/admin/api/travel-info/content-images'],
        [second, '/uploads/example/content/', '/admin/api/example/content-images']]) {
        page.root.dataset.structuredInput = '#content';
        page.root.dataset.imageUploadUrl = endpoint;
        page.root.dataset.imageUrlPrefix = prefix;
        page.form.querySelector = () => page.input;
    }
    const {listeners} = load([first.root, second.root]);
    listeners.DOMContentLoaded();
    first.root.structuredEditor.blocks()[0].caption = '첫 화면';
    first.form.dispatchEvent(new FakeEvent('submit'));
    assert.equal(JSON.parse(first.input.value).blocks[0].caption, '첫 화면');
    assert.equal(JSON.parse(second.input.value).blocks[0].caption, undefined);
    listeners.DOMContentLoaded();
    assert.equal(first.form.listeners.submit.length, 1);
});

test('all image block shapes preserve existing credits and the slider limit stays forty', () => {
    const {window} = load();
    const prefix = '/uploads/example/content/';
    const image = {url: prefix + 'aaaaaaaa-1111-4222-8333-444444444444.jpg', width: 10, height: 20,
        credit: {author: 'Author', source: 'Source', sourceUrl: 'https://example.test', license: 'CC BY 4.0',
            licenseUrl: 'https://creativecommons.org/licenses/by/4.0/'}};
    const items = Array.from({length: 40}, (_, index) => ({id: `item-${index}`, image}));
    const raw = [
        {type: 'FULL_IMAGE', id: 'full', image},
        {type: 'IMAGE_TEXT', id: 'text', imagePosition: 'LEFT', image, text: '본문'},
        {type: 'IMAGE_SLIDER', id: 'slider', items},
        {type: 'IMAGE_GRID', id: 'grid', columns: 2, items: items.slice(0, 2)}
    ];
    const blocks = raw.map(block => window.StructuredEditor.readBlock(block, prefix));
    const roundTrip = blocks.map(window.StructuredEditor.writeBlock);
    assert.deepEqual(JSON.parse(JSON.stringify(roundTrip)), raw);
    assert.equal(window.StructuredEditor.validate(blocks, 0).valid, true);
    blocks[2].items.push({...blocks[2].items[0], id: 'too-many'});
    assert.equal(window.StructuredEditor.validate(blocks, 0).valid, false);
});

test('an upload from a different namespace keeps the previous image and reports failure', async () => {
    const {window, uploads} = load();
    const page = fixture('travel', '/uploads/travel-info/content/');
    const editor = window.StructuredEditor.init({...page, imageUploadUrl: '/admin/api/travel-info/content-images',
        imageUrlPrefix: '/uploads/travel-info/content/'});
    const before = editor.serialize();
    const file = page.root.descendants().find(n => n.getAttribute('type') === 'file');
    file.files = [new Blob(['image'], {type: 'image/jpeg'})];
    file.dispatchEvent(new FakeEvent('change'));
    await flush();
    assert.deepEqual(uploads, ['/admin/api/travel-info/content-images']);
    assert.equal(editor.serialize(), before);
    assert.match(page.root.text(), /이미지를 올리지 못했습니다/);
});

test('unsafe settings are refused before mounting or sending CSRF credentials', () => {
    const {window} = load();
    const page = fixture('unsafe', '/uploads/example/content/');
    for (const imageUploadUrl of ['https://evil.test/upload', '//evil.test/upload', '/\\evil.test/upload']) {
        assert.throws(() => window.StructuredEditor.init({...page, imageUploadUrl, imageUrlPrefix: '/uploads/example/content/'}));
    }
    assert.throws(() => window.StructuredEditor.init({...page, imageUploadUrl: '/admin/api/example/content-images',
        imageUrlPrefix: '/uploads/'}));
    assert.equal(page.form.listeners.submit, undefined);
});
