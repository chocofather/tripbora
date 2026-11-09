// Run after ReviewFormRenderingTest. Uses rendered Thymeleaf form and actual CSS/JS; no app or DB.
const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const {execFileSync} = require('node:child_process');
const {pathToFileURL} = require('node:url');
const root = path.resolve('build/reports/review-ui');
const source = path.resolve('src/main/resources/static');
const css = ['detail.css', 'comment.css', 'content-comment.css', 'destination-detail-mobile.css']
    .map(file => fs.readFileSync(path.join(source, 'css', file), 'utf8')).join('\n');
const script = ['uploadTerms.js', 'events.js', 'render.js'].map(file => fs.readFileSync(path.join(source, 'js/comment', file), 'utf8')
    .replace(/import\s+[\s\S]*?from\s+['"][^'"]+['"];\s*/g, '').replaceAll('export function ', 'function ')).join('\n');
const form = fs.readFileSync(path.join(root, 'form.html'), 'utf8');
const setup = `
const errors = [], sent = [];
window.addEventListener('error', e => { if(e.message) errors.push(e.message); });
window.alert = () => {};
window.fetch = async url => {
    if(!String(url).startsWith('/review-photo-terms'))throw Error('unexpected consent request '+url);
    return {ok:true,status:200,headers:new Headers({'Content-Type':'application/json'}),json:async()=>({available:true,policyVersionId:25,version:'20261106-v1',title:'사진 업로드 이용조건',contentHtml:'<p>서비스 내 이용</p>',locale:'ko',policyUrl:'/review-photo-terms'})};
};
function detailMessage(key, n) { return ({reviewRating:'별점',reviewUnscored:'평점 없음',commentSave:'저장',commentCancel:'취소',commentEdit:'수정',commentReply:'답글',commentDelete:'삭제',commentPhoto:'사진',commentImageAlt:'사진',commentFileRemove:'제거',commentModerated:'숨김 처리된 리뷰'})[key] || key; }
const postComment = async (id, data) => sent.push({action:'create', rating:data.get('rating'), photos:data.getAll('images').length,policyVersionId:data.get('photoUploadPolicyVersionId')});
const updateCommentWithImages = async (id, data) => sent.push({action:'edit', id, rating:data.get('rating'), photos:data.getAll('images').length,policyVersionId:data.get('photoUploadPolicyVersionId')});
const fetchOwnCommentImages = async () => [];
const fetchThumbnails = async () => [];
const fetchCommentTranslation = async () => ({});
const toggleLikeApi = async () => 'liked';
const deleteCommentApi = async () => ({ok:true});
const postReply = async () => {};
${script}
const base = {writer:{id:7,nickname:'여행자'}, createdAt:'2026-10-30T12:00:00',updatedAt:'2026-10-30T12:00:00',content:'방문 경험을 공유합니다. 편리하게 이용할 수 있었습니다.',likes:2,isLoggedIn:true,myComment:true,imageUrls:[]};
const list = document.getElementById('comment-list');
list.append(createCommentItem({...base,id:20,rating:4}),createCommentItem({...base,id:21,rating:null}),createCommentItem({...base,id:22,parentCommentId:20,rating:null},1));
initCommentForm('10', () => {}, () => {});
bindCommentActions(list, () => {}, () => {});
function check(condition,message){if(!condition)throw Error(message);}
const tick=()=>new Promise(resolve=>setTimeout(resolve,20));
window.addEventListener('load', async () => {
  try {
    const form=document.getElementById('comment-form');
    check(form.querySelectorAll('[name=rating]').length===5,'five create stars');
    form.querySelector('textarea').value='새 방문 경험';
    check(!form.checkValidity(),'rating required');
    form.querySelector('[name=rating][value="4"]').click();
    check(form.checkValidity(),'selected rating valid');
    const upload=form.querySelector('input[type=file]'); const dt=new DataTransfer();
    for(let i=0;i<5;i++)dt.items.add(new File(['photo'],i+'.png',{type:'image/png'}));
    upload.files=dt.files;upload.dispatchEvent(new Event('change')); 
    check(form.querySelectorAll('.comment-image-preview-item').length===5 || form.querySelectorAll('.comment-image-preview img').length===5,'five photo previews');
    const label=form.querySelector('.image-upload-label').getBoundingClientRect(),button=form.querySelector('.submit-btn').getBoundingClientRect();
    check(label.right<=button.left+1 && Math.abs(label.top-button.top)<8,'photo and submit stay right aligned');
    form.requestSubmit();await tick();
    check(sent[0].rating==='4'&&sent[0].photos===5,'create rating and photos submitted');
    check(sent[0].policyVersionId==='25','new photos carry the displayed upload terms');
    const rated=list.querySelector('[data-id="20"]');
    check(rated.querySelector('.review-rating-display').textContent.includes('4 / 5'),'rating displayed');
    rated.querySelector('.edit-btn').click();await tick();
    const edit=rated.querySelector('.edit-comment-form');
    check(edit.querySelector('[name=rating]:checked').value==='4','edit keeps rating');
    edit.querySelector('[name=rating][value="2"]').click();edit.querySelector('.save-edit-btn').click();await tick();
    check(sent[1].rating==='2','edited rating submitted');
    check(sent[1].policyVersionId===null,'text-only edit does not relicense existing photos');
    const legacy=list.querySelector('[data-id="21"]');check(!legacy.querySelector('.review-rating-display'),'legacy has no rating');
    legacy.querySelector('.edit-btn').click();await tick();
    check(legacy.querySelector('[name=rating]:checked').value==='','legacy remains unscored');
    legacy.querySelector('[name=rating][value="5"]').click();legacy.querySelector('.save-edit-btn').click();await tick();
    check(sent[2].rating==='5','legacy conversion submitted');
    const reply=list.querySelector('[data-id="22"]');reply.querySelector('.edit-btn').click();await tick();
    check(!reply.querySelector('[name=rating]'),'reply editor has no stars');
    const overflow=document.documentElement.scrollWidth-innerWidth;
    check(overflow<=1,'document horizontal overflow '+overflow);
    parent.postMessage({width:innerWidth,overflow,sent,errors},'*');
  } catch(error){parent.postMessage({width:innerWidth,error:error.stack,errors},'*');}
});`;
const page = `<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif}${css}</style><div class="destination-detail-page"><section class="comment-section content-comments"><h2>여행자 리뷰</h2>${form}<ul id="comment-list" class="comment-list content-comment-list"></ul></section></div><script>${setup}</script></html>`;
const widths = [360,390,393,430,768,1440];
fs.writeFileSync(path.join(root,'interaction.html'),page);
const fixture = path.join(root,'browser-fixture.html');
fs.writeFileSync(fixture,`<!doctype html><meta charset="utf-8"><pre id="results"></pre><script>const results=[];addEventListener('message',e=>{results.push(e.data);if(results.length===6)document.getElementById('results').textContent=JSON.stringify(results);});</script>`+widths.map(width=>`<iframe style="width:${width}px;height:1100px" src="interaction.html"></iframe>`).join(''));
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const output=execFileSync(chrome,['--headless=new','--disable-gpu','--disable-background-networking','--no-first-run','--no-default-browser-check','--allow-file-access-from-files',`--user-data-dir=${path.join(root,'chrome-profile')}`,'--virtual-time-budget=10000','--dump-dom',pathToFileURL(fixture).href],{encoding:'utf8',timeout:60000,maxBuffer:16*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1],'browser measurements completed');
const results=JSON.parse(match[1].replaceAll('&amp;','&').replaceAll('&lt;','<').replaceAll('&gt;','>'));
fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify(results,null,2));
for(const result of results){assert.ok(!result.error,JSON.stringify(result));assert.deepEqual(result.errors,[]);assert.ok(result.overflow<=1);}
console.log(JSON.stringify({widths:results.map(r=>r.width),maxOverflow:Math.max(...results.map(r=>r.overflow)),report:path.join(root,'browser-results.json')}));
