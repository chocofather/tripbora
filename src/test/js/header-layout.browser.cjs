// Optional real-engine check. Export fixtures with TRIPBORA_HEADER_FIXTURE_DIR
// while running HeaderProfileMenuTest, then pass that directory to this script.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const {pathToFileURL} = require('node:url');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');

const fixtureDirectory = process.argv[2];
assert.ok(fixtureDirectory, 'Pass the exported HeaderProfileMenuTest fixture directory');
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const staticRoot = path.resolve('src/main/resources/static');
const read = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');
const css = files => files.map(read).join('\n').replace(/url\((['"]?)\/([^)'"\s]*)\1\)/g,
    (_, quote, asset) => `url("${pathToFileURL(path.join(staticRoot, asset)).href}")`);
const escapeAttribute = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
const contexts = [
    ['home', ['css/home.css', 'css/home-converge-prototype.css', 'css/home-mobile.css', 'css/home-festival.css', 'css/slider.css', 'css/home-promotion.css']],
    ['domestic', ['css/destination.css']],
    ['overseas', ['css/destination.css']],
    ['courses', ['css/course-list.css']],
    ['community', ['css/board-list.css', 'css/board-write-modal.css']],
    ['travel-info', ['css/travel-info.css']],
    ['events', ['css/event.css']]
];
const cases = [];
for (const language of ['ko', 'en', 'ja', 'zh-CN', 'zh-TW']) {
    const rendered = fs.readFileSync(path.join(fixtureDirectory, `header-${language}.html`), 'utf8');
    const header = rendered.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '').match(/<header\b[\s\S]*?<\/header>/i)[0];
    for (const width of [1280, 1440]) {
        for (const [context, styles] of contexts) cases.push({language, width, context, styles, header});
    }
    for (const width of [360, 390, 393, 430, 768]) cases.push({language, width, context: 'domestic', styles: ['css/destination.css'], header});
}
const resultsScript = `
const results = [];
window.addEventListener('message', event => {
    if (!event.data?.headerLayout) return;
    results.push(event.data);
    if (results.length === ${cases.length}) {
        const output = document.createElement('pre'); output.id = 'header-results';
        output.textContent = JSON.stringify(results); document.body.append(output);
    }
});`;
const frames = cases.map(({language, width, context, styles, header}, id) => {
    const pageCss = styles;
    const marker = ['domestic', 'overseas'].includes(context) ?
        '<div id="region-fragment-container"><div class="region-selector"><div class="region-buttons"><button class="circle-btn">Region</button></div></div></div>' : '';
    const measure = `
window.addEventListener('load', () => document.fonts.ready.then(() => setTimeout(() => {
    try {
        const main = document.querySelector('main');
        const mainBefore = main.getBoundingClientRect().top;
        const parents = [...document.querySelectorAll('.main-menu > .menu-item')];
        const desktop = innerWidth >= 1200;
        const initiallyClosed = getComputedStyle(document.querySelector('.global-submenu')).display === 'none';
        const groups = parents.filter(p => p.querySelector('.submenu-col'));
        const initiallyHidden = !desktop || groups.every(p => getComputedStyle(p.querySelector('.submenu-col')).visibility === 'hidden');
        const regionTop = document.querySelector('.circle-btn')?.getBoundingClientRect().top;
        const headerBottom = document.querySelector('.main-nav').getBoundingClientRect().bottom;
        if (desktop) parents[3].querySelector('a').focus();
        else document.querySelector('#site-menu-toggle').click();
        const pairs = desktop ? groups.map(p => {
            p.querySelector('a').focus();
            const onlyCurrentVisible = groups.every(other => getComputedStyle(other.querySelector('.submenu-col')).visibility === (other === p ? 'visible' : 'hidden'));
            const parent = p.querySelector('a').getBoundingClientRect();
            const textRange = document.createRange(); textRange.selectNodeContents(p.querySelector('a'));
            const parentText = textRange.getBoundingClientRect();
            const group = p.querySelector('.submenu-col').getBoundingClientRect();
            const firstLink = p.querySelector('.submenu-col a').getBoundingClientRect();
            return {onlyCurrentVisible, label:p.querySelector('a').textContent.trim(), delta: Math.abs(parent.x + parent.width/2 - group.x - group.width/2), top: group.top, bottom:group.bottom, parentBottom:parent.bottom,
                boxGap:group.top-parent.bottom, parentTextBottom:parentText.bottom, firstLinkTop:firstLink.top, visualGap:firstLink.top-parentText.bottom,
                bridgeOwned:document.elementFromPoint(parent.x+parent.width/2, (parent.bottom+group.top)/2)?.closest('.menu-item')===p,
                width:group.width,parentWidth:parent.width,
                visible:getComputedStyle(p.querySelector('.submenu-col')).visibility};
        }) : [];
        const backdrop = document.querySelector('.global-submenu').getBoundingClientRect();
        if (desktop) parents[0].querySelector('a').focus();
        const closesOnDirectLink = !desktop || (getComputedStyle(document.querySelector('.global-submenu')).display === 'none' && groups.every(p => getComputedStyle(p.querySelector('.submenu-col')).visibility === 'hidden'));
        parent.postMessage({initiallyHidden, closesOnDirectLink, headerLayout:true,id:${id}, language:${JSON.stringify(language)},context:${JSON.stringify(context)},width:innerWidth,
            overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
            offenders:[...document.querySelectorAll('body *')].filter(el=>{
                const r=el.getBoundingClientRect(); return r.right>document.documentElement.clientWidth+1 && r.width>0 && getComputedStyle(el).visibility==='visible';
            }).slice(0,8).map(el=>({tag:el.tagName,id:el.id,cls:el.className,right:el.getBoundingClientRect().right})),
            mainShift:main.getBoundingClientRect().top - mainBefore,pairs,
            initiallyClosed,regionTop,headerBottom,
            actionGap:document.querySelector('.search-box > #search-toggle').getBoundingClientRect().left - parents.at(-1).querySelector('a').getBoundingClientRect().right,
            primaryGaps:parents.slice(1).map((p,i)=>p.getBoundingClientRect().left-parents[i].getBoundingClientRect().right),
            logoGap:parents[0].getBoundingClientRect().left-document.querySelector('.logo').getBoundingClientRect().right,
            menuWidth:document.querySelector('.main-menu').getBoundingClientRect().width,
            firstParent:parents[0].querySelector('a').getBoundingClientRect().left,
            backdropBottom:backdrop.bottom,
            backdropTop:backdrop.top,
            mobileOpen: !document.querySelector('#site-menu').hidden}, '*');
    } catch(error) { parent.postMessage({headerLayout:true,id:${id},error:String(error)}, '*'); }
}, 50)));`;
    const html = `<!doctype html><html lang="${language}"><head><meta charset="UTF-8"><style>${css(['css/style.css', ...pageCss])}</style></head><body>${header}<main>${marker}<div style="height:1000px"></div></main><script>${read('js/main.js')}</script><script>${read('js/menu.js')}</script><script>${measure}</script></body></html>`;
    return `<iframe style="width:${width}px;height:900px;border:0" srcdoc="${escapeAttribute(html)}"></iframe>`;
}).join('\n');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-header-geometry-'));
const filename = path.join(directory, 'checks.html');
fs.writeFileSync(filename, `<!doctype html><meta charset="UTF-8"><script>${resultsScript}</script>${frames}`);
const output = execFileSync(chrome, [
    '--headless=new', '--disable-gpu', '--disable-background-networking', '--disable-renderer-backgrounding',
    '--no-first-run', '--no-default-browser-check', '--allow-file-access-from-files',
    `--user-data-dir=${path.join(directory, 'profile')}`, '--virtual-time-budget=5000', '--dump-dom', pathToFileURL(filename).href
], {encoding:'utf8', maxBuffer:32*1024*1024, timeout:60000, stdio:['ignore','pipe','pipe']});
const match = output.match(/<pre id="header-results">([\s\S]*?)<\/pre>/);
assert.ok(match, `Browser did not finish all ${cases.length} fixtures: ${filename}`);
const results = JSON.parse(match[1].replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
fs.writeFileSync(path.join(directory, 'results.json'), JSON.stringify(results, null, 2));
console.log(`Browser measurements: ${path.join(directory, 'results.json')}`);
for (const result of results) {
    const key = `${result.language}/${result.context}/${result.width}`;
    assert.ok(!result.error, `${key}: ${result.error}`);
    assert.ok(result.overflow <= 1, `${key}: horizontal overflow ${result.overflow}`);
    assert.equal(result.mainShift, 0, `${key}: main content moved`);
    assert.ok(result.initiallyClosed, `${key}: submenu unexpectedly starts open`);
    assert.ok(result.initiallyHidden, `${key}: submenu links unexpectedly start visible`);
    assert.ok(result.closesOnDirectLink, `${key}: submenu stays open after leaving its parent`);
    if (result.regionTop != null) assert.ok(result.regionTop >= result.headerBottom, `${key}: region menu overlaps the closed header`);
    if (result.width >= 1200) {
        const expectedGap = result.width < 1600 && ['en', 'ja'].includes(result.language) ? 40 : 60;
        for (const gap of result.primaryGaps) assert.equal(gap, expectedGap, `${key}: primary gap ${gap}px`);
        assert.ok(result.logoGap >= 0, `${key}: logo overlap ${-result.logoGap}px`);
        assert.ok(result.actionGap >= 0, `${key}: top-level menu overlaps actions by ${-result.actionGap}px`);
        assert.equal(result.pairs.length, 5, key);
        assert.ok(Math.abs(Math.min(...result.pairs.map(pair => pair.top)) - result.backdropTop) <= 1, `${key}: backdrop top`);
        for (const pair of result.pairs) {
            assert.ok(pair.onlyCurrentVisible, `${key}/${pair.label}: another submenu is visible`);
            assert.ok(pair.delta <= 1, `${key}/${pair.label}: center delta ${pair.delta}`);
            assert.equal(pair.visible, 'visible', key);
            assert.ok(pair.boxGap >= 16 && pair.boxGap <= 18, `${key}: parent-to-submenu gap ${pair.boxGap}px`);
            assert.ok(pair.visualGap >= 16 && pair.visualGap <= 18.5, `${key}: visible text gap ${pair.visualGap}px`);
            assert.ok(pair.bridgeOwned, `${key}: pointer cannot cross the vertical gap`);
            assert.ok(pair.bottom <= result.backdropBottom + 1, `${key}: backdrop height`);
        }
    } else assert.ok(result.mobileOpen, `${key}: mobile drawer did not open`);
}
const pairs = results.flatMap(result => result.pairs);
console.log(JSON.stringify({fixtures:results.length, pairs:pairs.length,
    maxCenterDelta:Math.max(...pairs.map(pair => pair.delta)), maxOverflow:Math.max(...results.map(result => result.overflow)),
    artifact:filename}));
