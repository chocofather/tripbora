const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const {FakeElement, FakeEvent, flush} = require('./fake-dom.cjs');

class Element extends FakeElement {
    matches(selector) {
        if (selector.startsWith('.')) return this.classList.contains(selector.slice(1));
        if (selector === 'input[type="file"][name="images"]') return this.tagName === 'INPUT' && this.name === 'images';
        if (selector === 'img[data-object-url]') return this.tagName === 'IMG' && this.dataset.objectUrl;
        return super.matches(selector);
    }
    get innerHTML() { return ''; }
    set innerHTML(value) { if (!value) this.replaceChildren(); }
    closest(selector) { return this.matches(selector) ? this : this.parent?.closest(selector); }
    querySelector(selector) {
        if (selector.startsWith(':scope > ')) return this.children.find(child => child.matches(selector.slice(9))) ?? null;
        return super.querySelector(selector);
    }
    reportValidity() { return true; }
}

function fixture(existing = []) {
    const revoked = [], calls = [];
    let objectId = 0;
    const document = new Element('document');
    document.createElement = tag => new Element(tag);
    const context = vm.createContext({document, console, setTimeout, alert() {}, URL: {
        createObjectURL() { return `blob:${++objectId}`; }, revokeObjectURL(url) { revoked.push(url); }
    }, detailMessage: key => key, window: {},
        // License loading/binding has its own real-helper suite; isolate this editor's draft lifecycle.
        initPhotoUploadTerms: async () => true,
        appendPhotoUploadPolicy() {},
        refreshPhotoUploadTerms: async () => true,
        updateCommentWithImages: async (id, data) => calls.push({id, data}),
        FormData: class extends Map {
            constructor(form) { super(); this.append('content', form.querySelector('textarea').value); }
            append(key, value) { this.set(key, [...(this.get(key) || []), value]); }
            getAll(key) { return this.get(key) || []; }
        }
    });
    const code = fs.readFileSync('src/main/resources/static/js/comment/events.js', 'utf8')
        .replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g, '').replaceAll('export function ', 'function ');
    vm.runInContext(code + '\nglobalThis.editorHooks={initCommentImagePreview,handleSaveEdit,handleCancelEdit};', context);
    const form = new Element('form'); form.className = 'edit-comment-form';
    const input = new Element('input', {type: 'file', name: 'images'}); input.files = [];
    const preview = new Element('div'); preview.className = 'comment-image-preview';
    const textarea = new Element('textarea', {name: 'content'}); textarea.value = 'updated review';
    const save = new Element('button'); save.className = 'save-edit-btn';
    const feedback = new Element('p'); feedback.className = 'comment-edit-feedback';
    form.append(input, preview, textarea, save, feedback);
    const editor = context.editorHooks.initCommentImagePreview(form, existing);
    const select = names => {
        input.files = names.map(name => ({name, size: 100, lastModified: 1}));
        input.dispatchEvent(new FakeEvent('change'));
    };
    return {context, document, form, input, preview, textarea, save, feedback, editor, select, revoked, calls};
}

test('photo selector accumulates files and enforces five photos across repeated selections', () => {
    const f = fixture(); f.select(['a.png', 'b.png']); f.select(['c.png', 'd.png', 'e.png', 'sixth.png']);
    assert.equal(f.preview.children.length, 5);
    const data = new f.context.FormData(f.form); f.editor.append(data);
    assert.equal(data.getAll('images').length, 5);
    f.preview.children[0].querySelector('button').click();
    assert.equal(f.preview.children.length, 4);
    assert.ok(f.revoked.length > 0, 'replaced object URLs released');
});

test('existing photo removal is local until the single multipart save request', async () => {
    const f = fixture([{id: 11, imageUrl: '/existing.jpg'}, {id: 12, imageUrl: '/keep.jpg'}]);
    f.preview.children[0].querySelector('button').click(); f.select(['new1.png', 'new2.png', 'new3.png', 'new4.png', 'extra.png']);
    assert.equal(f.preview.children.length, 5);
    assert.equal(f.calls.length, 0);
    const item = new Element('li'); item.append(f.form);
    let reloads = 0;
    f.context.editorHooks.handleSaveEdit({target: f.save}, item, 9, () => reloads++);
    await flush();
    assert.equal(f.calls.length, 1);
    assert.equal(f.calls[0].id, 9);
    assert.deepEqual(Array.from(f.calls[0].data.getAll('removeImageIds')), [11]);
    assert.equal(f.calls[0].data.getAll('images').length, 4);
    assert.equal(f.calls[0].data.getAll('content')[0], 'updated review');
    assert.equal(reloads, 1);
});

test('cancel discards the draft, releases URLs, and preserves the existing reply subtree', () => {
    const f = fixture([{id: 11, imageUrl: '/existing.jpg'}]); f.select(['new.png']);
    const item = new Element('li'); item.classList.add('editing');
    const body = new Element('div'); body.className = 'content-comment-body';
    const content = new Element('p'); content.className = 'comment-content'; content.hidden = true;
    const photos = new Element('div'); photos.className = 'comment-images'; photos.hidden = true;
    const reply = new Element('ul'); reply.className = 'reply-list';
    const cancel = new Element('button'); cancel.className = 'cancel-edit-btn';
    f.form.append(cancel); body.append(content, f.form, photos); item.append(body, reply);
    f.context.editorHooks.handleCancelEdit({target: cancel}, item, 9);
    assert.equal(f.calls.length, 0);
    assert.equal(content.hidden, false);
    assert.equal(photos.hidden, false);
    assert.equal(item.children.includes(reply), true);
    assert.equal(item.classList.contains('editing'), false);
    assert.ok(f.revoked.length > 0);
});

test('failed edit preserves its draft photos and re-enables save', async () => {
    const f = fixture(); f.select(['new.png']);
    f.context.updateCommentWithImages = async () => { throw Error('validation failed'); };
    const item = new Element('li'); item.append(f.form);
    f.context.editorHooks.handleSaveEdit({target: f.save}, item, 9, () => assert.fail('must not reload'), () => {});
    await flush();
    assert.equal(f.preview.children.length, 1);
    assert.equal(f.textarea.value, 'updated review');
    assert.equal(f.feedback.textContent, 'validation failed');
    assert.equal(f.save.disabled, false);
});

test('photo save never asks for consent and disables cancel while the request is pending', async () => {
    const f = fixture(); f.select(['new.png']);
    let release;
    f.context.window.tripboraGalleryConsent = {ensureDecision() { assert.fail('comment save must never request consent'); }};
    f.context.updateCommentWithImages = (id, data) => {
        f.calls.push({id, data});
        return new Promise(resolve => { release = resolve; });
    };
    const cancel = new Element('button'); cancel.className = 'cancel-edit-btn'; f.form.append(cancel);
    const item = new Element('li'); item.append(f.form);
    f.context.editorHooks.handleSaveEdit({target: f.save}, item, 9, () => {}, () => {});
    assert.equal(cancel.disabled, true);
    await flush();
    f.context.editorHooks.handleCancelEdit({target: cancel}, item, 9);
    assert.equal(item.children.includes(f.form), true);
    release();
    await flush();
    assert.equal(f.calls.length, 1);
});
