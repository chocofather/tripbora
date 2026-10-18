const {test} = require('node:test');
const assert = require('node:assert/strict');
const {parse, select, days} = require('../../main/resources/static/js/travel-plan-date-range.js');

test('range supports one-day trips, reverse selection and restarting a completed range', () => {
    assert.deepEqual(select('', '', '2026-12-31'), ['2026-12-31', '']);
    assert.deepEqual(select('2026-12-31', '', '2026-12-31'), ['2026-12-31', '2026-12-31']);
    assert.deepEqual(select('2027-01-02', '', '2026-12-31'), ['2026-12-31', '2027-01-02']);
    assert.deepEqual(select('2026-12-31', '2027-01-02', '2027-02-01'), ['2027-02-01', '']);
});
test('local parsing rejects impossible dates and duration crosses month/year boundaries', () => {
    assert.equal(parse('2026-02-29'), null);
    assert.equal(parse('0999-01-01'), null);
    assert.equal(parse('12026-01-01'), null);
    assert.ok(parse('2028-02-29'));
    assert.equal(days('2026-12-31', '2027-01-02'), 3);
    assert.equal(days('2026-10-18', '2026-10-18'), 1);
    assert.equal(days('2026-01-01', '2026-03-31'), 90);
    assert.equal(days('2026-01-01', '2026-04-01'), 91);
});
