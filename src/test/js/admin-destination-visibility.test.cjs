const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync('src/main/resources/static/js/admin-destination-visibility.js', 'utf8');

function setup(publicVisible = true, csrf = true, confirmed = true) {
    const events = {};
    const button = {
        dataset: {publicVisible: String(publicVisible), visibilityUrl: '/admin/destinations/17/visibility'},
        disabled: false, textContent: publicVisible ? '숨기기' : '공개하기',
        addEventListener: (type, handler) => { events[type] = handler; },
        setAttribute() {}, removeAttribute() {}
    };
    const alert = {hidden: true, textContent: ''};
    const requests = [];
    let resolve;
    let reloads = 0;
    let confirmations = 0;
    vm.runInNewContext(source, {
        document: {
            addEventListener: (type, handler) => handler(),
            querySelectorAll: () => [button],
            querySelector: selector => selector.includes('error') ? alert
                : csrf ? {content: selector.includes('_csrf_header') ? 'X-CSRF-TOKEN' : 'test-token'} : null
        },
        window: {location: {reload() { reloads++; }}, addEventListener() {},
            confirm() { confirmations++; return confirmed; }},
        fetch: (url, options) => {
            requests.push({url, options});
            return new Promise(done => { resolve = done; });
        }
    });
    return {button, alert, requests, click: events.click,
        respond: response => resolve(response), reloads: () => reloads, confirmations: () => confirmations};
}

for (const initial of [true, false]) {
    test(`saved ${initial} visibility changes only after successful POST, with CSRF and no duplicate request`, async () => {
        const ui = setup(initial);
        const pending = ui.click();
        await ui.click();
        assert.equal(ui.requests.length, 1);
        assert.equal(ui.confirmations(), initial ? 1 : 0);
        assert.equal(ui.button.disabled, true);
        assert.equal(ui.button.dataset.publicVisible, String(initial));
        assert.equal(ui.button.textContent, initial ? '숨기기' : '공개하기');
        const {url, options} = ui.requests[0];
        assert.equal(url, '/admin/destinations/17/visibility');
        assert.equal(options.method, 'POST');
        assert.equal(options.credentials, 'same-origin');
        assert.equal(options.headers['X-CSRF-TOKEN'], 'test-token');
        assert.deepEqual(JSON.parse(options.body), {publicVisible: !initial});
        ui.respond({ok: true, json: async () => ({publicVisible: !initial})});
        await pending;
        assert.equal(ui.reloads(), 1);
        assert.equal(ui.alert.hidden, true);
    });
}

test('cancelling hide confirmation leaves the saved state and performs no POST', async () => {
    const ui = setup(true, true, false);
    const pending = ui.click();
    assert.equal(ui.confirmations(), 1);
    assert.equal(ui.requests.length, 0);
    assert.equal(ui.button.disabled, false);
    assert.equal(ui.button.dataset.publicVisible, 'true');
    assert.equal(ui.reloads(), 0);
    assert.equal(ui.alert.hidden, true);
    await pending;
});

for (const response of [
    {ok: false},
    {ok: true, redirected: true},
    {ok: true, json: async () => { throw new Error('invalid JSON'); }},
    {ok: true, json: async () => ({publicVisible: true})}
]) {
    test('failed or invalid response retains saved state and permits retry with Korean error', async () => {
        const ui = setup();
        const pending = ui.click();
        ui.respond(response);
        await pending;
        assert.equal(ui.reloads(), 0);
        assert.equal(ui.button.disabled, false);
        assert.equal(ui.button.dataset.publicVisible, 'true');
        assert.equal(ui.button.textContent, '숨기기');
        assert.equal(ui.alert.hidden, false);
        assert.match(ui.alert.textContent, /공개 상태를 변경하지 못했습니다/);
    });
}

test('missing CSRF prevents POST and leaves saved state unchanged', async () => {
    const ui = setup(true, false);
    await ui.click();
    assert.equal(ui.requests.length, 0);
    assert.equal(ui.button.disabled, false);
    assert.equal(ui.reloads(), 0);
    assert.equal(ui.alert.hidden, false);
});
