// Uses HomeControllerTest's real Thymeleaf markup, with offline image success/error fixtures.
const fs = require('node:fs');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {execFileSync} = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve('build/reports/home-popular-images');
const read = file => fs.readFileSync(path.resolve('src/main/resources/static', file), 'utf8');
const widths = [360, 390, 393, 430, 768, 1440];
const fixture = fs.readFileSync(path.join(root, 'fixture.html'), 'utf8')
    .replace(/<link\b[^>]*href=['"]\/([^'"]+)['"][^>]*>/g, (_, file) => '<style>' + read(file).replace(/url\([^)]*\)/g, 'none') + '</style>')
    .replace(/<script\b[^>]*src=['"]\/([^'"]+)['"][^>]*><\/script>/g, '')
    .replace('</body>', '<script>' + read('js/card-thumbnail.js') + '</script><script>' + read('js/home-popular-images.js') + '</script></body>')
    .replaceAll('/missing-thumbnail.jpg', 'data:image/png;base64,invalid')
    .replaceAll('/missing-original.jpg', 'data:image/png;base64,invalid');
const encode = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
const frames = widths.map(width => {
    const measure = `<script>window.addEventListener('load',()=>setTimeout(()=>{
        const frames=[...document.querySelectorAll('.popular-recommend .recommend-card-media')];
        parent.postMessage({popularImages:true,width:${width},overflow:document.documentElement.scrollWidth-innerWidth,
            images:frames.map(frame=>{const img=frame.querySelector('img');return {width:frame.clientWidth,height:frame.clientHeight,
                placeholder:!!frame.querySelector('.recommend-image-placeholder'),fit:img?getComputedStyle(img).objectFit:null,
                position:img?getComputedStyle(img).objectPosition:null,visible:img?getComputedStyle(img).visibility:null,
                loaded:img?img.naturalWidth>0:false,broken:img?img.classList.contains('is-image-unavailable'):false};})},'*');
    },100));</script>`;
    return `<iframe style="width:${width}px;height:1000px;border:0" srcdoc="${encode(fixture.replace('</body>', measure + '</body>'))}"></iframe>`;
}).join('');
const html = path.join(root,'browser.html');
fs.writeFileSync(html, `<!doctype html><meta charset="utf-8"><script>const results=[];addEventListener('message',e=>{if(!e.data?.popularImages)return;results.push(e.data);if(results.length===6){const pre=document.createElement('pre');pre.id='results';pre.textContent=JSON.stringify(results);document.body.append(pre);}});</script>${frames}`);
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const output = execFileSync(chrome, ['--headless=new','--disable-gpu','--disable-background-networking','--disable-renderer-backgrounding',
    '--no-first-run','--no-default-browser-check','--allow-file-access-from-files',`--user-data-dir=${path.join(root,'chrome-profile')}`,
    '--virtual-time-budget=5000','--dump-dom',pathToFileURL(html).href], {encoding:'utf8',timeout:60000,maxBuffer:16*1024*1024,windowsHide:true,stdio:['ignore','pipe','pipe']});
const match = output.match(/<pre id="results">([^<]+)<\/pre>/); assert.ok(match,'all popular fixtures finished');
const results = JSON.parse(match[1].replaceAll('&amp;','&'));
for(const result of results){
    assert.ok(result.overflow<=1,`${result.width}: document overflow`);
    assert.equal(result.images.length,5);
    assert.ok(result.images.every(image=>image.width>0&&image.height>0&&image.placeholder));
    assert.equal(result.images[0].fit,'cover'); assert.equal(result.images[0].position,'20% 80%');
    assert.equal(result.images[0].loaded,true,'thumbnail failure falls back once to the original');
    assert.equal(result.images[1].fit,'contain','no-derivatives photo remains uncropped');
    assert.equal(result.images[2].fit,'scale-down','small original is never enlarged');
    assert.equal(result.images[3].fit,null,'missing URL retains placeholder frame');
    assert.equal(result.images[4].visible,'hidden','final failure never exposes broken icon');
    assert.equal(result.images[4].broken,true);
}
fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify(results,null,2));
console.log(`Popular image layout/fallback verified at ${widths.join(', ')}px.`);
