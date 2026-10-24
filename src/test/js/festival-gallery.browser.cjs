// Real Chrome against rendered Thymeleaf; no app, database or external image requests.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const read = file => fs.readFileSync(file, 'utf8');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-festival-gallery-'));
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const rendered = read('build/reports/festival-gallery/detail.html');
const body = rendered.match(/<article class="festival-detail"[\s\S]*?<\/article>/)[0]
    + rendered.match(/<dialog[^>]*id="festival-image-modal"[\s\S]*?<\/dialog>/)[0];
const css = read(process.env.TRIPBORA_FESTIVAL_CSS || 'src/main/resources/static/css/festival-detail.css');
const script = read('src/main/resources/static/js/festival-gallery.js');
const bookmarkScript = read('src/main/resources/static/js/travel-info-bookmark.js');
const widths = [360, 390, 393, 430, 768, 1024, 1280, 1440];
const image = (w, h, color) => 'data:image/svg+xml;base64,' + Buffer.from(
    `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}"><rect width="100%" height="100%" fill="${color}"/><rect x="5" y="5" width="${w-10}" height="${h-10}" fill="none" stroke="white" stroke-width="10"/><text x="50%" y="50%" fill="white" text-anchor="middle" font-size="50">${w} × ${h}</text></svg>`).toString('base64');
