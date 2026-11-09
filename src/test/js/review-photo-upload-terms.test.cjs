const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const currentPolicy = {available: true, policyVersionId: 25, version: '2026-11-v1',
    title: '리뷰 사진 업로드 이용조건', contentHtml: '<p>서비스 내 비독점적 이용</p>',
    locale: 'ko', policyUrl: '/review-photo-terms'};

function fixture() {
    const nodes = Object.fromEntries(['version', 'status', 'retry', 'link'].map(name => [name, {
        textContent: '', hidden: false,
        addEventListener(type, fn) { this[type] = fn; }
    }]));
    const surface = {querySelector(selector) { return nodes[selector.match(/data-photo-upload-(\w+)/)[1]]; }};
    const form = {querySelector(selector) { return selector === '[data-photo-upload-terms]' ? surface : null; }};
    let nextPolicy = {...currentPolicy};
    let response = {ok: true, redirected: false, type: 'application/json'};
    const calls = [];
    const context = vm.createContext({
        fetch: async (url, options) => {
            calls.push({url, options});
            if (response.failure) throw new Error('network failure');
            return {...response, headers: new Headers({'Content-Type': response.type}), json: async () => nextPolicy};
        }, Headers
    });
    const source = fs.readFileSync('src/main/resources/static/js/comment/uploadTerms.js', 'utf8')
        .replaceAll('export function ', 'function ');
    vm.runInContext(source + '\nglobalThis.hooks = {initPhotoUploadTerms, refreshPhotoUploadTerms, appendPhotoUploadPolicy, uploadTermsMarkup};', context);
    return {form, nodes, calls, hooks: context.hooks,
        setPolicy(policy) { nextPolicy = policy; },
        setResponse(value) { response = {...response, ...value}; }};
}

function dataFor(...files) {
    const values = new Map([['images', files]]);
    return {delete(name) { values.delete(name); }, getAll(name) { return values.get(name) || []; },
        append(name, value) { values.set(name, [...(values.get(name) || []), value]); }};
}

test('upload conditions fetch current public policy without recording an account choice', async () => {
    const f = fixture();
    assert.equal(await f.hooks.initPhotoUploadTerms(f.form), true);
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].url, '/review-photo-terms');
    assert.equal(f.calls[0].options.headers.Accept, 'application/json');
    assert.equal(f.calls[0].options.cache, 'no-store');
    assert.equal(f.calls[0].options.method, undefined);
    assert.match(f.nodes.version.textContent, /2026-11-v1/);
    assert.equal(f.nodes.link.href, '/review-photo-terms?versionId=25');
    const data = dataFor({size: 123, name: 'new.png'});
    f.hooks.appendPhotoUploadPolicy(f.form, data);
    assert.deepEqual(data.getAll('photoUploadPolicyVersionId'), ['25']);
});

test('text reviews, existing photo retention and removal need no new upload evidence', () => {
    const f = fixture();
    for (const data of [dataFor(), dataFor({size: 0, name: ''})]) {
        data.append('photoUploadPolicyVersionId', 'old');
        data.append('removeImageIds', '42');
        f.hooks.appendPhotoUploadPolicy(f.form, data);
        assert.deepEqual(data.getAll('photoUploadPolicyVersionId'), []);
        assert.deepEqual(data.getAll('removeImageIds'), ['42']);
    }
});

test('loading and failed policy lookup block only new file submission', async () => {
    const f = fixture();
    const loading = f.hooks.initPhotoUploadTerms(f.form);
    assert.throws(() => f.hooks.appendPhotoUploadPolicy(f.form, dataFor({size: 1})), /잠시 후/);
    await loading;
    f.setResponse({failure: true});
    assert.equal(await f.hooks.refreshPhotoUploadTerms(f.form), false);
    assert.match(f.nodes.status.textContent, /사진 없이/);
    assert.equal(f.nodes.retry.hidden, false);
    assert.throws(() => f.hooks.appendPhotoUploadPolicy(f.form, dataFor({size: 1})), /확인하지 못/);
    assert.doesNotThrow(() => f.hooks.appendPhotoUploadPolicy(f.form, dataFor()));
});

test('invalid/unavailable policy and HTML login redirects never grant upload evidence', async () => {
    for (const policy of [{...currentPolicy, available: false}, {...currentPolicy, policyVersionId: null},
        {...currentPolicy, version: ''}, {...currentPolicy, contentHtml: ''}]) {
        const f = fixture(); f.setPolicy(policy);
        assert.equal(await f.hooks.initPhotoUploadTerms(f.form), false);
        assert.throws(() => f.hooks.appendPhotoUploadPolicy(f.form, dataFor({size: 1})));
    }
    for (const response of [{redirected: true}, {type: 'text/html'}, {ok: false}]) {
        const f = fixture(); f.setResponse(response);
        assert.equal(await f.hooks.initPhotoUploadTerms(f.form), false);
    }
});

test('409 refresh displays new version and waits for the next explicit submission', async () => {
    const f = fixture(); await f.hooks.initPhotoUploadTerms(f.form);
    f.setPolicy({...currentPolicy, policyVersionId: 26, version: '2026-11-v2'});
    await f.hooks.refreshPhotoUploadTerms(f.form);
    assert.equal(f.calls.length, 2);
    assert.match(f.nodes.version.textContent, /2026-11-v2/);
    assert.match(f.nodes.status.textContent, /다시 눌러/);
    const data = dataFor({size: 1});
    assert.deepEqual(data.getAll('photoUploadPolicyVersionId'), []);
    f.hooks.appendPhotoUploadPolicy(f.form, data);
    assert.deepEqual(data.getAll('photoUploadPolicyVersionId'), ['26']);
});

test('visible clauses and policy link are present for static and dynamically created forms', () => {
    const f = fixture();
    const template = fs.readFileSync('src/main/resources/templates/destination/detail.html', 'utf8');
    for (const markup of [f.hooks.uploadTermsMarkup(), template]) {
        for (const text of ['사진의 권리는 회원님', '비독점적', '리뷰가 공개되는 동안', '승인된 사진만',
            '외부 광고', '제3자 제공', '별도 상업적 재사용', '텍스트 리뷰', '/review-photo-terms', 'data-photo-upload-version']) {
            assert.ok(markup.includes(text), text);
        }
    }
    assert.doesNotMatch(f.hooks.uploadTermsMarkup(), /checkbox|checked|WITHDRAW|DECLINE/);
});

test('login and account surfaces have no retired photo consent prompt or settings', () => {
    for (const name of ['layout/main.html', 'mypage/review-images.html', 'register.html', 'social-signup.html']) {
        const source = fs.readFileSync('src/main/resources/templates/' + name, 'utf8');
        assert.doesNotMatch(source, /gallery-consent|data-consent-action|별도의 선택 동의|활용 약관 확인·갱신/);
    }
    assert.ok(fs.existsSync('src/main/resources/static/js/review-image-management.js'));
    assert.equal(fs.existsSync('src/main/resources/static/js/gallery-consent.js'), false);
});
