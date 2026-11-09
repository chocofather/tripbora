const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture({present = true, canWithdraw = true, confirm = true} = {}) {
    const node = () => ({hidden: false, disabled: false, textContent: '',
        addEventListener(type, listener) { this[type] = listener; }});
    const button = node(), status = node(), error = node(), deleteButton = node();
    deleteButton.dataset = {commentId: '15', imageId: '24'};
    const scope = {dataset: {policyId: '17', policyVersion: 'legacy-v1'},
        querySelector(selector) {
            return {'[data-legacy-gallery-withdraw]': canWithdraw ? button : null,
                '[data-legacy-gallery-status]': status, '[data-legacy-gallery-error]': error}[selector];
        }, setAttribute(name, value) { this[name] = value; }};
    let response = {ok: true, status: 200, redirected: false, type: 'application/json',
        body: {decided: true, agreed: false, version: 'legacy-v1', decisionAction: 'WITHDRAW'}};
    let deferred;
    const calls = [], reloads = [], confirms = [], alerts = [];
    const context = vm.createContext({Headers,
        document: {
            querySelectorAll(selector) { return selector === '[data-review-photo-delete]' ? [deleteButton] : present ? [scope] : []; },
            querySelector(selector) {
                if (selector === '[data-legacy-gallery-withdrawal]') return present ? scope : null;
                if (selector === 'meta[name="_csrf"]') return {content: 'test-csrf'};
                if (selector === 'meta[name="_csrf_header"]') return {content: 'X-CSRF-TOKEN'};
                return null;
            }
        },
        confirm(message) { confirms.push(message); return confirm; },
        alert(message) { alerts.push(message); },
        window: {location: {reload() { reloads.push(true); }}},
        fetch: async (url, options) => {
            calls.push({url, options});
            if (deferred) await deferred;
            if (response.failure) throw new Error('network');
            return {...response, headers: new Headers({'Content-Type': response.type}), json: async () => response.body};
        }
    });
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/review-image-management.js', 'utf8'), context);
    return {button, status, error, scope, deleteButton, calls, reloads, confirms, alerts,
        click: () => button.click?.(), setResponse(value) { response = {...response, ...value}; },
        pause() { let release; deferred = new Promise(resolve => { release = resolve; }); return release; }};
}

test('legacy withdrawal uses its rendered original policy, JSON and CSRF, then refreshes photo badges', async () => {
    const f = fixture();
    assert.equal(f.calls.length, 0);
    await f.click();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].url, '/mypage/gallery-consent');
    assert.equal(f.calls[0].options.method, 'PUT');
    assert.deepEqual(JSON.parse(f.calls[0].options.body), {action: 'WITHDRAW', policyVersionId: 17, version: 'legacy-v1'});
    const headers = new Headers(f.calls[0].options.headers);
    assert.equal(headers.get('Accept'), 'application/json');
    assert.equal(headers.get('Content-Type'), 'application/json');
    assert.equal(headers.get('X-CSRF-TOKEN'), 'test-csrf');
    assert.equal(f.calls[0].options.credentials, 'same-origin');
    assert.equal(f.reloads.length, 1);
    assert.match(f.status.textContent, /철회.*완료/);
    assert.equal(f.button.hidden, true);
});

test('cancel and pages without a withdrawable legacy agreement never request a decision', async () => {
    for (const options of [{confirm: false}, {present: false}, {canWithdraw: false}]) {
        const f = fixture(options); await f.click();
        assert.equal(f.calls.length, 0);
        assert.equal(f.reloads.length, 0);
    }
});

test('rapid repeated clicks send one withdrawal while busy and never send another after success', async () => {
    const f = fixture(), release = f.pause();
    const pending = f.click();
    assert.equal(f.button.disabled, true);
    await f.click();
    assert.equal(f.calls.length, 1);
    assert.equal(f.confirms.length, 1);
    release(); await pending;
    await f.click();
    assert.equal(f.calls.length, 1);
});

test('conflict, authorization, redirect and network errors remain visible without reporting success', async () => {
    for (const response of [{ok: false, status: 409}, {ok: false, status: 401}, {ok: false, status: 403},
        {redirected: true, type: 'text/html'}, {type: 'text/html'}, {failure: true}]) {
        const f = fixture(); f.setResponse(response); await f.click();
        assert.equal(f.reloads.length, 0, JSON.stringify(response));
        assert.equal(f.button.disabled, false);
        assert.equal(f.button.hidden, false);
        assert.equal(f.error.hidden, false);
        assert.ok(f.error.textContent.length > 0);
        assert.doesNotMatch(f.status.textContent, /완료되었습니다/);
    }
});

test('a failed withdrawal can be explicitly retried and clears the previous error', async () => {
    const f = fixture(); f.setResponse({ok: false, status: 409}); await f.click();
    f.setResponse({ok: true, status: 200}); await f.click();
    assert.equal(f.calls.length, 2);
    assert.equal(f.error.hidden, true);
    assert.equal(f.error.textContent, '');
    assert.equal(f.reloads.length, 1);
});

test('an unexpected successful response cannot falsely report completed withdrawal', async () => {
    for (const body of [{agreed: true}, {}, null]) {
        const f = fixture(); f.setResponse({body}); await f.click();
        assert.equal(f.reloads.length, 0);
        assert.equal(f.error.hidden, false);
        assert.equal(f.button.disabled, false);
    }
});

test('an already withdrawn response remains successful when the latest action differs', async () => {
    const f = fixture(); f.setResponse({body: {agreed: false, decisionAction: 'DECLINE', status: 'WITHDRAWN'}});
    await f.click();
    assert.equal(f.reloads.length, 1);
    assert.equal(f.button.hidden, true);
});

test('existing photo deletion retains its confirmation, URL and reload behavior', async () => {
    const f = fixture(); await f.deleteButton.click();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].url, '/comments/15/images/24');
    assert.equal(f.calls[0].options.method, 'DELETE');
    assert.equal(f.reloads.length, 1);
});
