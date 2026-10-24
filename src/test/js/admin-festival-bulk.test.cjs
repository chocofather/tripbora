const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const file = path.join(__dirname, '../../main/resources/static/js/admin-festival-bulk.js');
const vm = require('node:vm');
const {FakeElement, FakeEvent, flush} = require('./fake-dom.cjs');
test('bulk selection persists by content and year, excludes unknown and enforces 30', () => {
    assert.ok(fs.existsSync(file), 'bulk module must exist');
    const {createSelection} = require(file);
    const selection = createSelection();
    const item = (id, year = 2026, status = 'UNREGISTERED') => ({contentId: String(id), eventStartDate: `${year}-05-01`, registrationStatus: status});
    assert.equal(selection.add(item(1, 2026, 'UNKNOWN')), false);
    assert.equal(selection.add(item(1, 2026, 'REGISTERED')), false);
    assert.equal(selection.add(item(1)), true);
    assert.equal(selection.add(item(1, 2027)), true);
    for (let id = 2; id <= 29; id++) selection.add(item(id));
    assert.equal(selection.items.size, 30);
    assert.equal(selection.add(item(30)), false);
    assert.equal(selection.items.has('1:2027'), true);
    selection.remove(item(1));
    assert.equal(selection.items.has('1:2027'), true);
});
test('UI shares candidates, previews only poster-selected rows and requires final confirmation', async () => {
    const doc = new FakeElement('document');
    doc.createElement = tag => {
        const node = new FakeElement(tag);
        node.getBoundingClientRect = () => ({left: 0, right: 0});
        node.focus = () => {};
        return node;
    };
    doc.getElementById = id => doc.querySelector(`#${id}`);
    const originalQuery = FakeElement.prototype.querySelectorAll;
    const queries = node => { node.querySelectorAll = selector => selector.split(',').flatMap(part => originalQuery.call(node, part.trim())); };
    const form = new FakeElement('form', {id: 'festival-create-form'});
    const controls = new FakeElement('div', {'data-festival-registration-modes': ''});
    for (const mode of ['single', 'bulk']) controls.append(new FakeElement('button', {'data-festival-registration-mode': mode}));
    const root = new FakeElement('section', {'data-festival-bulk': ''});
    for (const attr of ['rows', 'summary', 'status', 'preview', 'confirm', 'start', 'confirmation', 'recover', 'select-page', 'clear', 'confirm-count', 'cancel', 'new', 'poster-sequence']) {
        root.append(new FakeElement(['preview', 'confirm', 'start', 'recover', 'select-page', 'clear', 'cancel'].includes(attr) ? 'button' : 'div', {[`data-bulk-${attr}`]: ''}));
    }
    const dialog = new FakeElement('dialog');
    dialog.showModal = () => dialog.open = true;
    dialog.close = () => { dialog.open = false; dialog.dispatchEvent(new FakeEvent('close')); };
    dialog.append(new FakeElement('h3', {id: 'festival-bulk-poster-title'}));
    for (const attr of ['poster-items', 'poster-status', 'large-image', 'poster-close', 'poster-progress', 'poster-steps', 'poster-content', 'poster-previous', 'poster-next', 'poster-missing']) dialog.append(new FakeElement(attr === 'poster-close' ? 'button' : 'div', {[`data-bulk-${attr}`]: ''}));
    dialog.querySelector('[data-bulk-poster-steps]').getBoundingClientRect = () => ({left: 0, right: 0});
    root.append(dialog); form.append(root); doc.append(controls, form); queries(root);
    root.querySelector('[data-bulk-poster-sequence]').focus = () => {};
    const calls = [];
    const storage = new Map();
    const context = {document: doc, window: {addEventListener() {}}, URL, URLSearchParams, setTimeout, clearTimeout,
        crypto: {randomUUID: () => 'ui-uuid'}, sessionStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
        fetch: async (url, options) => {
            calls.push({url, body: options?.body && JSON.parse(options.body)});
            const payload = url.includes('/images?') ? {items: [{selectionKey: 'poster-1', imageUrl: 'https://example.com/poster.jpg', selectable: true, licenseType: 'KOGL_TYPE_1'}]}
                : url.endsWith('/preview') ? {status: 'READY', token: 'token-1', errors: [], warnings: []}
                    : {status: 'COMPLETE', total: 1, processed: 1, success: 1, failed: 0, duplicate: 0, items: []};
            return {ok: true, json: async () => payload};
        }};
    vm.runInNewContext(fs.readFileSync(file, 'utf8'), context);
    doc.dispatchEvent(new FakeEvent('DOMContentLoaded'));
    controls.querySelector('[data-festival-registration-mode="bulk"]').click();
    assert.equal(context.window.TripBoraFestivalBulk.isBulk(), true);
    const items = [1, 2].map(id => ({contentId: String(id), title: `Festival ${id}`, eventStartDate: '2026-05-01', registrationStatus: 'UNREGISTERED'}));
    const event = new FakeEvent('festival:candidates-rendered'); event.detail = {items}; doc.dispatchEvent(event);
    root.querySelector('[data-bulk-select-page]').click();
    assert.match(root.querySelector('[data-bulk-summary]').textContent, /전체 선택 2/);
    root.querySelector('[data-bulk-rows]').children[0].querySelector('button').click();
    await flush();
    const radio = dialog.querySelector('input'); assert.ok(radio, dialog.querySelector('[data-bulk-poster-status]').textContent); radio.checked = true; radio.dispatchEvent(new FakeEvent('change'));
    dialog.close();
    root.querySelector('[data-bulk-preview]').click(); await flush();
    assert.equal(calls.filter(call => call.url.endsWith('/preview')).length, 1);
    assert.match(root.querySelector('[data-bulk-summary]').textContent, /등록 가능 1건/);
    assert.doesNotMatch(root.querySelector('[data-bulk-rows]').text(), /READY/);
    assert.equal(calls.filter(call => call.url.endsWith('/start')).length, 0);
    root.querySelector('[data-bulk-confirm]').click();
    assert.equal(root.querySelector('[data-bulk-confirmation]').hidden, false);
    root.querySelector('[data-bulk-start]').click(); await flush();
    const start = calls.find(call => call.url.endsWith('/start'));
    assert.deepEqual(start.body, {requestId: 'ui-uuid', previewTokens: ['token-1']});
    assert.match(root.querySelector('[data-bulk-status]').textContent, /서버 처리 1\/1/);
});
test('lost start response recovers with GET only and retains request id on 404', async () => {
    assert.ok(fs.existsSync(file), 'bulk module must exist');
    const {createJobClient} = require(file);
    const calls = [];
    const storage = new Map();
    const session = {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)};
    const client = createJobClient(async (url, options) => {
        calls.push([url, options?.method || 'GET']);
        if (options?.method === 'POST') throw Error('network');
        return {ok: false, status: 404};
    }, session, () => 'request-uuid', {});
    await assert.rejects(client.start(['token']), /결과 확인 필요/);
    assert.equal(client.requestId(), 'request-uuid');
    await assert.rejects(client.start(['token']), /기존 작업/);
    assert.deepEqual(calls.map(call => call[1]), ['POST', 'GET']);
    await assert.rejects(client.recover(), /결과 확인 필요/);
    assert.deepEqual(calls.map(call => call[1]), ['POST', 'GET', 'GET']);
});
test('new work requires an explicitly confirmed COMPLETE result', async () => {
    const {createJobClient} = require(file);
    const storage = new Map([['tripbora.festival.bulk.request', 'previous-id']]);
    const client = createJobClient(async () => ({ok: true, json: async () => ({id: 'previous-id', status: 'COMPLETE'})}), {
        getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)
    }, () => 'new-id', {});
    assert.throws(() => client.beginNew(), /완료/);
    await client.recover();
    client.beginNew();
    assert.equal(client.requestId(), null);
    assert.equal(storage.size, 0);
});
test('only confirmed 404 permits explicit missing-job reset; 5xx and network errors never do', async () => {
    const {createJobClient} = require(file);
    for (const result of ['404', '503', 'network']) {
        const storage = new Map([['tripbora.festival.bulk.request', 'old-id']]);
        const client = createJobClient(async () => {
            if (result === 'network') throw Error('offline');
            return {ok: false, status: Number(result), json: async () => ({message: 'error'})};
        }, {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key)}, () => 'new-id', {});
        assert.equal(client.isMissing(), false);
        await assert.rejects(client.recover());
        assert.equal(client.isMissing(), result === '404');
        assert.throws(() => client.beginNew(), /완료|확인/);
        if (result === '404') {
            client.beginNew(true);
            assert.equal(client.requestId(), null);
        } else {
            assert.throws(() => client.beginNew(true), /완료|확인/);
            assert.equal(client.requestId(), 'old-id');
        }
    }
});
