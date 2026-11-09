const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict'),{execFileSync}=require('node:child_process'),{pathToFileURL}=require('node:url');
const root=path.resolve('build/reports/admin-review-destinations'),widths=[360,390,393,430,768,1440];
const css=['admin-layout.css','review-image-management.css','admin-mobile.css'].map(f=>fs.readFileSync('src/main/resources/static/css/'+f,'utf8')).join('\n');
const html=fs.readFileSync(path.join(root,'list.html'),'utf8'),runner=path.join(root,'list-browser.html');
fs.writeFileSync(path.join(root,'list-case.html'),`<!doctype html><meta charset="utf-8"><style>${css}body{margin:0}.admin-page-section{padding:12px}</style><div class="admin-shell" style="display:block">${html}</div><script>
const errors=[];addEventListener('error',e=>errors.push(e.message));
parent.postMessage({width:innerWidth,overflow:Math.max(0,document.documentElement.scrollWidth-innerWidth),errors,active:document.querySelector('.review-management-tabs [aria-current=page]').textContent,detail:document.querySelector('.review-destination-row h2 a').getAttribute('href')},'*');</script>`);
fs.writeFileSync(runner,'<pre id="results"></pre><script>const rows=[];addEventListener("message",e=>{rows.push(e.data);if(rows.length===6)document.querySelector("#results").textContent=JSON.stringify(rows)})</script>'+widths.map(w=>`<iframe style="width:${w}px;height:1400px" src="list-case.html"></iframe>`).join(''));
const output=execFileSync('C:/Program Files/Google/Chrome/Application/chrome.exe',['--headless=new','--disable-gpu','--allow-file-access-from-files','--no-first-run','--user-data-dir='+path.join(root,'list-chrome'),'--virtual-time-budget=10000','--dump-dom',pathToFileURL(runner).href],{encoding:'utf8',timeout:60000,maxBuffer:32*1024*1024,stdio:['ignore','pipe','pipe']});
const match=output.match(/<pre id="results">([\s\S]*?)<\/pre>/);assert.ok(match&&match[1]);const rows=JSON.parse(match[1]);assert.equal(rows.length,6);
for(const row of rows){assert.equal(row.overflow,0,JSON.stringify(row));assert.deepEqual(row.errors,[]);assert.equal(row.active,'여행지별 관리');assert.equal(row.detail,'/admin/review-images/destinations/10');}
fs.writeFileSync(path.join(root,'list-browser-results.json'),JSON.stringify(rows,null,2));console.log('Destination list: 6 widths, no overflow or script errors');
