const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture(fetch) {
    const window = {location: {href: 'http://localhost/destinations/15'}};
    const context = vm.createContext({window, document: {}, URL, AbortController, fetch, $: () => ({ready() {}})});
    vm.runInContext(fs.readFileSync('src/main/resources/static/js/detail-carousel.js', 'utf8'), context);
    return window.TripBoraGallery.GalleryWindow;
}
const item = id => ({galleryKey: 'admin:' + id, imageUrl: '/photo/' + id});
const response = body => ({ok: true, headers: new Headers({'content-type': 'application/json'}), json: async () => body});
function page(start, count = 10000) {
    const end = Math.min(start + 30, count);
    return {items: Array.from({length: Math.max(0, end - start)}, (_, index) => item(start + index)),
        nextCursor: end < count ? 'n:' + end : null, previousCursor: start > 0 ? 'p:' + start : null};
}

test('10,000 photos keep only three pages and reload evicted previous ranges with cursor', async () => {
    let calls = 0;
    const Window = fixture(async url => {
        calls++;
        const cursor = url.searchParams.get('cursor');
        const [direction, position] = cursor.split(':');
        return response(page(Number(position) - (direction === 'p' ? 30 : 0)));
    });
    const state = new Window('/destinations/15/gallery', page(0));
    while (state.cursor('next')) {
        await state.load();
        assert.ok(state.pages.length <= 3);
        assert.ok(state.items.length <= 90);
    }
    assert.equal(state.items.at(-1).galleryKey, 'admin:9999');
    state.selectedKey = state.items[0].galleryKey;
    const before = state.current.galleryKey;
    await state.move(-1);
    assert.notEqual(state.current.galleryKey, before);
    assert.equal(state.current.galleryKey, 'admin:9929');
    assert.ok(state.pages.length <= 3);
    assert.ok(state.items.length <= 90);
    assert.equal(calls, 334);
    // Only live page boundaries exist; there is no accumulating cursor/image history.
    assert.equal(Object.keys(state).some(key => /history|visited|cache/i.test(key)), false);
});

test('known start/end stop without wrapping or requests', async () => {
    const Window = fixture(() => { throw Error('unexpected fetch'); });
    const state = new Window('/gallery', page(0, 2));
    assert.equal(await state.move(-1), false);
    await state.move(1);
    assert.equal(state.current.galleryKey, 'admin:1');
    assert.equal(await state.move(1), false);
    assert.equal(state.current.galleryKey, 'admin:1');
});

test('duplicate requests share one fetch; failed page preserves cursor and retries', async () => {
    let release, calls = 0;
    const Window = fixture(async () => {
        calls++;
        if (calls === 1) { await new Promise(resolve => { release = resolve; }); throw Error('offline'); }
        return response(page(30, 60));
    });
    const state = new Window('/gallery', page(0, 60));
    const first = state.load(), second = state.load();
    await Promise.resolve(); release(); await Promise.all([first, second]);
    assert.equal(calls, 1); assert.equal(state.error, true); assert.equal(state.cursor('next'), 'n:30');
    await state.load(); assert.equal(calls, 2); assert.equal(state.error, false); assert.equal(state.items.length, 60);
});

test('filter cancels stale response, sends filter and never restores obsolete photos', async () => {
    const pending = [];
    const Window = fixture((url, options) => new Promise(resolve => pending.push({url, options, resolve})));
    const state = new Window('/gallery', null);
    const old = state.reset('ADMIN'); await Promise.resolve();
    const current = state.reset('TRAVELER'); await Promise.resolve();
    assert.equal(pending[0].options.signal.aborted, true);
    assert.equal(pending[1].url.searchParams.get('filter'), 'TRAVELER');
    pending[1].resolve(response({items: [{...item(99), galleryKey: 'review:99'}]})); await current;
    pending[0].resolve(response(page(0))); await old;
    assert.equal(state.items.length, 1); assert.equal(state.current.galleryKey, 'review:99');
});

test('empty candidate pages advance manually once and repeated server cursor terminates safely', async () => {
    let calls = 0;
    const Window = fixture(async () => {
        calls++;
        return response({items: [], nextCursor: calls === 1 ? 'empty-2' : 'empty-2', previousCursor: null});
    });
    const state = new Window('/gallery', {items: [], nextCursor: 'empty-1'});
    await state.move(1);
    assert.equal(calls, 1); assert.equal(state.cursor('next'), 'empty-2');
    await state.move(1);
    assert.equal(calls, 2); assert.equal(state.cursor('next'), null);
    assert.equal(await state.load(), false); assert.equal(calls, 2);
});

test('live duplicate keys are deduplicated inside the bounded window', async () => {
    const Window = fixture(async () => response({items: [item(29), item(30)], nextCursor: null, previousCursor: 'p:29'}));
    const state = new Window('/gallery', page(0, 31));
    await state.load(); assert.equal(state.items.length, 31);
    assert.equal(new Set(state.items.map(value => value.galleryKey)).size, 31);
});
