const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{pathToFileURL}=require('node:url');
const root=path.resolve('build/reports/admin-review-destinations'),widths=[360,390,393,430,768,1440];
const css=['admin-layout.css','review-image-management.css','admin-mobile.css'].map(f=>fs.readFileSync('src/main/resources/static/css/'+f,'utf8')).join('\n');
const html=fs.readFileSync(path.join(root,'page.html'),'utf8');
const script=fs.readFileSync('src/main/resources/static/js/admin-review-photo-analysis.js','utf8');
fs.writeFileSync(path.join(root,'workflow-case.html'),`<!doctype html><meta charset="utf-8"><style>${css}body{margin:0}.admin-page-section{padding:12px}</style><div class="admin-shell" style="display:block">${html}</div><script>
const errors=[];addEventListener('error',e=>errors.push(e.message));const requests=[],confirmations=[];
window.confirm=text=>{confirmations.push(text);return true};
const r=document.querySelector('[data-photo-workflow]');r.dataset.page='0';r.dataset.section='clean';
let cleanIds=Array.from({length:20},(_,i)=>i+1),conflict=false,exactDone=false,similarDone=false,bigExact=false,bigKept=null;
const picture='data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="%237657c8"/></svg>';
const photo=(id,status='PENDING')=>({id,commentId:id+1000,userId:id,nickname:'<img src=x onerror=alert(1)>',imageUrl:picture,galleryStatus:status,eligible:true,eligibilityReason:null,analysisState:'READY',distance:2,galleryPriorityVersion:4});
const exact=Array.from({length:5},(_,i)=>photo(201+i,i===0?'APPROVED':'PENDING'));
const similar=Array.from({length:5},(_,i)=>photo(301+i));
window.fetch=async(url,options={})=>{
 requests.push({url,options});const u=new URL(url,location.href),p=Number(u.searchParams.get('page')||0);
 if(options.method==='POST') {
  if(conflict)return {ok:false,status:409,json:async()=>({message:'처리 0건 · 묶음 전체 취소: 다른 관리자 심사'})};
  const body=JSON.parse(options.body);
  if(url.endsWith('/approve'))cleanIds=cleanIds.filter(id=>!Object.hasOwn(body.expected,id));
  if(url.endsWith('/decide')||url.endsWith('/reject-group')){if(bigExact){if(body.selected?.length)bigKept=body.selected[0];if(Object.hasOwn(body.expected,'901'))bigExact=false;}else if(body.kind==='exact')exactDone=true;else similarDone=true;}
  return {ok:true,json:async()=>({message:'선택한 묶음 전체 처리 완료'})};
 }
 if(u.pathname.endsWith('/comparison')) {
  if(bigExact)return {ok:true,json:async()=>({photos:Array.from({length:p===0?100:1},(_,i)=>photo(p===0?801+i:901)),hasNext:p===0,counts:{total:101,approved:bigKept?1:0,pending:bigKept?1:101,rejected:0,revoked:0}})};
  const rows=u.searchParams.get('kind')==='exact'?exact:similar;
  // 같은 ID가 반복되어도 카드·요청은 하나만 유지한다.
  return {ok:true,json:async()=>({photos:[...rows,rows[0]],hasNext:false,counts:{total:5,approved:rows===exact?1:0,pending:rows===exact?4:5,rejected:0,revoked:0}})};
 }
 const section=u.searchParams.get('section'),groups=[];
 if(bigExact)groups.push({anchorId:801,kind:'exact',key:'exact:big',total:101,pending:101,approved:0,photos:exact.slice(0,4)});
 if(!exactDone)groups.push({anchorId:201,kind:'exact',key:'exact:a',total:5,pending:4,approved:1,photos:exact.slice(0,4)});
 if(!similarDone)groups.push({anchorId:301,kind:'similar',key:'similar:301',total:5,pending:5,approved:0,photos:similar.slice(0,4)});
 const limited=[{...photo(501),analysisState:'PENDING'},{...photo(502),analysisState:'FAILED'},{...photo(503),analysisState:'HASH_ONLY'}];
 return {ok:true,json:async()=>({counts:{clean:cleanIds.length,groups:groups.reduce((s,g)=>s+g.pending,0),limited:3},photos:section==='clean'?cleanIds.slice(p*100,(p+1)*100).map(id=>photo(id)):section==='limited'?limited:section==='all'?[photo(601,'NONE'),photo(602,'REJECTED'),photo(603,'REVOKED'),photo(604,'APPROVED')]:[],groups:section==='groups'?groups:[],hasNext:section==='clean'&&cleanIds.length>(p+1)*100})};
};
${script}
(async()=>{try{
 const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await new Promise(resolve=>setTimeout(resolve,0));};
 const $=s=>r.querySelector(s);await flush();
 if($('[data-workflow-grid]').children.length!==20)throw Error('20 clean photos');
 if($('[data-workflow-grid] [onerror]'))throw Error('unsafe author markup');
 $('[data-clean-approve]').click();await flush();
 if(Object.keys(JSON.parse(requests.filter(x=>x.options.method==='POST').at(-1).options.body).expected).length!==20||cleanIds.length)throw Error('20 photos bulk approval');
 cleanIds=Array.from({length:20},(_,i)=>i+1);$('[data-section=clean]').click();await flush();
 $('[data-clean-select="2"]').click();$('[data-clean-approve]').click();await flush();
 let post=requests.filter(x=>x.options.method==='POST').at(-1),body=JSON.parse(post.options.body);
 if(Object.keys(body.expected).length!==19||Object.hasOwn(body.expected,'2')||!post.options.headers['X-CSRF-TOKEN'])throw Error('partial exclusion / csrf');
 if(!confirmations.at(-1).includes('ID 범위')||$('[data-count=clean]').textContent!=='1장')throw Error('confirmation / refreshed statistics');
 cleanIds=Array.from({length:101},(_,i)=>1001+i);$('[data-section=clean]').click();await flush();
 if($('[data-workflow-grid]').children.length!==100)throw Error('bounded first page');
 $('[data-workflow-next]').click();await flush();
 if($('[data-workflow-grid]').children.length!==1)throw Error('explicit second page');
 $('[data-workflow-prev]').click();await flush();$('[data-clean-approve]').click();await flush();
 post=requests.filter(x=>x.options.method==='POST').at(-1);if(Object.keys(JSON.parse(post.options.body).expected).length!==100||cleanIds.length!==1)throw Error('100 limit, no silent remainder');
 conflict=true;$('[data-clean-approve]').click();await flush();if(!$('[data-workflow-error]').textContent.includes('0건'))throw Error('conflict result');conflict=false;
 $('[data-section=limited]').click();await flush();if($('[data-clean-toolbar]').hidden||!$('[data-workflow-grid]').textContent.includes('유사도 분석 불가')||!$('[data-workflow-grid]').textContent.includes('분석 실패'))throw Error('limited classification');
 $('[data-clean-all]').click();$('[data-clean-approve]').click();await flush();
 post=requests.filter(x=>x.options.method==='POST').at(-1);body=JSON.parse(post.options.body);
 if(!post.url.endsWith('/workflow/batch')||body.action!=='APPROVE'||Object.keys(body.expected).length!==3)throw Error('unanalysed manual bulk approve');
 $('[data-clean-all]').click();$('[data-clean-reject]').click();await flush();
 body=JSON.parse(requests.filter(x=>x.options.method==='POST').at(-1).options.body);if(body.action!=='REJECT'||Object.keys(body.expected).length!==3)throw Error('manual bulk reject');
 $('[data-section=groups]').click();await flush();if(r.querySelectorAll('[data-group]').length!==2)throw Error('group cards');
 $('[data-group] button').click();await flush();const modal=$('[data-compare-dialog]');
 if(!modal.open||modal.querySelectorAll('[data-use-photo]').length!==5||modal.querySelectorAll('[data-use-photo]:checked').length!==1)throw Error('exact5 recommended1 / unique IDs');
 const modalOverflow=modal.scrollWidth-modal.clientWidth;if(modalOverflow>1)throw Error('modal overflow '+modalOverflow);
 $('[data-compare-decide]').click();await flush();
 post=requests.filter(x=>x.options.method==='POST').at(-1);body=JSON.parse(post.options.body);
 if(body.selected.length!==1||body.selected[0]!==201||Object.keys(body.expected).length!==5)throw Error('exact decision snapshot');
 if(!modal.open||!$('[data-compare-title]').textContent.includes('유사'))throw Error('next unprocessed group');
 $('[data-use-photo="301"]').click();$('[data-use-photo="302"]').click();$('[data-compare-decide]').click();await flush();
 post=requests.filter(x=>x.options.method==='POST').at(-1);body=JSON.parse(post.options.body);
 if(body.kind!=='similar'||body.selected.length!==2||Object.keys(body.expected).length!==5||modal.open)throw Error('similar5 select2 / group refresh');
 bigExact=true;$('[data-section=groups]').click();await flush();$('[data-group] button').click();await flush();
 if(modal.querySelectorAll('[data-use-photo]').length!==100)throw Error('large group bounded page');
 $('[data-compare-decide]').click();await flush();
 if(!modal.open||modal.querySelectorAll('[data-use-photo]:checked').length!==0||!$('[data-compare-chunk]').textContent.includes('#801'))throw Error('do not approve another exact representative on second chunk');
 if(!$('[data-compare-decide]').disabled)throw Error('zero selection generic action must be disabled');
 conflict=true;$('[data-compare-reject-all]').click();await flush();
 if(!$('[data-compare-error]').textContent.includes('100장은 처리 완료')||!$('[data-compare-error]').textContent.includes('처리 0건'))throw Error('completed chunks and failed chunk must be distinguished');conflict=false;
 $('[data-compare-reject-all]').click();await flush();post=requests.filter(x=>x.options.method==='POST').at(-1);body=JSON.parse(post.options.body);
 if(!post.url.endsWith('/reject-group')||body.selected||Object.keys(body.expected).length!==1)throw Error('explicit remaining chunk rejection');
 similarDone=false;similar[4].eligible=false;$('[data-section=groups]').click();await flush();$('[data-group] button').click();await flush();
 if(!$('[data-use-photo="305"]').disabled||$('[data-reference-photo="305"]')||!$('[data-compare-grid]').textContent.includes('반려 가능'))throw Error('restricted pending rejectable but not approvable');
 $('[data-use-photo="301"]').click();$('[data-use-photo="302"]').click();$('[data-compare-decide]').click();await flush();
 body=JSON.parse(requests.filter(x=>x.options.method==='POST').at(-1).options.body);
 if(Object.keys(body.expected).length!==5||body.selected.includes(305))throw Error('restricted pending included only in rejection');
 $('[data-workflow-status]').value='ALL';$('[data-workflow-status]').dispatchEvent(new Event('change'));await flush();
 const none=r.querySelector('[data-id="601"]');none.querySelector('button').click();
 if(!$('[data-detail-dialog]').open||$('[data-detail-actions]').querySelector('button'))throw Error('NONE has no moderation buttons');
 $('[data-detail-close]').click();r.querySelector('[data-id="604"] button').click();
 if(!$('[data-detail-actions]').textContent.includes('갤러리 순서'))throw Error('priority auxiliary control');$('[data-detail-close]').click();
 const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);if(overflow>1)throw Error('page overflow '+overflow);
 parent.postMessage({width:innerWidth,overflow,modalOverflow,requests:requests.length,confirmations:confirmations.length,errors},'*');
}catch(e){parent.postMessage({error:e.stack,errors},'*')}})();</script>`);
const runner=path.join(root,'workflow-browser.html');
fs.writeFileSync(runner,'<pre id="results"></pre><script>const rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length===6)document.querySelector("#results").textContent=JSON.stringify(rows)})</script>'+widths.map(w=>`<iframe style="width:${w}px;height:1800px" src="workflow-case.html"></iframe>`).join(''));
const output=execFileSync('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--allow-file-access-from-files','--no-first-run','--user-data-dir='+path.join(root,'workflow-chrome'),'--virtual-time-budget=10000','--dump-dom',pathToFileURL(runner).href],{encoding:'utf8',timeout:60000,maxBuffer:32*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1]);const rows=JSON.parse(match[1]);assert.equal(rows.length,6);
for(const row of rows){assert.ok(!row.error,JSON.stringify(row));assert.deepEqual(row.errors,[]);}
fs.writeFileSync(path.join(root,'workflow-browser-results.json'),JSON.stringify(rows,null,2));console.log('Clean bulk approval, grouped decisions, limits, conflict and responsive UI: 6 viewports passed');
