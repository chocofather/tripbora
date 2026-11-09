const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

function fixture() {
    const nodes = Object.fromEntries(['review-summary', 'review-average', 'review-average-stars', 'review-rating-count'].map(id => [id, {
        dataset: {}, style: {}, textContent: '', classList: {toggle() {}}, setAttribute(key, value) { this[key] = value; }
    }]));
    const rows = [5,4,3,2,1].map(score => ({dataset:{ratingScore:String(score)}, meter:{}, count:{},
        querySelector(selector) { return selector === 'meter' ? this.meter : this.count; }}));
    nodes['review-summary'].querySelectorAll = () => rows;
    const source = fs.readFileSync('src/main/resources/static/js/comment/render.js', 'utf8')
        .replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g, '').replaceAll('export function ', 'function ');
    const context = vm.createContext({document:{documentElement:{lang:'ko'},getElementById:id=>nodes[id]},
        detailMessage:(key,count)=>key==='reviewNoRating'?'아직 평가 없음':`별점 평가 ${count}개`});
    vm.runInContext(source+'\nglobalThis.draw = renderReviewStatistics;',context);
    return {nodes,rows,draw:context.draw};
}
test('summary rounds to one decimal and uses rating count as distribution denominator', () => {
    const f=fixture();
    f.draw({reviewCount:23,ratingCount:20,averageRating:4.35,distribution:[0,0,1,11,8]});
    assert.equal(f.nodes['review-average'].textContent,'4.4');
    assert.equal(f.nodes['review-rating-count'].textContent,'별점 평가 20개');
    assert.deepEqual(f.rows.map(r=>r.meter.value),[8,11,1,0,0]);
    assert.ok(f.rows.every(r=>r.meter.max===20));
    assert.equal(f.nodes['review-summary']['aria-busy'],'false');
});
test('unrated legacy reviews do not become zero-star ratings and hide/restore refreshes the summary', () => {
    const f=fixture();
    f.draw({reviewCount:4,ratingCount:2,averageRating:4,distribution:[0,0,1,0,1]});
    assert.equal(f.nodes['review-average'].textContent,'4.0');
    f.draw({reviewCount:2,ratingCount:0,averageRating:null,distribution:[0,0,0,0,0]});
    assert.equal(f.nodes['review-average'].textContent,'아직 평가 없음');
    assert.equal(f.nodes['review-average-stars'].style.width,'0%');
    assert.ok(f.rows.every(r=>r.meter.max===1&&r.meter.value===0));
    f.draw({reviewCount:4,ratingCount:2,averageRating:4,distribution:[0,0,1,0,1]});
    assert.equal(f.nodes['review-average'].textContent,'4.0');
});
