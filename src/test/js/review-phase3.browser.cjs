// Actual Thymeleaf gallery/modal + production carousel/viewer/review renderer. Preview HTTP server only; no app/DB.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {execFile}=require('node:child_process');const {promisify}=require('node:util');const run=promisify(execFile);
const root=path.resolve('build/reports/review-gallery'),source=path.resolve('src/main/resources/static');
const chrome=process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
const read=f=>fs.readFileSync(path.join(source,f),'utf8');
const css=['style.css','detail.css','comment.css','content-comment.css','destination-detail-mobile.css'].map(f=>read('css/'+f)).join('\n');
const strip=s=>s.replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g,'').replace(/export (function|let)/g,'$1');
const code=['comment/messages.js','comment/render.js','comment/events.js','detail-carousel.js','destination-image-modal.js'].map(f=>strip(read('js/'+f))).join('\n');
const svg=color=>'data:image/svg+xml;base64,'+Buffer.from(`<svg xmlns="http://www.w3.org/2000/svg" width="900" height="600"><rect width="900" height="600" fill="${color}"/><circle cx="450" cy="270" r="140" fill="#fff"/></svg>`).toString('base64');
const admin=svg('#b7a4d5'),review=svg('#7657c8');
const languages=['ko','en','ja','zh-CN','zh-TW'],widths=[360,390,393,430,768,1440];
const cases=languages.flatMap(lang=>widths.map(width=>({lang,width,kind:'mixed'})).concat(['admin','review','empty','protected','many'].flatMap(kind=>[360,1440].map(width=>({lang,width,kind})))));
function page(kind,lang){
 const markup=fs.readFileSync(path.join(root,(kind==='many'?'mixed':kind)+'-'+lang+'.html'),'utf8').replaceAll('/fixture/admin.svg',admin).replaceAll('/fixture/review.svg',review);
 const modal=fs.readFileSync(path.join(root,'modal-'+lang+'.html'),'utf8'),messages=fs.readFileSync(path.join(root,'messages-'+lang+'.html'),'utf8');
 // Transition is disabled only in this fixture so screenshots capture the selected slide, not an intermediate frame.
 return `<!doctype html><html lang="${lang}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}body{margin:0}.carousel .track{transition:none!important}.preview-reviews{max-width:900px;margin:24px auto;padding:0 16px}</style><script src="/js/jquery-3.6.0.min.js"></script><div class="destination-detail-page">${messages}${markup}${modal}<section class="preview-reviews content-comments"><ul id="cards" class="comment-list"></ul></section><div id="image-modal" style="display:none"></div></div><script>
 const errors=[];window.addEventListener('error',e=>{if(e.message)errors.push(e.message)});
 if(${JSON.stringify(kind)}==='many')for(let i=2;i<32;i++){
  const track=document.querySelector('.carousel .track'),strip=document.querySelector('.destination-gallery-thumbnails');
  const slide=track.firstElementChild.cloneNode(true);slide.dataset.galleryKey='admin:'+(100+i);track.append(slide);
  const thumb=strip.firstElementChild.cloneNode(true);thumb.dataset.galleryIndex=i;strip.append(thumb);
 }
 ${code}
 const photos=${JSON.stringify([review,svg('#8197b7'),svg('#8caa92'),svg('#c7ac7b'),svg('#c18ba1')])};
 const base={writer:{id:7,nickname:'Traveler',profileImage:photos[0]},createdAt:'2026-10-31T10:00:00',updatedAt:'2026-10-31T10:00:00',rating:5,content:'방문 경험과 사진을 공유합니다.',likes:1,isLoggedIn:false};
 const cards=document.getElementById('cards');cards.append(createCommentItem({...base,id:20,imageUrls:photos,photos:photos.map((url,i)=>({id:7+i,imageUrl:url}))}),createCommentItem({...base,id:30,imageUrls:[${JSON.stringify(svg('#f0a34a'))}],photos:[{id:12,imageUrl:${JSON.stringify(svg('#f0a34a'))}}]}));
 bindSingleImageModal();
 for(let count=2;count<=4;count++)cards.append(createCommentItem({...base,id:40+count,imageUrls:photos.slice(0,count),photos:photos.slice(0,count).map((url,i)=>({id:100+count*5+i,imageUrl:url}))}));
 const check=(v,m)=>{if(!v)throw Error(m)};let changes=0;document.addEventListener('destination-gallery-change',()=>changes++);
 const click=el=>el.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true}));
 const modal=document.getElementById('destination-image-modal'),enlarged=modal.querySelector('.image-modal-img');
 window.addEventListener('load',()=>setTimeout(()=>{try{
  const slides=[...document.querySelectorAll('.carousel .slide')],kind=${JSON.stringify(kind)};
  check(slides.length===(kind==='many'?32:kind==='mixed'||kind==='protected'?2:kind==='empty'?0:1),'gallery combination');
  if(kind==='many'){
   const strip=document.querySelector('.destination-gallery-thumbnails');
   check(!document.querySelector('[data-gallery-scroll="1"]').hidden,'overflow navigation visible');
   click(document.querySelector('[data-gallery-index="31"]'));check(strip.scrollLeft>0,'selected thumbnail scrolled into view');
   check(document.querySelector('[data-gallery-index="31"]').getAttribute('aria-current')==='true','last thumbnail active');
   click(document.querySelector('[data-gallery-index="0"]'));check(strip.scrollLeft<=2,'first thumbnail restored');
   click(document.querySelector('[data-gallery-scroll="1"]'));check(strip.scrollLeft>20,'rail next navigation');
   click(document.querySelector('[data-gallery-scroll="-1"]'));check(strip.scrollLeft<=2,'rail previous navigation');
   strip.setPointerCapture=()=>{};
   strip.dispatchEvent(new PointerEvent('pointerdown',{pointerType:'mouse',button:0,clientX:220}));
   strip.dispatchEvent(new PointerEvent('pointermove',{pointerType:'mouse',clientX:100}));
   strip.dispatchEvent(new PointerEvent('pointerup',{pointerType:'mouse',clientX:100}));
   check(strip.scrollLeft>20,'mouse drag scroll');click(strip);strip.scrollLeft=0;
  }
  if(kind==='protected')check(getComputedStyle(document.querySelector('.destination-gallery-thumbnails .is-no-derivatives img')).objectFit==='contain','protected admin thumbnail is not cropped');
  if(slides.length){
   const carousel=document.querySelector('.carousel'),key=(target,value)=>{const event=new KeyboardEvent('keydown',{key:value,bubbles:true,cancelable:true});target.dispatchEvent(event);return event};
   document.activeElement?.blur();
   key(document.body,'ArrowRight');
   check(document.querySelector('.slide.active')===slides[slides.length>1?1:0],'first visit keyboard without autofocus');
   key(document.body,'ArrowLeft');
   click(slides[0]);check(modal.open,'slide padding click opens modal');click(modal.querySelector('.image-modal-close'));
   click(carousel.querySelector('.track'));check(modal.open,'transition common ancestor click opens modal');click(modal.querySelector('.image-modal-close'));
   for(let repeat=0;repeat<12;repeat++){
    carousel.querySelector('.nav.next').click();
    const activeImage=document.querySelector('.slide.active img');
    click(carousel.querySelector('.track'));check(modal.open&&enlarged.src===activeImage.src,'rapid transition opens current image');
    document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));check(!modal.open,'repeat Escape closes');
   }
   click(document.querySelector('[data-gallery-index="0"]')||carousel.querySelector('.nav.next'));
   document.activeElement?.blur();
   const rect=carousel.getBoundingClientRect.bind(carousel),beforeOffscreen=document.querySelector('.slide.active');
   carousel.getBoundingClientRect=()=>({width:400,height:300,left:0,right:400,top:-500,bottom:-200});
   check(!key(document.body,'ArrowRight').defaultPrevented&&document.querySelector('.slide.active')===beforeOffscreen,'offscreen gallery ignores arrows');
   carousel.getBoundingClientRect=rect;
   carousel.focus();check(document.activeElement===carousel,'main carousel focusable');
   const initial=document.querySelector('.slide.active');
   const right=key(carousel,'ArrowRight');
   check(right.defaultPrevented===(slides.length>1),'only navigation prevents default');
   check(document.querySelector('.slide.active')===slides[slides.length>1?1:0],'main keyboard next');
   key(carousel,'ArrowLeft');check(document.querySelector('.slide.active')===initial,'main keyboard previous');
   key(carousel,'ArrowLeft');check(document.querySelector('.slide.active')===slides.at(-1),'main keyboard wraps');
   key(carousel,'ArrowRight');check(document.querySelector('.slide.active')===slides[0],'main keyboard wrap to first');
   const next=carousel.querySelector('.nav.next');next.focus();key(next,'ArrowRight');
   check(document.querySelector('.slide.active')===slides[slides.length>1?1:0],'navigation button arrows');
   key(next,'ArrowLeft');
   for(const tag of ['input','textarea','div']){
    const input=document.createElement(tag);if(tag==='div'){input.contentEditable='true';input.innerHTML='<span>edit</span>';}
    carousel.append(input);input.focus();const event=key(input.firstElementChild||input,'ArrowRight');
    check(!event.defaultPrevented&&document.querySelector('.slide.active')===initial,'editable keeps arrow '+tag);input.remove();
   }
   const outside=document.createElement('button');document.body.append(outside);outside.focus();check(!key(outside,'ArrowRight').defaultPrevented&&document.querySelector('.slide.active')===initial,'outside keeps arrow');outside.remove();
   const img=slides[0].querySelector('img');click(img);check(modal.open,'main modal opens');check(enlarged.src===img.src,'correct first image');
   const changesBefore=changes;key(carousel,'ArrowRight');check(changes-changesBefore===(slides.length>1?1:0),'modal handles arrow once');
   key(carousel,'ArrowLeft');
   if(kind==='mixed'){
    click(modal.querySelector('.image-modal-nav.next'));check(enlarged.src===slides[1].querySelector('img').src,'gallery next');
    check(document.querySelector('.slide.active').dataset.galleryKey==='review:7','background tracks modal');
    const link=document.querySelector('#destination-image-modal-caption a');check(link.href.endsWith('/destinations/15?commentId=20'),'modal original review link');
    check(document.querySelector('[data-destination-image-review-link]').getAttribute('href')==='/destinations/15?commentId=20','slider review link');
    click(modal.querySelector('.image-modal-close'));click(document.querySelector('[data-gallery-index="0"]'));
    check(document.querySelector('.slide.active').dataset.galleryKey==='admin:7','thumbnail navigation');
    check(!document.querySelector('[data-destination-image-license-link]').hidden,'admin license restored');
   }else click(modal.querySelector('.image-modal-close'));
  }
  const before=changes,background=document.querySelector('.slide.active')?.dataset.galleryKey;
  const cardPhotos=[...document.querySelector('[data-id="20"] .comment-images').children];
  check(cardPhotos.length===5&&cardPhotos[0].dataset.reviewImageId==='7','original review photo IDs');click(cardPhotos[0]);
  check(modal.open&&enlarged.src===cardPhotos[0].src,'card opens reused modal');
  const cardLink=document.querySelector('#destination-image-modal-caption a');check(cardLink.href.endsWith('/destinations/15?commentId=20'),'card review link');
  for(let i=1;i<=5;i++){document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));check(enlarged.src===cardPhotos[i%5].src,'only five photos of this review');}
  check(changes===before,'card scope must not emit gallery index');check(document.querySelector('.slide.active')?.dataset.galleryKey===background,'card does not move main slider');
  if(innerWidth<=600){
   enlarged.setPointerCapture=()=>{};
   enlarged.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,pointerId:1,isPrimary:true,button:0,clientX:250,clientY:100}));
   enlarged.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,pointerId:1,isPrimary:true,button:0,clientX:100,clientY:102}));
   check(enlarged.src===cardPhotos[1].src,'mobile swipe');
  }
  document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));check(!modal.open,'Escape closes');check(document.getElementById('image-modal').style.display==='none','legacy modal never opens');
  if(slides.length){click(slides[0].querySelector('img'));document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));check(enlarged.src===slides[slides.length>1?1:0].querySelector('img').src,'main range restored after card');click(modal.querySelector('.image-modal-close'));}
  for(const id of [30,42,43,44,20]){
   const group=[...document.querySelector('[data-id="'+id+'"] .comment-images').children];
   click(group[0]);check(modal.open,'review with '+group.length+' photos opens');
   for(let i=1;i<=group.length;i++){document.dispatchEvent(new KeyboardEvent('keydown',{key:'ArrowRight',bubbles:true,cancelable:true}));check(enlarged.src===group[i%group.length].src,'review photo range '+group.length);}
   click(modal.querySelector('.image-modal-close'));
  }
  const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);check(overflow<=1,'overflow '+overflow);
  parent.postMessage({kind,lang:document.documentElement.lang,width:innerWidth,overflow,errors},'*');
 }catch(e){parent.postMessage({kind:${JSON.stringify(kind)},lang:document.documentElement.lang,width:innerWidth,error:e.stack,errors},'*')}},80));
 </script></html>`;
}
const server=http.createServer((req,res)=>{
 const url=new URL(req.url,'http://127.0.0.1');res.setHeader('Cache-Control','no-store');
 if(url.pathname==='/js/jquery-3.6.0.min.js'){res.setHeader('Content-Type','text/javascript');res.end(read('js/jquery-3.6.0.min.js'));return;}
 res.setHeader('Content-Type','text/html; charset=utf-8');
 if(url.pathname==='/destinations/15'){res.end(page(url.searchParams.get('kind')||'mixed',url.searchParams.get('lang')||'ko'));return;}
 if(url.pathname==='/preview'){const width=Number(url.searchParams.get('width'))||390;res.end('<style>body{margin:0}iframe{border:0;width:'+width+'px;height:1700px}</style><iframe src="/destinations/15?kind=mixed&lang=ko"></iframe>');return;}
 res.end('<!doctype html><meta charset="utf-8"><pre id="results"></pre><script>const rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length==='+cases.length+')document.getElementById("results").textContent=JSON.stringify(rows)});</script>'+cases.map(c=>'<iframe style="width:'+c.width+'px;height:1400px" src="/destinations/15?kind='+c.kind+'&lang='+c.lang+'"></iframe>').join(''));
});
(async()=>{await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const origin='http://127.0.0.1:'+server.address().port;
 const flags=['--headless=new','--force-prefers-reduced-motion','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--user-data-dir='+path.join(root,'chrome-profile'),'--virtual-time-budget=16000'];
 try{const {stdout}=await run(chrome,[...flags,'--dump-dom',origin],{encoding:'utf8',timeout:60000,maxBuffer:32*1024*1024});
 const match=stdout.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1],'gallery browser measurements completed');
 const results=JSON.parse(match[1].replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify(results,null,2));
 for(const row of results){assert.ok(!row.error,JSON.stringify(row));assert.deepEqual(row.errors,[]);}
 for(const width of [390,1440])await run(chrome,[...flags,'--window-size='+Math.max(550,width+10)+',1800','--screenshot='+path.join(root,'gallery-'+width+'.png'),origin+'/preview?width='+width],{timeout:30000,maxBuffer:1024*1024});
 console.log(JSON.stringify({cases:results.length,languages,widths,maxOverflow:Math.max(...results.map(r=>r.overflow))}));
 }finally{await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1});
