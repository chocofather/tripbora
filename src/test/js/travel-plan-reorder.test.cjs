const {test} = require('node:test');
const assert = require('node:assert/strict');
const {persistMove} = require('../../main/resources/static/js/travel-plan-reorder.js');

test('arbitrary same-Day drop reuses adjacent moves with each authoritative response', async () => {
    const calls = [];
    const result = await persistMove({ids: ['a', 'b', 'c'], version: 4}, 'a', 2, async (state, direction) => {
        calls.push([direction, state.version]);
        return {ids: calls.length === 1 ? ['b', 'a', 'c'] : ['b', 'c', 'a'], version: state.version + 1};
    });
    assert.deepEqual(calls, [['down', 4], ['down', 5]]);
    assert.deepEqual(result.ids, ['b', 'c', 'a']);
});

test('stale/conflicting response stops further moves instead of overwriting concurrent order', async () => {
    let calls = 0;
    await assert.rejects(persistMove({ids: ['a', 'b', 'c']}, 'c', 0, async () => {
        calls++;
        return {ids: ['b', 'a', 'c']};
    }), /concurrently/);
    assert.equal(calls, 1);
});
