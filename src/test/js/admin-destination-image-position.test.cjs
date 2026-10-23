const test = require('node:test');
const assert = require('node:assert/strict');
const {coverOverflow, draggedPosition} = require('../../main/resources/static/js/admin-destination-image-position.js');

test('cover overflow reflects portrait, landscape, square and small source pixels', () => {
    const portrait = coverOverflow(300, 400, 400, 300);
    assert.equal(portrait.x, 0);
    assert.ok(Math.abs(portrait.y - 233.3333333333) < 1e-6);
    assert.deepEqual(coverOverflow(800, 300, 400, 300), {x: 400, y: 0});
    assert.deepEqual(coverOverflow(100, 100, 400, 300), {x: 0, y: 100});
    assert.deepEqual(coverOverflow(40, 30, 400, 300), {x: 0, y: 0});
    assert.deepEqual(coverOverflow(0, 0, 400, 300), {x: 0, y: 0});
});

test('drag follows the grabbed photo, clamps edges and locks uncut axes', () => {
    assert.equal(draggedPosition(50, 20, 100), 30); // move photo right/down => smaller position
    assert.equal(draggedPosition(50, -20, 100), 70);
    assert.equal(draggedPosition(50, 999, 100), 0);
    assert.equal(draggedPosition(50, -999, 100), 100);
    assert.equal(draggedPosition(20, 100, 0), 50);
    assert.equal(draggedPosition(20, 100, 0.001), 50);
});
