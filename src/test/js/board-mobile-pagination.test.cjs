const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function setup(search, mobile = true) {
    const requests = [], history = [], listeners = {};
    const media = {matches: mobile, addEventListener: (name, fn) => listeners.resize = fn};
    const fragment = {innerHTML: '', querySelector: () => ({dataset: {pageSize: mobile ? '8' : '10'}})};
    const context = vm.createContext({
        URLSearchParams, console, alert: assert.fail,
        window: {location: {search, pathname: '/board/list'}, matchMedia: () => media,
            history: {pushState: (_, __, url) => history.push(url), replaceState: (_, __, url) => history.push(url)},
            addEventListener: (name, fn) => listeners[name] = fn},
        document: {querySelectorAll: () => [], querySelector: () => null,
            getElementById: id => id === 'board-fragment-container' ? fragment : null,
            addEventListener: (name, fn) => listeners[name] = fn},
        fetch: async url => {requests.push(url); return {ok: true, text: async () => '<div>response</div>'};}
    });
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/board-ajax.js', 'utf8'), context);
    return {context, requests, history, listeners, media};
}

test('mobile requests eight rows and preserves post filters, sort and page in the URL', async () => {
    const s = setup('?boardType=post&postType=question&size=10');
    await vm.runInContext("loadBoardList(2, 'bookmarks')", s.context);
    const params = new URLSearchParams(s.requests[0].split('?')[1]);
    assert.equal(params.get('size'), '8');
    assert.equal(params.get('page'), '2');
    assert.equal(params.get('sort'), 'bookmarks');
    assert.equal(params.get('boardType'), 'post');
    assert.equal(params.get('postType'), 'question');
    assert.equal(s.history[0].split('?')[1], params.toString());
});

test('desktop keeps the existing default size', async () => {
    const s = setup('', false);
    await vm.runInContext("loadBoardList(1, 'latest')", s.context);
    assert.equal(new URLSearchParams(s.requests[0].split('?')[1]).get('size'), '10');
});

test('desktop normalizes a shared mobile URL back to the original size', async () => {
    const s = setup('?boardType=post&postType=tip&size=8', false);
    await vm.runInContext("loadBoardList(1, 'latest')", s.context);
    assert.equal(new URLSearchParams(s.requests[0].split('?')[1]).get('size'), '10');
});

test('a slow previous request cannot overwrite a newer sort', async () => {
    const s = setup('');
    vm.runInContext('var pending = []; fetch = url => new Promise(resolve => pending.push(resolve));', s.context);
    const first = vm.runInContext("loadBoardList(1, 'oldest')", s.context);
    const second = vm.runInContext("loadBoardList(1, 'views')", s.context);
    vm.runInContext("pending[1]({ok: true, text: async () => 'new'})", s.context);
    await second;
    vm.runInContext("pending[0]({ok: true, text: async () => 'old'})", s.context);
    await first;
    assert.equal(s.history.length, 1);
    assert.match(s.history[0], /sort=views/);
});

test('initial mobile load replaces history and keeps the requested page', async () => {
    const s = setup('?boardType=post&postType=tip&page=2&sort=comments');
    await vm.runInContext('syncBoardViewportSize()', s.context);
    const params = new URLSearchParams(s.requests[0].split('?')[1]);
    assert.equal(params.get('size'), '8');
    assert.equal(params.get('page'), '2');
    assert.equal(params.get('postType'), 'tip');
    assert.equal(s.history.length, 1);
});

test('switching to desktop resets page and restores ten rows', async () => {
    const s = setup('?boardType=post&postType=tip&page=2&size=8');
    s.media.matches = false;
    await vm.runInContext('syncBoardViewportSize(true)', s.context);
    const params = new URLSearchParams(s.requests[0].split('?')[1]);
    assert.equal(params.get('size'), '10');
    assert.equal(params.get('page'), '1');
    assert.equal(params.get('postType'), 'tip');
});

test('back navigation normalizes mobile size and updates its URL', async () => {
    const s = setup('?boardType=post&postType=question&page=2&sort=views&size=10');
    await s.listeners.popstate();
    await new Promise(resolve => setImmediate(resolve));
    assert.match(s.requests[0], /size=8/);
    assert.match(s.history[0], /size=8/);
});
