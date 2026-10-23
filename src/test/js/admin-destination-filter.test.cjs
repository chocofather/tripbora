const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { runInNewContext } = require('node:vm');
const { resolve } = require('node:path');

const script = readFileSync(resolve(__dirname, '../../main/resources/static/js/admin-destination-filter.js'), 'utf8');

// 실제 스크립트의 이벤트와 폼 제출 값을 검증한다. 서버와 DB는 사용하지 않는다.
function page(visibility, implicit) {
    function element(value = '') {
        const listeners = {};
        return {
            value, disabled: false, dataset: {},
            addEventListener(type, listener) { listeners[type] = listener; },
            fire(type, event = {}) { listeners[type]?.(event); }
        };
    }
    const visibilitySelect = element(visibility);
    visibilitySelect.dataset.implicitVisibility = String(implicit);
    const dataStatus = element('missing_image');
    const form = element();
    const submissions = [];
    form.querySelectorAll = () => [];
    form.submit = () => submissions.push({
        visibility: visibilitySelect.disabled ? undefined : visibilitySelect.value,
        dataStatus: dataStatus.value
    });
    const elements = {
        'admin-destination-filter-form': form,
        'destination-data-status-filter': dataStatus,
        'destination-visibility-filter': visibilitySelect
    };
    runInNewContext(script, {
        document: {
            addEventListener(type, listener) { if (type === 'DOMContentLoaded') listener(); },
            getElementById(id) { return elements[id] ?? null; }
        },
        sessionStorage: { getItem() { return null; }, removeItem() {} }
    });
    return { form, visibilitySelect, dataStatus, submissions };
}

test('default data status submission omits implicit all and leaves control usable on back navigation', () => {
    const p = page('all', true);
    p.dataStatus.fire('change');
    assert.deepEqual(p.submissions, [{ visibility: undefined, dataStatus: 'missing_image' }]);
    assert.equal(p.visibilitySelect.disabled, false);
});

test('native scope-tab form submission also omits implicit all', () => {
    const p = page('all', true);
    let prevented = false;
    p.form.fire('submit', { preventDefault() { prevented = true; } });
    if (!prevented) p.form.submit();
    assert.equal(p.submissions[0].visibility, undefined);
    assert.equal(p.visibilitySelect.disabled, false);
});

test('explicit all remains all after changing data status', () => {
    const p = page('all', true);
    p.visibilitySelect.fire('change');
    p.dataStatus.fire('change');
    assert.deepEqual(p.submissions.map(s => s.visibility), ['all', 'all']);
});

test('restored public hidden and explicit all states survive subsequent form changes', () => {
    for (const visibility of ['public', 'hidden', 'all']) {
        const p = page(visibility, false);
        p.dataStatus.fire('change');
        assert.equal(p.submissions[0].visibility, visibility);
    }
});
