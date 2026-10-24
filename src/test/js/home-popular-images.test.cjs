const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture({width = 1200, height = 800, sourceWidth, sourceHeight, original, broken = false} = {}) {
    const classes = new Set();
    const frame = {clientWidth: 500, clientHeight: 400};
    const listeners = {};
    const image = {
        dataset: {sourceWidth, sourceHeight, ...(original ? {originalSrc: original} : {})},
        naturalWidth: broken ? 0 : width, naturalHeight: broken ? 0 : height,
        complete: true, parentElement: frame, src: '/thumbnail.jpg', srcset: '/thumbnail.jpg 480w',
        classList: {toggle: (name, enabled) => enabled ? classes.add(name) : classes.delete(name)},
        removeAttribute: name => { delete image[name]; },
        addEventListener: (name, fn) => { listeners[name] = fn; }
    };
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/home-popular-images.js', 'utf8'), {
        document: {querySelectorAll: selector => {
            assert.equal(selector, '.popular-recommend .recommend-card-media img');
            return [image];
        }},
        window: {addEventListener: () => {}}
    });
    return {image, classes, listeners};
}

test('failed thumbnail retries original once and a failed original becomes a stable placeholder', () => {
    const {image, classes, listeners} = fixture({original: '/original.jpg', broken: true});
    assert.equal(image.src, '/original.jpg');
    assert.equal(image.dataset.originalSrc, undefined);
    assert.equal(classes.has('is-image-unavailable'), false);
    listeners.error();
    assert.equal(classes.has('is-image-unavailable'), true);
    listeners.error();
    assert.equal(image.src, '/original.jpg');
});

test('original dimensions distinguish small sources from small responsive thumbnail candidates', () => {
    assert.equal(fixture({width: 320, height: 240, sourceWidth: 1600, sourceHeight: 1200})
        .classes.has('is-small-popular'), false);
    assert.equal(fixture({width: 120, height: 90}).classes.has('is-small-popular'), true);
});

test('panorama sizes account for cover height so the browser can select a sharp candidate', () => {
    assert.equal(fixture({sourceWidth: 2400, sourceHeight: 600}).image.sizes, '1600px');
});
