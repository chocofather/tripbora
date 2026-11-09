const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function hooks() {
    const code = fs.readFileSync('src/main/resources/static/js/comment/events.js', 'utf8')
        .replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g, '')
        .replaceAll('export function ', 'function ');
    const context = vm.createContext({detailMessage: key => key});
    vm.runInContext(code + '\nglobalThis.ratingHooks = {ratingMarkup};', context);
    return context.ratingHooks;
}

test('rated review editor has five native radio choices and cannot clear its rating', () => {
    const html = hooks().ratingMarkup(4, true);
    assert.equal((html.match(/type="radio"/g) || []).length, 5);
    assert.match(html, /value="4" checked/);
    assert.match(html, /required/);
    assert.doesNotMatch(html, /value=""/);
});

test('legacy editor keeps an explicit unscored choice until the author chooses a rating', () => {
    const html = hooks().ratingMarkup(null, false);
    assert.match(html, /value="" checked/);
    assert.doesNotMatch(html, /required/);
    assert.equal((html.match(/name="rating"/g) || []).length, 6);
});
