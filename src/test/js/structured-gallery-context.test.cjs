const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {FakeElement, FakeEvent} = require('./fake-dom.cjs');

test('shared block renderer images open and close without a travel-info wrapper', () => {
    const content = new FakeElement('div');
    const image = new FakeElement('img', {'data-structured-gallery-image': '', alt: '본문 사진'});
    image.src = '/uploads/example/content/aaaaaaaa-1111-4222-8333-444444444444.jpg';
    image.closest = selector => selector === '.structured-content' ? content
        : selector === '[data-structured-gallery-image]' ? image : null;
    image.focus = () => {};
    const modal = new FakeElement('dialog');
    const parts = new Map();
    modal.querySelector = selector => {
        if (!parts.has(selector)) {
            const part = new FakeElement('div');
            part.focus = () => {};
            parts.set(selector, part);
        }
        return parts.get(selector);
    };
    const document = {readyState: 'complete', body: {style: {overflow: ''}},
        querySelector: () => modal,
        querySelectorAll: selector => selector === '.structured-content [data-structured-gallery-image]' ? [image] : [],
        addEventListener: () => {}, removeEventListener: () => {}};
    const window = {matchMedia: () => ({matches: false})};
    vm.runInNewContext(fs.readFileSync('src/main/resources/static/js/structured-gallery.js', 'utf8'), {document, window});
    assert.equal(modal.dataset.galleryReady, 'true');
    assert.equal(image.getAttribute('tabindex'), '0');
    const click = new FakeEvent('click');
    click.target = image;
    content.dispatchEvent(click);
    assert.equal(modal.classList.contains('is-open'), true);
    assert.equal(parts.get('[data-gallery-current]').src, image.src);
    assert.equal(document.body.style.overflow, 'hidden');
    const closeClick = new FakeEvent('click');
    closeClick.stopPropagation = () => {};
    parts.get('[data-gallery-close]').dispatchEvent(closeClick);
    assert.equal(modal.classList.contains('is-open'), false);
    assert.equal(document.body.style.overflow, '');
});
