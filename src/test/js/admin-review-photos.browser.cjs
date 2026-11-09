const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{pathToFileURL}=require('node:url');
const root=path.resolve(process.env.REVIEW_PHOTO_FIXTURE || 'build/reports/admin-review-queue');
const html=fs.readFileSync(path.join(root,'page.html'),'utf8');
const css=['admin-layout.css','review-image-management.css','admin-mobile.css'].map(f=>fs.readFileSync('src/main/resources/static/css/'+f,'utf8')).join('\n');
const script=fs.readFileSync('src/main/resources/static/js/admin-review-photos.js','utf8');
for(let n=1;n<=6;n++){
const page=`<!doctype html><meta charset="utf-8"><style>${css}body{margin:0}.admin-page-section{padding:12px}</style><div class="admin-shell" style="display:block">${html}</div><script>
const errors=[];addEventListener('error',e=>errors.push(e.message));const saved=[],targets=[];HTMLFormElement.prototype.submit=function(){targets.push(this.getAttribute('action'));saved.push([...new FormData(this)])};window.confirm=()=>true;
const cards=document.querySelector('.review-image-admin-list'),first=cards.firstElementChild;
const all=[...first.querySelectorAll('.review-queue-photo')];all.slice(${n}).forEach(p=>p.remove());
if(${n}===3){const clone=first.cloneNode(true);clone.querySelectorAll('[name=ids]').forEach(i=>i.value=Number(i.value)+100);cards.append(clone);}
document.querySelectorAll('[data-enlarge] img').forEach(img=>{img.src='data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect width="100" height="100" fill="%237657c8"/></svg>';img.closest('button').dataset.url=img.src});
// 브라우저가 이전 페이지의 체크 상태를 복원한 상황도 초기화해야 한다.
document.querySelector('[name=ids]').checked=true;
document.querySelector('[data-select-pending]').checked=true;
if(${n}===6)first.querySelector('[data-status]').dataset.approvable='false';
${script}
try{
const root=document.querySelector('[data-photo-queue]'),form=root.querySelector('[data-batch-form]');
if(form.querySelector('input[name=ids]:checked')||root.querySelector('[data-select-pending]').checked)throw Error('stale selection');
document.querySelector('[data-enlarge]').click();if(!document.querySelector('[data-photo-dialog]').open)throw Error('enlarge');document.querySelector('[data-photo-close]').click();
let expected;
if(${n}===1){expected=['1'];first.querySelector('[data-single]').click();}
if(${n}===2){expected=['1','2'];first.querySelector('[data-approve-card]').click();}
if(${n}===3){expected=['1','101'];for(const id of expected){const box=document.querySelector('[name=ids][value="'+id+'"]');box.checked=true;box.dispatchEvent(new Event('change'))}if(document.querySelector('[data-selected-count]').textContent!=='2')throw Error('selection count');form.querySelector('button[value=APPROVE]').click();}
if(${n}===4){expected=['1','2','3'];const toggle=root.querySelector('[data-select-pending]');toggle.checked=true;toggle.dispatchEvent(new Event('change'));form.querySelector('button[value=REJECT]').click();if(!root.querySelector('[data-reject-dialog]').open||saved.length)throw Error('reject dialog');root.querySelector('[data-reason]').value='권리 확인 필요';root.querySelector('[data-reject-confirm]').click();}
if(${n}===5){expected=['4'];form.querySelector('[name=ids][value="4"]').checked=true;form.querySelector('button[value=REVOKE]').click();}
if(${n}===6){expected=['1'];const box=form.querySelector('[name=ids][value="1"]');box.checked=true;box.dispatchEvent(new Event('change'));if(!form.querySelector('[data-approve-selected]').disabled||!root.querySelector('[data-approval-hint]').textContent.includes('1장'))throw Error('restricted approval must explain');form.querySelector('button[value=REJECT]').click();root.querySelector('[data-reason]').value='활용 범위 확인 불가';root.querySelector('[data-reject-confirm]').click();}
if(saved.length!==1)throw Error('one submission');if(targets[0]!==form.getAttribute('action'))throw Error('submit target');if(!saved[0].some(p=>p[0]==='_csrf'))throw Error('csrf preserved');const actual=saved[0].filter(p=>p[0]==='ids').map(p=>p[1]);if(JSON.stringify(actual)!==JSON.stringify(expected))throw Error('explicit ids '+actual);
form.querySelector('button[value=APPROVE]').click();if(saved.length!==1)throw Error('duplicate request');
const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);if(overflow>1)throw Error('overflow '+overflow);parent.postMessage({n:${n},width:innerWidth,overflow,errors},'*');
}catch(e){parent.postMessage({error:e.stack,errors},'*')}
</script>`;
fs.writeFileSync(path.join(root,'case-'+n+'.html'),page);
}
const widths=[360,390,393,430,768,1440],fixture=path.join(root,'browser.html');
fs.writeFileSync(fixture,'<pre id="results"></pre><script>let rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length===36)document.getElementById("results").textContent=JSON.stringify(rows)})</script>'+widths.flatMap(w=>[1,2,3,4,5,6].map(n=>'<iframe style="width:'+w+'px;height:1400px" src="case-'+n+'.html"></iframe>')).join(''));
const output=execFileSync('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--allow-file-access-from-files','--no-first-run','--user-data-dir='+path.join(root,'chrome'),'--virtual-time-budget=10000','--dump-dom',pathToFileURL(fixture).href],{encoding:'utf8',timeout:60000,maxBuffer:32*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1],'results complete');const rows=JSON.parse(match[1].replaceAll('&amp;','&').replaceAll('&gt;','>').replaceAll('&lt;','<'));for(const row of rows){assert.ok(!row.error,JSON.stringify(row));assert.deepEqual(row.errors,[])}fs.writeFileSync(path.join(root,'browser-results.json'),JSON.stringify(rows,null,2));console.log(JSON.stringify({cases:rows.length,maxOverflow:Math.max(...rows.map(r=>r.overflow))}));
