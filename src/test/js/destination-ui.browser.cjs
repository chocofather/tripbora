// Run after DestinationListRenderingTest and HomeControllerTest export real Thymeleaf HTML.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve('build/reports/destination-ui');
const staticRoot = path.resolve('src/main/resources/static');
const read = file => fs.readFileSync(path.join(staticRoot, file), 'utf8');
const escape = text => text.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
const svg = (w, h) => 'data:image/svg+xml;base64,' + Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="#94b8bc"/><circle cx="${w/2}" cy="${h/3}" r="${w/8}" fill="#345c6c"/></svg>`).toString('base64');
const cases = [];
for (const width of [360, 390, 393, 430, 768, 1440]) {
    for (const file of fs.readdirSync(root).filter(f => /^list-.*\.html$/.test(f))) cases.push({width, file, home: false});
    cases.push({width, file: 'home.html', home: true});
}
const frames = cases.map(({width, file, home}, id) => {
    let html = fs.readFileSync(path.join(root, file), 'utf8')
        .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
        .replace(/<link\b[^>]*>/gi, tag => {
            const url = tag.match(/href="(\/css\/[^"?]+)/)?.[1];
            if (!url) return '';
            const file = url.replace(/-[a-f\d]{32}(?=\.css)/, '').slice(1);
            return '<style>' + read(file).replace(/url\([^)]*\)/g, 'none') + '</style>';
        })
        .replace(/<img\b[^>]*>/gi, tag => tag.replace(/\s(?:src|srcset|sizes|loading|onerror|data-original-src)="[^"]*"/gi, '')
            .replace('<img', `<img src="${svg(80,80)}"`));
    const setup = home ? `
        const images = [...document.querySelectorAll('.home-converge-photo img')];
        const extra = images[0].closest('li').cloneNode(true);
        images[0].closest('ul').append(extra); images.push(extra.querySelector('img'));
        const dimensions = [[1200,800],[800,1200],[60,40],[1200,800]];
        images.forEach((image,i) => {
            image.src = ${JSON.stringify([svg(1200,800),svg(800,1200),svg(60,40),svg(1200,800)])}[i];
            image.dataset.sourceWidth = dimensions[i][0]; image.dataset.sourceHeight = dimensions[i][1];
            image.classList.toggle('is-no-derivatives', i === 3);
            image.style.setProperty('--card-position-x','25%'); image.style.setProperty('--card-position-y','15%');
        });` : '';
    const measure = `
        const errors=[]; window.addEventListener('error',e=>errors.push(e.message));
        ${setup}
        window.addEventListener('load',()=>setTimeout(()=>{
            try {
                const nav = [...document.querySelectorAll('.pagination')].find(n=>getComputedStyle(n).display!=='none');
                const items=nav ? [...nav.children] : [];
                const images=[...document.querySelectorAll('.home-converge-photo img')];
                const cornersVisible = image => {
                    const box=image.getBoundingClientRect(), frame=image.parentElement.getBoundingClientRect();
                    const scale=Math.min(1,box.width/image.naturalWidth,box.height/image.naturalHeight);
                    const width=image.naturalWidth*scale, height=image.naturalHeight*scale;
                    const left=box.left-frame.left+(box.width-width)/2;
                    const top=box.top-frame.top+(box.height-height)/2, radius=frame.width/2;
                    const inset=top>=radius ? 0 : radius-Math.sqrt(radius*radius-(radius-top)**2);
                    return left>=inset-0.5 && left+width<=frame.width-inset+0.5;
                };
                parent.postMessage({destinationUI:true,id:${id},width:${width},file:${JSON.stringify(file)},
                    overflow:document.documentElement.scrollWidth-innerWidth,
                    navOverflow:nav ? nav.scrollWidth-nav.clientWidth : 0,
                    rows:new Set(items.map(e=>Math.round(e.getBoundingClientRect().top))).size,
                    numbers:nav?.querySelectorAll('.page-number').length,
                    images:images.map(i=>({fit:getComputedStyle(i).objectFit,position:getComputedStyle(i).objectPosition,
                        background:getComputedStyle(i).backgroundColor,frameBackground:getComputedStyle(i.parentElement).backgroundColor,
                        small:i.classList.contains('is-small-landmark'),
                        cornersVisible:cornersVisible(i),
                        ratio:i.parentElement.clientWidth/i.parentElement.clientHeight})),errors},'*');
            } catch(e){parent.postMessage({destinationUI:true,id:${id},error:String(e)},'*');}
        },100));`;
    html = html.replace('</body>', `<script>${home ? read('js/card-thumbnail.js') : ''}</script><script>${measure}</script></body>`);
    return `<iframe style="width:${width}px;height:950px;border:0" srcdoc="${escape(html)}"></iframe>`;
}).join('\n');
const collector = `const results=[];addEventListener('message',e=>{if(!e.data?.destinationUI)return;results.push(e.data);if(results.length===${cases.length}){const p=document.createElement('pre');p.id='results';p.textContent=JSON.stringify(results);document.body.prepend(p);}});`;
const fixture = path.join(root, 'browser-checks.html');
fs.writeFileSync(fixture, `<!doctype html><meta charset="utf-8"><script>${collector}</script>${frames}`);
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const output = execFileSync(chrome, ['--headless=new','--disable-gpu','--disable-background-networking',
    '--disable-renderer-backgrounding','--no-first-run','--no-default-browser-check','--allow-file-access-from-files',
    `--user-data-dir=${path.join(root,'chrome-profile')}`,'--virtual-time-budget=8000','--dump-dom',pathToFileURL(fixture).href],
    {encoding:'utf8',timeout:60000,maxBuffer:64*1024*1024,stdio:['ignore','pipe','pipe']});
const match = output.match(/<pre id="results">([\s\S]*?)<\/pre>/);
assert.ok(match, 'Browser measurements did not finish');
const results = JSON.parse(match[1].replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));
fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify(results,null,2));
for (const result of results) {
    const key = `${result.file}/${result.width}`;
    assert.ok(!result.error,`${key}: ${result.error}`);
    assert.deepEqual(result.errors,[],key);
    assert.ok(result.overflow<=1,`${key}: document overflow ${result.overflow}`);
    if (!result.file.startsWith('home')) {
        assert.equal(result.rows,1,`${key}: pagination rows`);
        assert.ok(result.navOverflow<=1,`${key}: pagination overflow ${result.navOverflow}`);
        assert.ok(result.numbers <= (result.width<=600 ? 3 : 7),key);
    } else {
        assert.deepEqual(result.images.map(i=>i.fit),['cover','cover','scale-down','scale-down'],key);
        assert.equal(result.images[0].position,'25% 15%',key);
        assert.ok(result.images[2].small,key);
        assert.equal(result.images[3].frameBackground,'rgb(255, 255, 255)',key);
        assert.ok(result.images.slice(2).every(i=>i.cornersVisible),`${key}: fallback photo corners clipped by arch`);
        assert.ok(result.images.every(i=>Math.abs(i.ratio-0.75)<0.01),key);
    }
}
console.log(JSON.stringify({fixtures:results.length,maxOverflow:Math.max(...results.map(r=>r.overflow)),report:path.join(root,'browser-results.json')}));
