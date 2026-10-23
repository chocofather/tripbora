// Actual Spring-rendered HTML/hashed assets with a local HTTP mock. No DEV server or DB.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const project = path.resolve(__dirname, '../../..');
const fixtures = path.join(project, 'build/reports/destination-json-update');
const chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

test('automatic JSON update flow, one candidate confirmation, final validation and safe error recovery',
  {skip: !fs.existsSync(chrome), timeout: 60000}, async t => {
    const previews = [], applies = [], statuses = [], plans = [], assets = [], tickets = new Map();
    let nextPreview = null, nextApply = null, held = null;
    const changes = [
      {path:'translations.ko.shortDescription',label:'한 줄 소개',kind:'REPLACE',beforeText:'현재 소개',afterText:'새 소개'},
      {path:'translations.ko.description',label:'상세 설명',kind:'REPLACE',beforeText:'현재 본문',afterText:'새 본문 '.repeat(120)},
      {path:'translations.en.name',label:'영어 이름',kind:'REPLACE',beforeText:'Old name',afterText:'New name'},
      {path:'translations.en.shortDescription',label:'영어 소개',kind:'AUTO_ADD',beforeText:'(없음)',afterText:'New intro'},
      {path:'season',label:'계절',kind:'UNCHANGED',beforeText:'사계절',afterText:'사계절'},
      {path:'categories',label:'카테고리',kind:'REPLACE',beforeText:'A, B',afterText:'A, B, C'}
    ];
    function preview(body) {
      const count = JSON.parse(body.json).destinations.length;
      return {previewToken:`snapshot-${previews.length}`,fileErrors:[],rows:Array.from({length:count},(_,index)=>{
        const selected=body.selections?.find(row=>row.index===index);
        const matched=index===0 || (selected?.destinationId===202 && selected.confirmedMatch);
        const token=matched?`ticket-${previews.length}-${index}`:null;
        const fields=selected?.fields ?? changes.filter(c=>c.kind==='AUTO_ADD').map(c=>c.path);
        if(token) tickets.set(token,{index,fields,replaceRelations:Boolean(selected?.replaceRelations)});
        return {index,key:`fixture-${index}`,name:`검증 여행지 ${index+1}`,status:matched?'MATCHED':'NEEDS_MATCH',
          message:matched?'대상과 선택 항목을 확인했습니다.':'유사 후보를 확인하세요.',destinationId:matched?(index===0?101:202):null,
          contentId:index===0?'126508':null,candidates:index===1?[{destinationId:202,name:'동일 장소 후보',message:'이름·위치 일치'}]:[],
          changes:matched?changes.map(c=>({...c,afterText:c.path==='categories'&&selected?.replaceRelations?'C':c.afterText,
            removedCount:c.path==='categories'&&selected?.replaceRelations?2:0,selected:fields.includes(c.path)&&c.kind!=='UNCHANGED',changed:c.kind!=='UNCHANGED'})):[],
          token,warnings:index===0?['출처와 기존 번역을 확인하세요.']:[],evidence:[]};
      })};
    }
    const server=http.createServer(async(req,res)=>{
      const url=new URL(req.url,'http://localhost');
      if(url.pathname.startsWith('/admin/api/destinations/import-json/update/')) {
        let raw='';for await(const chunk of req)raw+=chunk;const body=JSON.parse(raw);
        if(url.pathname.endsWith('/preview')) {
          previews.push(body);const action=nextPreview;nextPreview=null;
          if(action==='hold'){held={res,body};return;}
          if(action==='500'){res.writeHead(500,{'Content-Type':'text/html'});res.end('<html>server error</html>');return;}
          res.writeHead(200,{'Content-Type':'application/json'});
          res.end(JSON.stringify(action==='expired'?{fileErrors:[{path:'previewToken',message:'미리보기가 만료되었습니다.'}],rows:[]}:preview(body)));return;
        }
        if(url.pathname.endsWith('/status')) {
          statuses.push(body);res.writeHead(200,{'Content-Type':'application/json'});res.end(JSON.stringify({status:'UPDATED',message:'저장 완료 확인',destinationId:101,changedFields:1}));return;
        }
        applies.push(body);const plan=tickets.get(body.token);tickets.delete(body.token);if(plan)plans.push(plan);
        const action=nextApply;nextApply=null;
        res.writeHead(200,{'Content-Type':'application/json'});
        if(action==='lost'){res.write('{"status":');setTimeout(()=>res.destroy(),20);return;}
        res.end(JSON.stringify({status:plan?(action||'UPDATED'):'INVALID',message:'처리 결과',destinationId:101,changedFields:plan?.fields.length||0}));return;
      }
      if(url.pathname==='/') {
        let html=fs.readFileSync(path.join(fixtures,'page.html'),'utf8').replace(/<link\b[^>]*href="https:\/\/[^>]*>/g,'');
        html=html.replace('<head>',`<head><script>
          window.fixtureErrors=[];window.fixtureConfirmations=[];
          addEventListener('error',e=>{if(e.message)fixtureErrors.push(e.message)});
          addEventListener('unhandledrejection',e=>fixtureErrors.push(String(e.reason)));
          window.confirm=message=>{fixtureConfirmations.push(message);return true;};
          window.q=s=>document.querySelector(s);
          window.fixtureWait=async predicate=>{for(let i=0;i<250;i++){if(predicate())return;await new Promise(r=>setTimeout(r,20));}throw Error('UI wait timed out');};
          window.startUpload=count=>{
            q('[name="json-mode"][value="update"]').click();
            const data={version:1,destinations:Array.from({length:count},(_,i)=>({key:'fixture-'+i,...(i===0?{external:{tourApiContentId:'126508'}}:{})}))};
            const control=q('[data-update-file]');Object.defineProperty(control,'files',{value:[new File([JSON.stringify(data)],'test.json',{type:'application/json'})],configurable:true});
            control.dispatchEvent(new Event('change',{bubbles:true}));
          };
          window.upload=async count=>{startUpload(count);await fixtureWait(()=>document.querySelectorAll('.admin-json-update-row').length===count&&!q('[data-update-preview]').disabled&&q('[data-update-status]').textContent.includes('분석했습니다'));};
          window.field=(index,text)=>[...document.querySelectorAll('.admin-json-update-row')[index].querySelectorAll('.admin-json-update-change label')].find(label=>label.textContent.includes(text)).querySelector('input');
          window.save=async()=>{q('[data-update-apply]').click();await fixtureWait(()=>!q('[data-update-preview]').disabled&&q('[data-update-status]').textContent.includes('처리가 끝났습니다'));};
        </script>`);
        res.writeHead(200,{'Content-Type':'text/html; charset=utf-8'});res.end(html);return;
      }
      let file=path.resolve(fixtures,'assets','.'+url.pathname);
      if(!file.startsWith(path.resolve(fixtures,'assets')+path.sep)){res.writeHead(400);res.end();return;}
      if(!fs.existsSync(file)&&/^\/(images|fonts)\//.test(url.pathname)) file=path.join(project,'src/main/resources/static',url.pathname.replace(/-[a-f0-9]{32}(?=\.)/,''));
      const mime={'.css':'text/css','.js':'text/javascript','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png','.jpg':'image/jpeg','.ico':'image/x-icon'}[path.extname(file)]||'application/octet-stream';
      if(fs.existsSync(file)&&fs.statSync(file).isFile()){assets.push({url:url.pathname,status:200,mime});res.writeHead(200,{'Content-Type':mime});res.end(fs.readFileSync(file));return;}
      if(url.pathname==='/favicon.ico'){res.writeHead(204);res.end();return;}
      assets.push({url:url.pathname,status:404});res.writeHead(404);res.end();
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>server.close());
    const profile=fs.mkdtempSync(path.join(os.tmpdir(),'tripbora-json-update-'));
    const browser=spawn(chrome,['--headless=new','--no-first-run','--no-default-browser-check','--disable-gpu','--remote-debugging-pipe',`--user-data-dir=${profile}`],{stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
    let sequence=0,buffer='',session,loadComplete;const pending=new Map();
    browser.stdio[4].on('data',chunk=>{buffer+=chunk;let at;while((at=buffer.indexOf('\0'))>=0){const raw=buffer.slice(0,at);buffer=buffer.slice(at+1);if(!raw)continue;const message=JSON.parse(raw),task=pending.get(message.id);if(task){pending.delete(message.id);message.error?task.reject(Error(JSON.stringify(message.error))):task.resolve(message.result);}if(message.method==='Page.loadEventFired')loadComplete?.();}});
    const send=(method,params={},target=session)=>new Promise((resolve,reject)=>{const id=++sequence;pending.set(id,{resolve,reject});browser.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');});
    browser.on('exit',()=>{pending.forEach(task=>task.reject(Error('Chrome exited')));pending.clear();});
    t.after(async()=>{browser.kill();const target=path.resolve(profile);assert.ok(target.startsWith(path.resolve(os.tmpdir())+path.sep+'tripbora-json-update-'));for(let i=0;i<10;i++){try{fs.rmSync(target,{recursive:true,force:true});break;}catch{await new Promise(r=>setTimeout(r,100));}}});
    const target=await send('Target.createTarget',{url:'about:blank'},null);session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;await send('Page.enable');
    async function evaluate(expression) {
      const result=await send('Runtime.evaluate',{expression,awaitPromise:true,returnByValue:true});
      if(result.exceptionDetails){const detail=await send('Runtime.evaluate',{expression:`JSON.stringify({status:q('[data-update-status]')?.textContent,errors:fixtureErrors,rows:document.querySelectorAll('.admin-json-update-row').length})`,returnByValue:true});t.diagnostic(detail.result.value);}
      assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));return result.result.value;
    }
    async function navigate(width) {await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});const loaded=new Promise(resolve=>{loadComplete=resolve;});await send('Page.navigate',{url:`http://127.0.0.1:${server.address().port}/`});await loaded;}
    for(const width of [360,390,430,768,1440]) {
      await navigate(width);const before=previews.length,beforeWrites=applies.length;
      const state=await evaluate(`(async()=>{
        await upload(1);window.firstRow=q('.admin-json-update-row');
        const before=q('[data-update-summary]').textContent;field(0,'한국어 원문 · 한 줄 소개').click();
        return {overflow:document.documentElement.scrollWidth>innerWidth,errors:fixtureErrors,rawClosed:!q('.admin-json-update-source').open,
          auto:before.includes('선택 자동 보강 1개'),replaced:q('[data-update-summary]').textContent.includes('선택 교체 1개'),
          body:field(0,'한국어 원문 · 상세 설명').checked,sameRow:firstRow===q('.admin-json-update-row'),enabled:!q('[data-update-apply]').disabled,
          noRowCheck:!q('.admin-json-update-row-head input'),bulkHidden:q('[data-update-select-all]').hidden,
          replaceDefault:[...q('.admin-json-update-row').querySelectorAll('label')].find(e=>e.textContent==='JSON 목록으로 교체(제거 허용)').querySelector('input').checked};
      })()`);
      assert.equal(previews.length,before+1,'one upload performs one preview; field checkbox does not request preview');
      assert.equal(applies.length,beforeWrites,'upload and field selection never save');
      assert.equal(state.overflow,false);assert.deepEqual(state.errors,[]);assert.equal(state.rawClosed,true);assert.equal(state.auto,true);assert.equal(state.replaced,true);
      assert.equal(state.body,false);assert.equal(state.sameRow,true);assert.equal(state.enabled,true);assert.equal(state.noRowCheck,true);assert.equal(state.bulkHidden,true);assert.equal(state.replaceDefault,false);
      if([390,1440].includes(width)) {
        const screenshot=await send('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});
        fs.writeFileSync(path.join(fixtures,`simple-flow-${width}.png`),Buffer.from(screenshot.data,'base64'));
      }
      const beforeSave=applies.length;await evaluate('save()');
      assert.equal(applies.length,beforeSave+1);assert.equal(previews.length,before+2,'final click automatically validates changed selection');
      assert.deepEqual([...plans.at(-1).fields].sort(),['translations.en.shortDescription','translations.ko.shortDescription']);
      assert.match(previews.at(-1).previewToken,/^snapshot-/);assert.equal(await evaluate('fixtureConfirmations.length'),1);
      t.diagnostic(`${width}px: automatic one-file preview, no repeated preview click, independent fields, no overflow/script errors`);
    }
    const furtherEdit=await evaluate(`(()=>{field(0,'한국어 원문 · 상세 설명').click();return !q('[data-update-apply]').disabled;})()`);
    assert.equal(furtherEdit,true,'single destination remains automatically included when selecting another field after a completed save');
    await navigate(1440);await evaluate('upload(2)');const beforeMatch=previews.length;
    const matched=await evaluate(`(async()=>{window.firstRow=q('.admin-json-update-row');q('[data-candidate-id="202"]').click();await fixtureWait(()=>!q('[data-update-preview]').disabled&&!q('[data-candidate-id]'));return {checks:[...document.querySelectorAll('.admin-json-update-row-head input')].map(e=>e.checked),confirmations:fixtureConfirmations.length,same:firstRow===q('.admin-json-update-row')};})()`);
    assert.equal(previews.length,beforeMatch+1);assert.deepEqual(matched.checks,[true,true]);assert.equal(matched.confirmations,0);assert.equal(matched.same,true);
    assert.equal(previews.at(-1).selections[1].confirmedMatch,true);assert.equal(previews.at(-1).selections[1].fields,null);
    const bulk=await evaluate(`(()=>{q('[data-update-clear]').click();const cleared=q('[data-update-apply]').disabled;q('[data-update-select-all]').click();return {cleared,checked:[...document.querySelectorAll('.admin-json-update-row-head input')].map(e=>e.checked),visible:!q('[data-update-select-all]').hidden};})()`);
    assert.equal(bulk.cleared,true);assert.deepEqual(bulk.checked,[true,true]);assert.equal(bulk.visible,true);
    await evaluate(`field(1,'한국어 원문 · 상세 설명').click();field(0,'카테고리').click();[...q('.admin-json-update-row').querySelectorAll('label')].find(e=>e.textContent==='JSON 목록으로 교체(제거 허용)').querySelector('input').click();`);
    await evaluate(`fixtureWait(()=>!q('[data-update-preview]').disabled)`);
    const batchBefore=applies.length;await evaluate('save()');assert.equal(applies.length,batchBefore+2);
    assert.match(await evaluate('fixtureConfirmations.at(-1)'),/기존 목록 제거 2개/);
    assert.equal(plans.at(-1).fields.includes('translations.ko.description'),true);assert.equal(plans.at(-1).fields.includes('translations.ko.shortDescription'),false);

    // A delayed upload preview stays read-only and prevents duplicate clicks.
    nextPreview='hold';const delayedBefore=previews.length;
    await evaluate(`startUpload(1);fixtureWait(()=>q('[data-update-status]').textContent.includes('경과 1초'))`);
    await evaluate(`q('[data-update-preview]').dispatchEvent(new Event('click'));q('[data-update-preview]').dispatchEvent(new Event('click'));`);
    assert.equal(previews.length,delayedBefore+1);assert.ok(held);
    held.res.writeHead(200,{'Content-Type':'application/json'});held.res.end(JSON.stringify(preview(held.body)));held=null;
    await evaluate(`fixtureWait(()=>!q('[data-update-preview]').disabled)`);
    nextPreview='500';await evaluate(`q('[data-update-preview]').click();fixtureWait(()=>!q('[data-update-preview]').disabled)`);
    assert.match(await evaluate(`q('[data-update-status]').textContent`),/500/);
    await evaluate('upload(1)');await evaluate(`field(0,'한국어 원문 · 한 줄 소개').click()`);
    nextPreview='expired';const beforeExpirySave=applies.length;
    await evaluate(`q('[data-update-apply]').click();fixtureWait(()=>!q('[data-update-preview]').disabled)`);
    assert.equal(applies.length,beforeExpirySave);assert.match(await evaluate(`q('[data-update-errors]').textContent`),/만료/);
    await evaluate('save()');assert.equal(applies.length,beforeExpirySave+1);

    await evaluate('upload(1)');nextApply='lost';const lostBefore=applies.length;
    await evaluate(`q('[data-update-apply]').click();fixtureWait(()=>q('[data-update-status]').textContent.includes('자동 재시도'))`);
    assert.equal(applies.length,lostBefore+1);assert.match(await evaluate(`q('.admin-json-update-outcome').textContent`),/UNKNOWN/);
    await evaluate(`(async()=>{[...q('.admin-json-update-row').querySelectorAll('button')].find(e=>e.textContent==='저장 결과 확인').click();await fixtureWait(()=>!q('[data-update-preview]').disabled);})()`);
    assert.equal(statuses.length,1);assert.equal(applies.length,lostBefore+1);assert.match(await evaluate(`q('.admin-json-update-outcome').textContent`),/UPDATED/);
    await evaluate('upload(1)');nextApply='CONFLICT';await evaluate('save()');assert.match(await evaluate(`q('.admin-json-update-outcome').textContent`),/CONFLICT/);
    const create=await evaluate(`(()=>{q('[name="json-mode"][value="import"]').click();return {visible:!q('[data-json-import]').hidden,updateHidden:q('[data-json-update]').hidden,errors:fixtureErrors};})()`);
    assert.equal(create.visible,true);assert.equal(create.updateHidden,true);assert.deepEqual(create.errors,[]);
    const localAssets=assets.filter(a=>/^\/(css|js)\//.test(a.url));assert.ok(localAssets.length);for(const asset of localAssets){assert.equal(asset.status,200);assert.match(asset.url,/^\/(css|js)\/.+-[a-f0-9]{32}\.(css|js)$/);assert.ok(['text/css','text/javascript','application/javascript'].includes(asset.mime));}
    assert.equal(assets.filter(a=>a.status===404).length,0);
  });
