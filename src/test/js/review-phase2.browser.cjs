// Run after ReviewFormRenderingTest. Real Thymeleaf section and production JS/CSS, simulated API only.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {execFileSync}=require('node:child_process');const {pathToFileURL}=require('node:url');
const root=path.resolve('build/reports/review-ui'),source=path.resolve('src/main/resources/static');
const css=['style.css','detail.css','comment.css','content-comment.css','destination-detail-mobile.css'].map(f=>fs.readFileSync(path.join(source,'css',f),'utf8')).join('\n');
const production=['comment-deep-link.js','comment/messages.js','comment/commentsState.js','comment/events.js','comment/render.js','comment/init.js']
 .map(f=>fs.readFileSync(path.join(source,'js',f),'utf8').replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g,'').replace(/export (function|let)/g,'$1')).join('\n');
const setup=`
const errors=[],requests=[],saves=[];window.addEventListener('error',e=>{if(e.message)errors.push(e.message)});
window.alert=()=>{};window.confirm=()=>true;
const photo='data:image/svg+xml;base64,'+btoa('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="#ece7f5"/><path d="M0 120L65 30L160 120Z" fill="#b6a4d7"/></svg>');
const base={writer:{id:7,nickname:'여행자 민준',profileImage:photo},content:'직접 방문해 보니 산책로가 편하고 주변 풍경이 좋았습니다. 다음 여행에도 다시 들르고 싶어요.',createdAt:'2026-10-30T12:00:00',updatedAt:'2026-10-30T12:00:00',likes:2,isLoggedIn:true,myComment:true,translationAvailable:true,imageUrls:[]};
const roots=[{...base,id:20,rating:5,imageUrls:Array(5).fill(photo)},{...base,id:21,rating:3},{...base,id:22,rating:null},{...base,id:24,moderated:true,content:null,rating:null},{...base,id:25,rating:5},{...base,id:26,rating:4},{...base,id:27,rating:4},{...base,id:28,rating:null}];
const reply={...base,id:23,parentCommentId:20,content:'방문 팁 감사합니다.',rating:null};
roots[0].content += '\\n\\n' + base.content.repeat(12);
const stats={reviewCount:7,ratingCount:5,averageRating:4.2,distribution:[0,0,1,2,2]};
const getDestinationId=()=>10;
const fetchCommentsPage=async(id,page,size,sort)=>{requests.push({page,sort});const order=sort==='rating_desc'?[25,20,27,26,21,22,28,24]:sort==='rating_asc'?[21,27,26,25,20,22,28,24]:sort==='oldest'?[28,27,26,25,24,22,21,20]:roots.map(c=>c.id);const subset=order.slice(page*size,(page+1)*size).map(id=>roots.find(c=>c.id===id));return {content:subset.some(c=>c.id===20)?[...subset,reply]:subset,reviewCount:7,reviewStatistics:stats,last:(page+1)*size>=roots.length}};
const fetchCommentLocation=async()=>({page:1});
let failCreate=true,failTranslation=false;
const postComment=async(id,data)=>{if(failCreate){failCreate=false;throw Error('validation');}saves.push({type:'create',rating:data.get('rating'),photos:data.getAll('images').length});return {id:30}};
const postReply=async()=>({id:29});const fetchOwnCommentImages=async()=>[];
const updateCommentWithImages=async(id,data)=>{saves.push({type:'edit',id,rating:data.get('rating')})};
const toggleLikeApi=async()=> 'liked';const deleteCommentApi=async()=>({ok:true});
const fetchCommentTranslation=async()=>{if(failTranslation)throw Error('temporary');return {status:'READY',translatedText:'Translated visit experience'}};
${production}
const check=(ok,message)=>{if(!ok)throw Error(message)};const tick=()=>new Promise(r=>setTimeout(r,30));
window.addEventListener('load',async()=>{try{
 await tick();check(document.getElementById('review-average').textContent==='4.2','average');
 check(document.getElementById('comment-count').textContent==='7','root count excludes replies');
 check(document.getElementById('review-rating-count').textContent===detailMessage('reviewRatingCount',5),'rating count');
 check(document.querySelector('[data-rating-score="5"] meter').value===2,'distribution');
 const composer=document.getElementById('review-composer');check(composer.hidden,'composer collapsed');
 document.querySelector('.review-write-toggle').click();check(!composer.hidden,'composer opens');
 const form=document.getElementById('comment-form');form.querySelector('[name=rating][value="4"]').click();form.querySelector('textarea').value='방문 경험';
 const transfer=new DataTransfer();for(let i=0;i<5;i++)transfer.items.add(new File(['photo'],i+'.png',{type:'image/png'}));
 const input=form.querySelector('input[type=file]');input.files=transfer.files;input.dispatchEvent(new Event('change'));
 check(form.querySelectorAll('.comment-image-preview img').length===5,'five draft photos');
 form.requestSubmit();await tick();check(!composer.hidden&&form.querySelectorAll('.comment-image-preview img').length===5,'failed create keeps draft');
 form.requestSubmit();await tick();check(saves[0].rating==='4'&&saves[0].photos===5,'new review payload');
 check(composer.hidden&&!form.querySelector('[name=rating]:checked')&&form.querySelector('textarea').value===''&&form.querySelectorAll('.comment-image-preview img').length===0,'success collapses and resets draft');
 check(!document.querySelector('.reply-btn,.review-replies,.reply-list,[data-id="23"]'),'review-only display');
 document.querySelector('[data-id="20"] .like-btn').click();await tick();
 const translated=document.querySelector('[data-id="20"] .translate-btn');translated.click();await tick();
 check(document.querySelector('[data-id="20"] .comment-content').textContent==='Translated visit experience','translation shown');
 translated.click();check(document.querySelector('[data-id="20"] .comment-content').textContent.includes('직접 방문'),'original restored');
 failTranslation=true;translated.click();await tick();check(!document.querySelector('[data-id="20"] .content-comment-translation-status').hidden,'translation failure inline');failTranslation=false;
 check(document.querySelectorAll('[data-id="20"] > .content-comment-card .comment-image').length===5,'five saved photos');
 check(!document.querySelector('[data-id="21"] .comment-images'),'no-photo review');
 check(document.querySelector('[data-id="22"] .review-legacy-badge'),'legacy badge');
 const dateButton=document.querySelector('[data-date-sort]');
 check(dateButton.textContent.endsWith('↓'),'initial date direction');
 dateButton.click();await tick();check(requests.at(-1).sort==='oldest'&&dateButton.textContent.endsWith('↑'),'date toggles oldest');
 dateButton.click();await tick();check(requests.at(-1).sort==='latest'&&dateButton.textContent.endsWith('↓'),'date toggles latest');
 for(const sort of ['rating_desc','rating_asc','latest','oldest','likes','oldest','latest']){
  const button=sort==='oldest'||sort==='latest'?dateButton:document.querySelector('[data-comment-sort="'+sort+'"]');
  button.click();await tick();
  check(requests.at(-1).sort===sort&&requests.at(-1).page===0,'sort '+sort);
  if(sort==='rating_desc')check(document.querySelector('#comment-list > li').dataset.id==='25','high-first');
  if(sort==='rating_asc')check(document.querySelector('#comment-list > li').dataset.id==='21','low-first');
 }
 document.getElementById('load-more-comments').click();await tick();check(requests.at(-1).page===1,'next page');
 check(document.querySelectorAll('#comment-list > li').length===8,'append roots');
 check(document.getElementById('load-more-comments').hidden,'last page');
 const rated=document.querySelector('[data-id="20"]');rated.querySelector('.edit-btn').click();await tick();
 check(rated.querySelector('[name=rating]:checked').value==='5','existing rating in editor');rated.querySelector('[name=rating][value="2"]').click();rated.querySelector('.save-edit-btn').click();await tick();
 check(saves.at(-1).rating==='2','rating edit payload');
 check(!document.querySelector('.edit-comment-form'),'saved editor closes');
 renderReviewStatistics({reviewCount:2,ratingCount:0,averageRating:null,distribution:[0,0,0,0,0]});check(document.getElementById('review-average').textContent===detailMessage('reviewNoRating'),'no ratings');renderReviewStatistics(stats);
 const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);check(overflow<=1,'overflow '+overflow);
 parent.postMessage({width:innerWidth,lang:document.documentElement.lang,overflow,sorts:requests.map(r=>r.sort),errors},'*');
 }catch(error){parent.postMessage({width:innerWidth,lang:document.documentElement.lang,error:error.stack,errors},'*')}});`;
