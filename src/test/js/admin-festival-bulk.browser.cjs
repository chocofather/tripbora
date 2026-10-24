// Offline real-browser layout smoke test. No application, DB, or network API is started.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const read = file => fs.readFileSync(path.resolve(file), 'utf8');
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-festival-bulk-'));
const renderedPath = 'build/reports/festival-bulk/form.html';
assert.ok(fs.existsSync(renderedPath), 'Run AdminFestivalControllerTest to export real Thymeleaf HTML first');
const rendered = read(renderedPath);
const styles = [...rendered.matchAll(/<link[^>]*href="(\/css\/[^"?]+)/g)].map(match => {
    const asset = match[1].replace(/-[a-f0-9]{32}(?=\.css)/, '');
    return read(asset === '/css/admin-festival-bulk.css' && process.env.TRIPBORA_POSTER_CSS
        ? process.env.TRIPBORA_POSTER_CSS : 'src/main/resources/static' + asset);
});
const template = rendered.match(/<body[^>]*>([\s\S]*?)<\/body>/i)[1]
    .replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
    .replace(/<link\b[^>]*>/gi, '')
    .replace(/th:id="\$\{editMode\}[^\n]*\n/, 'id="festival-create-form"\n');
const css = styles.join('\n').replace(/url\([^)]*\)/g, 'none');
const js = read('src/main/resources/static/js/admin-festival-bulk.js');
const widths = [360, 390, 393, 430, 768, 1024, 1280, 1440];
const fixture = width => `<!doctype html><html><head><meta charset="utf-8"><style>body{margin:0}*{box-sizing:border-box}${css}</style></head><body class="admin-page">${template}<script>
const requests=[];let failPhotos=true,holdPhotos=true,releasePhotos;window.fetch=async(url,options)=>{const contentId=new URL(url,'https://tripbora.test').searchParams.get('contentId');
 const body=options?.body?JSON.parse(options.body):null;requests.push({url,body});
 if(url.includes('/images') && contentId==='205' && failPhotos)return {ok:false,json:async()=>({message:'사진 조회 실패 테스트'})};
 if(url.includes('/images') && contentId==='206')return {ok:true,json:async()=>({items:[]})};
 if(url.includes('/images') && contentId==='207' && holdPhotos)return new Promise(resolve=>{releasePhotos=()=>{holdPhotos=false;resolve({ok:true,json:async()=>({items:[{selectionKey:'MAIN',imageUrl:'http://127.0.0.1:9/poster.jpg',licenseType:'KOGL_TYPE_1',selectable:true}]})})}});
 return {ok:true,json:async()=>url.includes('/images')?{items:[
  {selectionKey:'MAIN',imageName:'세로형 포스터',thumbnailUrl:'http://127.0.0.1:9/poster-thumb.jpg',imageUrl:'http://127.0.0.1:9/poster.jpg',licenseType:'KOGL_TYPE_1',selectable:true},
  {selectionKey:'DETAIL:poster',imageName:'가로형 사진',thumbnailUrl:'http://127.0.0.1:9/poster2-thumb.jpg',imageUrl:'http://127.0.0.1:9/poster2.jpg',licenseType:'KOGL_TYPE_3',selectable:true},
  {selectionKey:'DETAIL:square',imageName:'정사각형 사진',thumbnailUrl:'http://127.0.0.1:9/poster3-thumb.jpg',imageUrl:'http://127.0.0.1:9/poster3.jpg',licenseType:'KOGL_TYPE_1',selectable:true}
 ]}:{status:'READY',token:'token-'+body.contentId,title:'준비된 행사',errors:[],warnings:[]}};};
</script><script>${js}</script><script>
document.addEventListener('DOMContentLoaded', async () => {
 try {
  document.querySelector('[data-festival-registration-mode="bulk"]').click();
  const item = {contentId:'123',title:'긴 축제명 테스트 포스터 선택과 지역 정보',eventStartDate:'2026-10-01',eventEndDate:'2026-10-31',address:'서울특별시 종로구 긴 주소 테스트',registrationStatus:'UNREGISTERED'};
  const card = document.createElement('article'); card.className='admin-kto-festival-candidate';
  const media=document.createElement('div'); media.className='admin-kto-festival-candidate-media'; media.textContent='이미지 없음';
  const body=document.createElement('div'); body.className='admin-kto-festival-candidate-body'; body.textContent=item.title;
  card.append(media,body); document.querySelector('[data-festival-results]').append(card); window.TripBoraFestivalBulk.attach(card,item);
  const other={...item,contentId:'124',title:'두 번째 축제'};
  document.dispatchEvent(new CustomEvent('festival:candidates-rendered',{detail:{items:[item,other]}}));
  document.querySelector('[data-bulk-select-page]').click();
  for(let index=0;index<2;index++){
   document.querySelectorAll('[data-bulk-rows] > article')[index].querySelector('button').click();
   await new Promise(resolve=>setTimeout(resolve,20));
   const options=document.querySelectorAll('[data-bulk-poster-items] .admin-kto-festival-image-option');
   const chosen=options[index], radio=chosen.querySelector('input');
   chosen.querySelector('img').click();
   if(!radio.checked) throw Error('image click must select poster');
   const otherOption=options[1-index];
   otherOption.querySelector('button').click();
   if(!radio.checked || document.querySelector('[data-bulk-large-image]').hidden) throw Error('preview must not change poster');
   if(!document.querySelector('[data-bulk-large-image]').src.endsWith(index===0?'/poster2.jpg':'/poster.jpg')) throw Error('large preview uses original URL only on request');
   otherOption.querySelector('a').addEventListener('click',event=>event.preventDefault());
   otherOption.querySelector('a').click();
   if(!radio.checked) throw Error('source link must not change poster');
   otherOption.click();
   if(!otherOption.querySelector('input').checked) throw Error('card whitespace must select poster');
   chosen.querySelector('strong').click();
   if(!radio.checked) throw Error('title must select poster');
   otherOption.querySelector('input').click();
   radio.focus();radio.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',bubbles:true,cancelable:true}));
   if(!radio.checked) throw Error('Enter must select poster');
   if(getComputedStyle(chosen).borderColor===getComputedStyle(otherOption).borderColor) throw Error('selected border must differ');
   document.querySelector('dialog').close();
  }
  document.querySelector('[data-bulk-preview]').click();await new Promise(resolve=>setTimeout(resolve,50));
  document.querySelector('[data-bulk-confirm]').click();
  document.querySelector('[data-bulk-rows] button').click();await new Promise(resolve=>setTimeout(resolve,20));
  const dialog=document.querySelector('dialog');
  dialog.querySelector('[data-bulk-poster-close]')?.click();
  if(dialog.open) throw Error('close must close dialog');
  if(!document.querySelector('[data-bulk-summary]').textContent.includes('포스터 완료 2건')) throw Error('close must preserve selection');
  document.querySelector('[data-bulk-rows] button').click();await new Promise(resolve=>setTimeout(resolve,20));
  [...dialog.querySelectorAll('button')].find(button=>button.textContent==='포스터 선택 해제').click();
  if(dialog.open || !document.querySelector('[data-bulk-summary]').textContent.includes('포스터 완료 1건')) throw Error('clear must remove only this poster');
  document.querySelector('[data-bulk-rows] button').click();await new Promise(resolve=>setTimeout(resolve,20));
  if([...dialog.querySelectorAll('input')].some(input=>input.checked)) throw Error('cleared poster must stay unchecked');
  dialog.querySelector('img').click();dialog.close();
  document.querySelector('[data-bulk-preview]').click();await new Promise(resolve=>setTimeout(resolve,50));
  document.querySelector('[data-bulk-rows] button').click();await new Promise(resolve=>setTimeout(resolve,20));
  const result={width:${width},overflow:document.documentElement.scrollWidth>innerWidth,bulkVisible:!document.querySelector('[data-festival-bulk]').hidden,formHidden:getComputedStyle(document.querySelector('#festival-title').closest('section')).display==='none',selected:document.querySelector('[data-bulk-summary]').textContent.includes('전체 선택 2/30'),ready:document.querySelector('[data-bulk-summary]').textContent.includes('등록 가능 2건'),posters:requests.filter(request=>request.url.endsWith('/preview')).map(request=>request.body.posterSelection),dialogOverflow:dialog.scrollWidth>dialog.clientWidth};
  const check=(value,message)=>{if(!value)throw Error(message)},wait=()=>new Promise(resolve=>setTimeout(resolve,20));
  const progress=()=>dialog.querySelector('[data-bulk-poster-progress]').textContent;
  const go=async index=>{dialog.querySelectorAll('[data-bulk-poster-steps] button')[index].click();await wait()};
  const imageCalls=id=>requests.filter(request=>request.url.includes('/images?') && new URL(request.url,'https://tripbora.test').searchParams.get('contentId')===String(id)).length;
  dialog.close();document.querySelector('[data-bulk-clear]').click();
  document.dispatchEvent(new CustomEvent('festival:candidates-rendered',{detail:{items:[item]}}));
  document.querySelector('[data-bulk-select-page]').click();
  document.querySelector('[data-bulk-poster-sequence]').click();await wait();
  check(progress().includes('1 / 1') && dialog.querySelector('[data-bulk-poster-previous]').disabled && dialog.querySelector('[data-bulk-poster-next]').textContent==='선택 마치기','single event sequence boundaries');
  dialog.querySelector('input').click();dialog.querySelector('[data-bulk-poster-next]').click();await wait();
  check(!dialog.open,'single event finish closes modal');
  document.querySelector('[data-bulk-clear]').click();
  const twenty=Array.from({length:20},(_,index)=>({...item,contentId:String(200+index),title:'연속 선택 축제 '+(index+1)}));
  document.dispatchEvent(new CustomEvent('festival:candidates-rendered',{detail:{items:twenty}}));
  document.querySelector('[data-bulk-select-page]').click();
  document.dispatchEvent(new CustomEvent('festival:candidates-rendered',{detail:{items:[]}}));
  check(document.querySelector('[data-bulk-summary]').textContent.includes('전체 선택 20/30'),'search page preserves twenty selections');
  document.querySelector('[data-bulk-poster-sequence]').click();await wait();
  check(progress().includes('1 / 20'),'sequence starts at first missing');
  check(imageCalls(200)===1 && imageCalls(201)===0,'only current candidates fetched');
  dialog.querySelector('input').click();check(progress().includes('1 / 20'),'select never auto advances');
  dialog.querySelector('[data-bulk-poster-next]').click();await wait();dialog.querySelectorAll('input')[1].click();
  dialog.querySelector('[data-bulk-poster-previous]').click();await wait();
  check(dialog.querySelector('input').checked && imageCalls(200)===1,'previous restores cached selection');
  [...dialog.querySelectorAll('button')].find(button=>button.textContent==='포스터 선택 해제').click();await wait();
  check(dialog.open && !dialog.querySelector('input').checked,'continuous clear keeps modal open');dialog.querySelector('input').click();
  await go(3);dialog.querySelector('[data-bulk-poster-next]').click();await wait();
  check(progress().includes('5 / 20'),'skip unselected event');
  dialog.querySelector('[data-bulk-poster-missing]').click();await wait();
  check(progress().includes('6 / 20') && dialog.querySelector('[data-bulk-poster-status]').textContent.includes('사진 조회 실패'),'next missing and failed photos');
  dialog.querySelector('[data-bulk-poster-next]').click();await wait();
  check(progress().includes('7 / 20') && dialog.querySelector('[data-bulk-poster-status]').textContent.includes('이미지 없음'),'empty event remains navigable');
  await go(7);await go(8);await go(7);check(imageCalls(207)===1,'in-flight candidates reused');
  await go(8);releasePhotos();await wait();
  check(dialog.querySelector('#festival-bulk-poster-title').textContent==='연속 선택 축제 9','late response cannot replace current event');
  for(let index=0;index<20;index++){
   await go(index);
   if(index===6)continue;
   if(index===5){failPhotos=false;[...dialog.querySelectorAll('button')].find(button=>button.textContent==='사진 다시 불러오기').click();await wait()}
   dialog.querySelector('input').click();
  }
  check(progress().includes('선택 완료 19건') && progress().includes('미선택 1건'),'accurate completed count');
  check(dialog.querySelector('[data-bulk-poster-next]').textContent==='선택 마치기','last action finishes');
  for(const event of twenty)if(event.contentId!=='205')check(imageCalls(event.contentId)===1,'cache reused for '+event.contentId);
  const footer=dialog.querySelector('.festival-bulk-poster-footer');
  dialog.querySelector('[data-bulk-poster-content]').scrollTop=100000;
  check(footer.getBoundingClientRect().bottom<=innerHeight && footer.getBoundingClientRect().top>=0,'footer visible in short viewport');
  check(dialog.scrollHeight<=dialog.clientHeight && getComputedStyle(dialog.querySelector('[data-bulk-poster-content]')).overflowY==='auto','only content scrolls');
  dialog.querySelector('[data-bulk-poster-next]').click();check(!dialog.open,'finish only closes modal');
  document.querySelector('[data-bulk-poster-sequence]').click();await wait();check(progress().includes('7 / 20'),'reopen at missing event');
  await go(0);check(dialog.querySelector('input').checked,'reopen retains poster');dialog.close();
  document.querySelector('[data-bulk-rows] button').click();await wait();check(progress().includes('1 / 1') && dialog.querySelector('input').checked,'individual picker retained');dialog.close();
  document.querySelectorAll('[data-bulk-rows] > article')[6].querySelectorAll('button')[1].click();
  document.querySelector('[data-bulk-poster-sequence]').click();await wait();check(progress().includes('전체 선택 완료'),'removed event clears state and all remaining complete');dialog.close();
  document.querySelector('[data-bulk-preview]').click();await new Promise(resolve=>setTimeout(resolve,100));
  check(document.querySelector('[data-bulk-summary]').textContent.includes('등록 가능 19건'),'final preview uses selected posters');
  check(!requests.some(request=>request.url.endsWith('/start')),'poster operations never register');
  document.querySelector('[data-bulk-rows] > article').querySelectorAll('button')[1].click();
  document.dispatchEvent(new CustomEvent('festival:candidates-rendered',{detail:{items:[twenty[0]]}}));
  document.querySelector('[data-bulk-select-page]').click();
  document.querySelector('[data-bulk-poster-sequence]').click();await wait();
  check(!dialog.querySelector('input').checked && dialog.querySelector('#festival-bulk-poster-title').textContent===twenty[0].title,'deselect clears prior poster on reselect');
  dialog.querySelector('input').click();
  await go(18);
  const activeStep=dialog.querySelector('[aria-current="step"]').getBoundingClientRect(),strip=dialog.querySelector('[data-bulk-poster-steps]').getBoundingClientRect();
  check(activeStep.left>=strip.left-1 && activeStep.right<=strip.right+1,'current step stays visible');
  const posterImages=[...dialog.querySelectorAll('[data-bulk-poster-items] .admin-kto-festival-image-option > img')];
  check(posterImages[0].src.endsWith('/poster-thumb.jpg'),'grid reuses thumbnail URL');
  const shapes=[[900,1200],[1200,600],[800,800]];
  await Promise.all(posterImages.map(async(img,index)=>{
   check(img.loading==='lazy','candidate URL remains lazy before test image measurement');
   // Decode test-only data images even in off-screen iframe cards; production URLs stay lazy.
   img.loading='eager';
   const [w,h]=shapes[index%3];
   img.src='data:image/svg+xml;base64,'+btoa('<svg xmlns="http://www.w3.org/2000/svg" width="'+w+'" height="'+h+'"><rect width="100%" height="100%" fill="#e9e3f5"/><rect x="5" y="5" width="'+(w-10)+'" height="'+(h-10)+'" fill="none" stroke="#7c3aed" stroke-width="10"/><text x="50%" y="50%" text-anchor="middle" fill="#6d28d9" font-size="50">'+w+' x '+h+'</text></svg>');
   await img.decode();
   img.loading='lazy';
  }));
  const imageFrame=posterImages[0].getBoundingClientRect(),modalFrame=dialog.getBoundingClientRect();
  result.modal={width:modalFrame.width,height:modalFrame.height};
  result.image={width:imageFrame.width,height:imageFrame.height,fit:getComputedStyle(posterImages[0]).objectFit};
  const scale=Math.min(imageFrame.width/posterImages[0].naturalWidth,imageFrame.height/posterImages[0].naturalHeight);
  result.containedPoster={width:posterImages[0].naturalWidth*scale,height:posterImages[0].naturalHeight*scale};
  if(${!process.env.TRIPBORA_POSTER_CSS}){
   check(modalFrame.width<=innerWidth*0.96+1 && modalFrame.height<=innerHeight*0.90+1,'modal stays inside viewport');
   const columns=getComputedStyle(dialog.querySelector('[data-bulk-poster-items]')).gridTemplateColumns.split(' ').length;
   check(columns===(innerWidth>=1280?3:innerWidth<=600?1:2),'responsive poster columns');
   for(const img of posterImages){
    const rect=img.getBoundingClientRect(),style=getComputedStyle(img);
    check(Math.abs(rect.width/rect.height-0.75)<0.01,'portrait preview frame 3:4');
    check(style.objectFit==='contain' && style.objectPosition==='50% 50%','full uncropped image at original ratio');
    check(img.loading==='lazy','candidate images remain lazy');
   }
   const oldHeight=posterImages[0].getBoundingClientRect().height;
   dialog.querySelector('[data-bulk-poster-content]').scrollTop=100000;
   posterImages[0].removeAttribute('src');check(Math.abs(posterImages[0].getBoundingClientRect().height-oldHeight)<1,'loading reserves image dimensions');
   dialog.querySelector('[data-bulk-poster-previous]').click();await wait();
   check(dialog.querySelector('[data-bulk-poster-content]').scrollTop===0,'event change resets image list scroll');
  }
  document.documentElement.dataset.posterTestComplete='true';
  result.sequence=true;parent.postMessage(result,'*');
 } catch(error){parent.postMessage({width:${width},error:String(error)},'*');}
});</script></body></html>`;
const encode = value => value.replaceAll('&', '&amp;').replaceAll('"', '&quot;');
const html = `<!doctype html><html><head><meta charset="utf-8"></head><body><script>const results=[];addEventListener('message',e=>{results.push(e.data);let output=document.getElementById('bulk-layout-results');if(!output){output=document.createElement('pre');output.id='bulk-layout-results';document.body.append(output)}output.textContent=JSON.stringify(results)})</script>${widths.map(width => `<iframe style="width:${width}px;height:600px;border:0" srcdoc="${encode(fixture(width))}"></iframe>`).join('')}</body></html>`;
const file = path.join(directory, 'layout.html'); fs.writeFileSync(file, html);
const output = execFileSync(chrome, ['--headless', '--disable-gpu', `--user-data-dir=${path.join(directory, 'chrome-profile')}`, '--no-first-run', '--no-default-browser-check', '--allow-file-access-from-files', '--virtual-time-budget=5000', '--dump-dom', pathToFileURL(file).href], {encoding: 'utf8', maxBuffer: 6e6, timeout: 30000, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore']});
const match = output.match(/<pre id="bulk-layout-results">([^<]+)<\/pre>/);
assert.ok(match, 'all browser fixtures must return results');
const results = JSON.parse(match[1].replaceAll('&quot;', '"'));
assert.equal(results.length, widths.length, JSON.stringify(results));
for (const result of results) {
    assert.ok(!result.error, `${result.width}px ${result.error}`);
    assert.equal(result.overflow, false, `${result.width}px horizontal overflow`);
    assert.equal(result.bulkVisible, true, `${result.width}px bulk panel visible`);
    assert.equal(result.formHidden, true, `${result.width}px single form hidden`);
    assert.equal(result.selected, true, `${result.width}px shared candidate selected`);
    assert.equal(result.ready, true, `${result.width}px both poster choices previewed`);
    assert.deepEqual(result.posters, ['MAIN', 'DETAIL:poster', 'MAIN', 'DETAIL:poster']);
    assert.equal(result.dialogOverflow, false, `${result.width}px poster dialog overflow`);
    assert.equal(result.sequence, true, `${result.width}px continuous poster workflow`);
}
fs.writeFileSync(process.env.TRIPBORA_POSTER_REPORT || 'build/reports/festival-bulk/modal-after-sizes.json',JSON.stringify(results,null,2));
console.log(`Bulk layout verified in Chrome at ${widths.join(', ')}px (offline fixture).`);
console.log(`Fixture: ${file}`);
