const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{pathToFileURL}=require('node:url');
const directory=path.resolve('build/reports/admin-review-destinations'),widths=[360,390,393,430,768,1440];
const html=fs.readFileSync(path.join(directory,'page.html'),'utf8');
const css=['admin-layout.css','review-image-management.css','admin-mobile.css'].map(f=>fs.readFileSync('src/main/resources/static/css/'+f,'utf8')).join('\n');
const script=fs.readFileSync('src/main/resources/static/js/admin-review-photo-analysis.js','utf8');
// ID는 사용자 증상과 같은 재현 fixture이며 실제 DB 행의 동의 상태를 가정하지 않는다.
fs.writeFileSync(path.join(directory,'eligibility-case.html'),`<!doctype html><meta charset="utf-8"><style>${css}body{margin:0}.admin-page-section{padding:12px}</style><div class="admin-shell" style="display:block">${html}</div><script>
const errors=[],requests=[],confirmations=[];addEventListener('error',e=>errors.push(e.message));
window.confirm=text=>{confirmations.push(text);return true};const root=document.querySelector('[data-photo-workflow]');root.dataset.section='groups';root.dataset.page='0';root.dataset.status='PENDING';
const picture='data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="%237657c8"/></svg>';
let mode='denied',conflict=false,failGet=false,forbidden=false,malformed=false;
const codes=['MEMBER_CONSENT_MISSING','REVIEW_HIDDEN','MEMBER_RESTRICTED','PHOTO_CONSENT_WITHDRAWN'];
const messages=['현재 정책에 대한 회원의 갤러리 활용 동의 없음','관리자 조치로 비공개된 리뷰','회원 상태 제한 (활성 회원 아님)','이 사진의 갤러리 활용 동의 철회'];
let rows=[];function reset() {rows=[8,11,14,19].map((id,i)=>({id,commentId:100+id,imageUrl:picture,nickname:'작성자',galleryStatus:mode==='mixed'&&i===0?'APPROVED':'PENDING',eligible:mode==='unknown'?null:mode!=='denied',eligibilityReason:mode==='denied'?codes[i]:null,eligibilityMessage:mode==='denied'?messages[i]:mode==='unknown'?'심사 자격을 확인할 수 없습니다.':null}));
 if(mode==='pair'||mode==='mixed-reference'){
  const deleted={...rows[0],id:6,galleryStatus:'PENDING',eligible:false,eligibilityReason:'REVIEW_DELETED',eligibilityMessage:'삭제된 리뷰'};
  const none={...rows[0],id:25,galleryStatus:'NONE',eligible:false,eligibilityReason:'PHOTO_CONSENT_MISSING',eligibilityMessage:'사진의 갤러리 활용 동의 기록 없음'};
  const restrictedApproved={...deleted,id:40,galleryStatus:'APPROVED'};
  rows=mode==='pair'?[deleted,none]:[...rows.slice(0,2),deleted,none,restrictedApproved];
 }
 if(mode==='readonly')rows.forEach((p,i)=>{p.galleryStatus=['NONE','REJECTED','REVOKED','NONE'][i];p.eligible=false;p.eligibilityMessage='이미 처리되었거나 활용 미동의';});
}reset();
const reviewable=p=>['PENDING','APPROVED'].includes(p.galleryStatus);
window.fetch=async(url,options={})=>{
 requests.push({url,options});
 if(options.method==='POST'){
  if(forbidden)return {ok:false,status:403,json:async()=>null};
  if(conflict)return {ok:false,status:409,json:async()=>({message:'처리 0건 · 사진 #11: 동의 철회. 새로고침 후 확인하세요.'})};
  const body=JSON.parse(options.body);rows.forEach(p=>{if(!Object.hasOwn(body.expected,p.id))return;p.galleryStatus=body.selected?.includes(p.id)?'APPROVED':p.galleryStatus==='APPROVED'?'REVOKED':'REJECTED';});
  return {ok:true,json:async()=>({message:'묶음 전체 처리 완료. 원본 리뷰 사진은 유지됩니다.'})};
 }
 const pending=rows.filter(p=>p.galleryStatus==='PENDING').length,approved=rows.filter(p=>p.galleryStatus==='APPROVED').length;
 if(url.includes('/comparison')) {
  if(malformed)return {ok:true,json:async()=>({photos:rows,counts:null})};
  if(failGet)return {ok:false,status:409,json:async()=>({message:'그룹 상태가 변경되었습니다. 다시 열어 주세요.'})};
  return {ok:true,json:async()=>({photos:rows,hasNext:false,counts:{total:rows.length,approved,reviewableApproved:rows.filter(p=>p.eligible&&p.galleryStatus==='APPROVED').length,pending,rejected:rows.filter(p=>p.galleryStatus==='REJECTED').length,revoked:rows.filter(p=>p.galleryStatus==='REVOKED').length}})};
 }
 const parameters=new URL(url,location.href).searchParams,section=parameters.get('section'),eligible=rows.filter(reviewable),matching=eligible.filter(p=>parameters.get('status')==='ALL'||p.galleryStatus===parameters.get('status'));
 return {ok:true,json:async()=>({counts:{clean:0,groups:matching.length,limited:rows.length-eligible.length},photos:section==='limited'?rows.filter(p=>!reviewable(p)).map(p=>({...p,exactCount:rows.length-1})):[],groups:section==='groups'&&matching.length?[{anchorId:rows[0].id,kind:'exact',key:'fixture:four',total:rows.length,pending,approved,reviewable:eligible.length,photos:rows}]:[],hasNext:false})};
};
${script}
(async()=>{try {
 const $=s=>root.querySelector(s),flush=async()=>{await new Promise(r=>setTimeout(r,0));await new Promise(r=>setTimeout(r,0));};await flush();
 const open=async()=>{const canReview=rows.some(reviewable);$('[data-section='+ (canReview?'groups':'limited')+']').click();await flush();if(canReview)$('[data-group] button').click();else $('[data-workflow-grid] .review-workflow-caption button').click();await flush();};
 await open();const modal=$('[data-compare-dialog]'),action=$('[data-compare-decide]');
 if(modal.querySelectorAll('[data-use-photo]:disabled').length!==4||modal.querySelectorAll('[data-reference-photo]').length!==0||!action.disabled||action.hidden||action.getAttribute('aria-disabled')!=='true'||!$('[data-compare-controls]').hidden||$('[data-compare-reject-all]').disabled)throw Error('restricted four rejectable state');
 if(!$('[data-compare-blocked]').textContent.includes('0장')||$('[data-compare-blocked]').hidden||getComputedStyle(action).cursor!=='not-allowed')throw Error('disabled reason/style');
 for(const text of messages)if(!modal.textContent.includes(text))throw Error('missing concrete reason '+text);
 const posts=()=>requests.filter(r=>r.options.method==='POST');action.click();if(posts().length)throw Error('restricted photos approved');
 $('[data-compare-reject-all]').click();await flush();if(rows.some(p=>p.galleryStatus!=='REJECTED')||!posts().at(-1).url.endsWith('/reject-group'))throw Error('restricted PENDING can be explicitly rejected');
 $('[data-compare-close]').click();mode='valid';reset();await open();
 if(action.disabled||modal.querySelectorAll('[data-use-photo]:checked').length!==1)throw Error('consented four should be selectable');
 const first=$('[data-use-photo="8"]'),card=$('[data-use-card="8"]');first.focus();
 if(first.type!=='checkbox'||!first.getAttribute('aria-label')||first.labels.length!==1||document.activeElement!==first)throw Error('accessible native checkbox');
 card.click();if(first.checked||card.classList.contains('is-selected'))throw Error('card deselection');
 card.querySelector('label').click();if(!first.checked||!card.classList.contains('is-selected'))throw Error('label selection once');
 const selectedBefore=[...modal.querySelectorAll('[data-use-photo]:checked')].map(p=>p.dataset.usePhoto).join(',');
 card.querySelector('button').click();if(!$('[data-detail-dialog]').open)throw Error('separate enlargement');$('[data-detail-close]').click();
 if([...modal.querySelectorAll('[data-use-photo]:checked')].map(p=>p.dataset.usePhoto).join(',')!==selectedBefore)throw Error('enlargement must not alter selection');
 $('[data-use-photo="8"]').click();$('[data-use-photo="11"]').click();conflict=true;action.click();await flush();
 if(!modal.open||!$('[data-compare-error]').textContent.includes('동의 철회'))throw Error('API conflict must be visible');
 const request=posts().at(-1),payload=JSON.parse(request.options.body);
 if(payload.selected.length!==1||payload.selected[0]!==11||Object.keys(payload.expected).join(',')!=='8,11,14,19'||!request.options.headers['X-CSRF-TOKEN'])throw Error('selection / snapshot / CSRF');
 conflict=false;action.click();await flush();
 if(rows.filter(p=>p.galleryStatus==='APPROVED').length!==1||rows.filter(p=>p.galleryStatus==='REJECTED').length!==3||modal.open||$('[data-count=groups]').textContent!=='0장')throw Error('approve1 reject3 and modal/stats refresh');
 mode='mixed';reset();await open();if(!modal.textContent.includes('이미 승인됨'))throw Error('approved highlight');
 $('[data-use-none]').click();$('[data-use-photo="11"]').click();action.click();await flush();if(rows[0].galleryStatus!=='REVOKED')throw Error('excluded approved uses revoke');
 mode='pair';reset();await open();
 if($('[data-compare-reviewable-section]').hidden||$('[data-compare-reference-section]').hidden||!$('[data-compare-empty]').hidden||!modal.textContent.includes('현재 승인 가능한 사진은 없습니다'))throw Error('deleted6 pending rejectable / NONE25 reference layout');
 if(!$('[data-use-photo="6"]').disabled||!action.disabled||!$('[data-compare-controls]').hidden||!modal.textContent.includes('삭제된 리뷰')||!modal.textContent.includes('미동의'))throw Error('restriction reasons/controls');
 $('[data-use-card="6"] button').click();if($('[data-manual-actions]').hidden||!$('[data-detail-actions]').textContent.includes('개별 반려')||$('[data-detail-actions]').textContent.includes('개별 승인'))throw Error('deleted pending detail allows rejection only');$('[data-detail-close]').click();
 $('[data-reference-photo="25"] button').click();if(!$('[data-manual-actions]').hidden||$('[data-detail-actions]').querySelector('button'))throw Error('NONE reference has no moderation');$('[data-detail-close]').click();
 $('[data-compare-close]').click();if(modal.open)throw Error('back to list');
 mode='mixed-reference';reset();await open();
 if(modal.querySelectorAll('[data-use-photo]').length!==4||modal.querySelectorAll('[data-reference-photo]').length!==1)throw Error('separate actionable and reference areas');
 if(modal.querySelectorAll('[data-use-photo]:checked').length!==1)throw Error('invalid approved reference must not suppress representative recommendation');
 action.click();await flush();const mixedBody=JSON.parse(posts().at(-1).options.body);
 if(Object.keys(mixedBody.expected).join(',')!=='6,8,11,40'||rows.find(p=>p.id===6).galleryStatus!=='REJECTED'||rows.find(p=>p.id===25).galleryStatus!=='NONE'||rows.find(p=>p.id===40).galleryStatus!=='REVOKED')throw Error('pending rejection / approved revoke / NONE unchanged');
 mode='valid';reset();await open();$('[data-use-none]').click();
 if(!action.disabled||!action.textContent.includes('선택 0장')||!$('[data-compare-blocked]').textContent.includes('그룹 전체 반려'))throw Error('zero selection explicit outcome');
 $('[data-compare-reject-all]').click();await flush();if(JSON.parse(posts().at(-1).options.body).selected||rows.some(p=>p.galleryStatus!=='REJECTED'))throw Error('explicit group rejection');
 mode='mixed';reset();await open();$('[data-use-none]').click();$('[data-compare-reject-all]').click();await flush();if(rows[0].galleryStatus!=='APPROVED'||rows.slice(1).some(p=>p.galleryStatus!=='REJECTED'))throw Error('whole rejection preserves existing APPROVED');
 mode='unknown';reset();await open();if(!action.disabled||$('[data-compare-reject-all]').disabled||!modal.textContent.includes('심사 자격을 확인할 수 없습니다'))throw Error('unknown eligibility blocks approval, not rejection');$('[data-compare-close]').click();
 mode='readonly';reset();await open();if(!$('[data-compare-reviewable-section]').hidden||$('[data-compare-empty]').hidden||!$('[data-compare-decision]').hidden||modal.querySelectorAll('[data-reference-photo]').length!==4)throw Error('zero actionable photos reference-only guidance');$('[data-compare-back]').click();
 mode='valid';reset();await open();forbidden=true;action.click();await flush();if(!$('[data-compare-error]').textContent.includes('접근 권한'))throw Error('empty 403 must explain authorization');forbidden=false;$('[data-compare-close]').click();
 malformed=true;await open();$('[data-use-all]').click();if(!action.disabled||!$('[data-compare-error]').textContent.includes('비교 응답'))throw Error('malformed success must remain disabled');$('[data-compare-close]').click();malformed=false;
 failGet=true;await open();if(!action.disabled||!$('[data-compare-error]').textContent.includes('그룹 상태가 변경'))throw Error('GET errors must be visible');
 const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth),modalOverflow=modal.scrollWidth-modal.clientWidth;
 if(overflow>1||modalOverflow>1)throw Error('overflow');
 parent.postMessage({width:innerWidth,overflow,modalOverflow,posts:posts().length,confirmations:confirmations.length,errors},'*');
}catch(e){parent.postMessage({error:e.stack,errors},'*')}})();</script>`);
const runner=path.join(directory,'eligibility-browser.html');
fs.writeFileSync(runner,'<pre id="results"></pre><script>const rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length===6)document.querySelector("#results").textContent=JSON.stringify(rows)})</script>'+widths.map(w=>`<iframe style="width:${w}px;height:1800px" src="eligibility-case.html"></iframe>`).join(''));
const output=execFileSync('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--allow-file-access-from-files','--no-first-run','--user-data-dir='+path.join(directory,'eligibility-chrome'),'--virtual-time-budget=10000','--dump-dom',pathToFileURL(runner).href],{encoding:'utf8',timeout:60000,maxBuffer:32*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1]);const results=JSON.parse(match[1]);assert.equal(results.length,6);
for(const result of results){assert.ok(!result.error,JSON.stringify(result));assert.deepEqual(result.errors,[]);}
fs.writeFileSync(path.join(directory,'eligibility-browser-results.json'),JSON.stringify(results,null,2));console.log('Eligibility reasons, four-photo decision, disabled state, conflict and responsive modal: 6 viewports passed');