const languages=['ko','en','ja','zh-CN','zh-TW'];
for(const lang of languages){
 const section=fs.readFileSync(path.join(root,'section-'+lang+'.html'),'utf8'),messages=fs.readFileSync(path.join(root,'messages-'+lang+'.html'),'utf8');
 const page=`<!doctype html><html lang="${lang}"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>${css}body{margin:0}</style><div class="destination-detail-page">${messages}${section}</div><script>${setup}</script></html>`;
 fs.writeFileSync(path.join(root,'phase2-interaction-'+lang+'.html'),page);
 if(lang==='ko')fs.writeFileSync(path.join(root,'phase2-interaction.html'),page);
}
const widths=[360,390,393,430,768,1440],fixture=path.join(root,'phase2-browser-fixture.html');
fs.writeFileSync(fixture,'<!doctype html><meta charset="utf-8"><pre id="results"></pre><script>const rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length===30)document.getElementById("results").textContent=JSON.stringify(rows)});</script>'+languages.flatMap(lang=>widths.map(w=>`<iframe style="width:${w}px;height:1800px" src="phase2-interaction-${lang}.html${w===393?'?commentId=23':''}"></iframe>`)).join(''));
const chrome=process.env.CHROME_PATH||'C:/Program Files/Google/Chrome/Application/chrome.exe';
const output=execFileSync(chrome,['--headless=new','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--allow-file-access-from-files',`--user-data-dir=${path.join(root,'phase2-chrome-profile')}`,'--virtual-time-budget=12000','--dump-dom',pathToFileURL(fixture).href],{encoding:'utf8',timeout:60000,maxBuffer:16*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1],'browser results completed');
const results=JSON.parse(match[1].replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));fs.writeFileSync(path.join(root,'phase2-browser-results.json'),JSON.stringify(results,null,2));
for(const result of results){assert.ok(!result.error,JSON.stringify(result));assert.deepEqual(result.errors,[])}
console.log(JSON.stringify({cases:results.length,widths,languages,maxOverflow:Math.max(...results.map(r=>r.overflow))}));