const sources = [image(1200, 600, '#416963'), image(600, 1200, '#805b8e'), image(800, 800, '#9d713c')];
const fixture = width => `<!doctype html><meta charset="utf-8"><meta name="_csrf" content="fixture-csrf"><meta name="_csrf_header" content="X-CSRF-TOKEN"><style>body{margin:0}*{box-sizing:border-box}${css}</style>${body}
<script>const sources=${JSON.stringify(sources)};document.querySelectorAll('[data-festival-gallery-slide] img').forEach((img,i)=>img.src=sources[i]);const isLoggedIn=true;const bookmarkRequests=[];window.fetch=async(url,options)=>{bookmarkRequests.push({url,options});return {ok:true,status:200}};</script>
<script>${script}</script><script>${bookmarkScript}</script><script>
addEventListener('load',async()=>{try{
 const check=(value,message)=>{if(!value)throw Error(message)};
 const gallery=document.querySelector('[data-festival-gallery]');
 const slides=[...gallery.querySelectorAll('[data-festival-gallery-slide]')];
 const next=gallery.querySelector('[data-festival-gallery-next]');
 const frame=gallery.getBoundingClientRect();
 const heading=document.querySelector('.festival-detail-heading').getBoundingClientRect();
 const modal=document.querySelector('#festival-image-modal');
 for(let i=0;i<slides.length;i++){
  const img=slides[i].querySelector('img'),style=getComputedStyle(img);
  check(style.objectFit===(i===1?'contain':'cover'),'license fit '+i);
  check(style.objectPosition==='50% 50%','centered image');
  check(Math.abs(img.clientHeight-frame.height)<2,'image fills frame height');
  check(Math.abs(gallery.getBoundingClientRect().height-frame.height)<1,'stable frame');
  if(i===1)check(getComputedStyle(slides[i]).backgroundColor==='rgb(255, 255, 255)','protected white background');
  slides[i].querySelector('button').click();
  check(modal.open,'zoom opens');
  const large=modal.querySelector('img');check(large.src===img.src,'same original source');
  const ratio=large.getBoundingClientRect();
  check(Math.abs(ratio.width/ratio.height-img.naturalWidth/img.naturalHeight)<0.02,'zoom original ratio');
  document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true}));
  check(modal.querySelector('img').src===sources[(i+1)%3],'zoom next');
  document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));check(!modal.open,'Escape closes');
  gallery.querySelector('[data-festival-gallery-prev]').click();
  check(gallery.querySelector('[data-festival-gallery-current]').textContent===String(i+1),'counter previous');
  next.click();
 }
 check(gallery.querySelector('[data-festival-gallery-current]').textContent==='1','wrap counter');
 if(${!process.env.TRIPBORA_FESTIVAL_CSS}){
  const hero=document.querySelector('.festival-detail-hero'),credit=document.querySelector('.festival-detail-attribution');
  check(getComputedStyle(hero).borderBottomWidth==='0px','no upper divider');
  check(getComputedStyle(credit).justifyContent==='flex-end' && getComputedStyle(credit).textAlign==='right','credit aligned right');
  check(credit.getBoundingClientRect().top>=frame.bottom,'credit below image without overlap');
  const creditSource=credit.querySelector('[data-festival-gallery-source]'),originalCredit=creditSource.textContent;
  creditSource.textContent='아주 긴 사진 제공기관 이름과 행사 사진 저작권 출처 '.repeat(12);
  check(credit.scrollWidth<=credit.clientWidth,'long credit wraps');
  creditSource.textContent=originalCredit;
  check(document.querySelector('h1').textContent.includes('특별한 밤'),'full long title');
  check(document.querySelector('.festival-detail-period').textContent.includes('2026.10.31'),'date preserved');
  check(document.querySelector('.festival-detail-location').textContent.includes('시민 체험 공간'),'full address');
  if(innerWidth>=901)check(heading.left>=frame.right,'two column layout');
  if(innerWidth>=1101){const share=frame.width/(frame.width+heading.width);check(share>=0.60 && share<=0.65,'desktop image share')}
  if(innerWidth>=1280)check(frame.height>=520 && frame.height<=580,'large desktop height');
  check(document.querySelector('.festival-detail-section').getBoundingClientRect().width<=1080,'body width preserved');
  check(getComputedStyle(document.querySelector('.festival-detail-section h2')).borderBottomWidth==='2px','body divider preserved');
 }
 const bookmark=document.querySelector('[data-travel-info-bookmark]');
 bookmark.click();await new Promise(resolve=>setTimeout(resolve,0));
 check(bookmark.getAttribute('aria-pressed')==='true','save state');
 bookmark.click();await new Promise(resolve=>setTimeout(resolve,0));
 check(bookmark.getAttribute('aria-pressed')==='false','unsave state');
 check(bookmarkRequests.length===2 && bookmarkRequests[0].options.method==='POST' && bookmarkRequests[1].options.method==='DELETE','save API unchanged');
 check(bookmarkRequests[0].options.headers['X-CSRF-TOKEN']==='fixture-csrf','save CSRF unchanged');
 check(document.documentElement.scrollWidth<=innerWidth,'no horizontal overflow');
 const poster=slides[1].querySelector('img');
 const posterScale=Math.min(frame.width/poster.naturalWidth,frame.height/poster.naturalHeight);
 parent.postMessage({width:${width},ok:true,gallery:{width:frame.width,height:frame.height},poster:{width:poster.naturalWidth*posterScale,height:poster.naturalHeight*posterScale},headingWidth:heading.width},'*');
}catch(error){parent.postMessage({width:${width},error:String(error)},'*')}});
</script>`;
const encode = value => value.replaceAll('&','&amp;').replaceAll('"','&quot;');
const html = `<!doctype html><meta charset="utf-8"><script>const results=[];addEventListener('message',e=>{results.push(e.data);let p=document.getElementById('results');if(!p){p=document.createElement('pre');p.id='results';document.body.append(p)}p.textContent=JSON.stringify(results)})</script><body>${widths.map(w=>`<iframe style="width:${w}px;height:1000px;border:0" srcdoc="${encode(fixture(w))}"></iframe>`).join('')}</body>`;
const file=path.join(directory,'gallery.html');fs.writeFileSync(file,html);
fs.writeFileSync(path.join(directory,'preview.html'),fixture(1440));
const output=execFileSync(chrome,['--headless','--disable-gpu',`--user-data-dir=${path.join(directory,'chrome-profile')}`,'--no-first-run','--no-default-browser-check','--allow-file-access-from-files','--virtual-time-budget=5000','--dump-dom',pathToFileURL(file).href],{encoding:'utf8',maxBuffer:6e6,timeout:30000,windowsHide:true,stdio:['ignore','pipe','ignore']});
const match=output.match(/<pre id="results">([^<]+)<\/pre>/);assert.ok(match,'browser returns results');
const results=JSON.parse(match[1].replaceAll('&quot;','"'));assert.equal(results.length,widths.length,JSON.stringify(results));
for(const result of results)assert.ok(result.ok,`${result.width}px ${result.error}`);
fs.writeFileSync(process.env.TRIPBORA_FESTIVAL_REPORT || 'build/reports/festival-gallery/after-sizes.json',JSON.stringify(results,null,2));
console.log(`Gallery ratios, license protection, navigation and zoom verified at ${widths.join(', ')}px. Preview: ${path.join(directory,'preview.html')}`);
