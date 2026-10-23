// First run DestinationListCardImageRenderingTest to export real Thymeleaf fragments.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const reports = path.join(root, 'build/reports/destination-image-position');
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

test('position editor and list agree across PC/mobile without downloads or drag-time saves',
    {skip: !fs.existsSync(chromePath), timeout: 60000}, async t => {
    const posts = [], assetFailures = [];
    let imageRequests = 0, rejectSave = false;
    const dimensions = {portrait:[300,400], landscape:[800,300], square:[100,100], small:[40,30]};
    const server = http.createServer((req,res) => {
        const url = new URL(req.url, 'http://localhost');
        if (req.method === 'POST') {
            let body = '';
            req.on('data', data => body += data);
            req.on('end', () => {
                posts.push({url:url.pathname, token:req.headers['x-csrf-token'], body:JSON.parse(body)});
                res.writeHead(rejectSave ? 400 : 200, {'Content-Type':'application/json'});
                res.end(rejectSave ? JSON.stringify({message:'대표 이미지가 변경되었습니다.'}) : body);
            });
            return;
        }
        if (url.pathname.startsWith('/fixture/')) {
            imageRequests++;
            const [w,h] = dimensions[path.basename(url.pathname,'.svg')];
            res.writeHead(200, {'Content-Type':'image/svg+xml', 'Cache-Control':'public,max-age=3600'});
            res.end(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><rect width="100%" height="100%" fill="#cbdce8"/><path d="M0 0L${w} ${h}M${w} 0L0 ${h}" stroke="#7657c8" stroke-width="3"/><circle cx="${w/2}" cy="${h/2}" r="10" fill="#fff"/></svg>`);
            return;
        }
        const file = url.pathname === '/' ? path.join(reports,'fixture.html')
            : path.resolve(root,'src/main/resources/static','.'+url.pathname);
        const allowed = path.resolve(root,'src/main/resources/static') + path.sep;
        if (url.pathname !== '/' && !file.startsWith(allowed)) {res.writeHead(400);res.end();return;}
        if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
            if (/\.(css|js)$/.test(url.pathname)) assetFailures.push(url.pathname);
            res.writeHead(404);res.end();return;
        }
        res.writeHead(200, {'Content-Type':{'.html':'text/html; charset=utf-8','.css':'text/css','.js':'text/javascript','.woff2':'font/woff2'}[path.extname(file)] || 'application/octet-stream'});
        res.end(fs.readFileSync(file));
    });
    await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
    t.after(() => server.close());
    const profile = fs.mkdtempSync(path.join(os.tmpdir(),'tripbora-image-position-'));
    const chrome = spawn(chromePath,['--headless=new','--no-first-run','--no-default-browser-check','--disable-gpu',
        '--remote-debugging-pipe',`--user-data-dir=${profile}`], {stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
    let sequence=0, buffer='', session;
    const pending = new Map();
    chrome.stdio[4].on('data', data => {
        buffer += data; let at;
        while ((at=buffer.indexOf('\0')) >= 0) {
            const raw=buffer.slice(0,at); buffer=buffer.slice(at+1); if(!raw)continue;
            const message=JSON.parse(raw), task=pending.get(message.id);
            if(task){pending.delete(message.id);message.error?task.reject(new Error(JSON.stringify(message.error))):task.resolve(message.result);}
        }
    });
    const send=(method,params={},target=session)=>new Promise((resolve,reject)=>{
        const id=++sequence; pending.set(id,{resolve,reject});
        chrome.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');
    });
    chrome.on('exit',()=>{pending.forEach(p=>p.reject(new Error('Chrome exited')));pending.clear();});
    t.after(async()=>{
        chrome.kill();
        for(let i=0;i<10;i++) {try {fs.rmSync(profile,{recursive:true,force:true});break;} catch {await new Promise(r=>setTimeout(r,100));}}
    });
    const target=await send('Target.createTarget',{url:'about:blank'},null);
    session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;
    await send('Page.enable');
    const evaluate=async expression=>{
        const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
        assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));
        return result.result?.value;
    };
    const settle=()=>evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');
    const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const slider=async value=>{await evaluate(`document.querySelector('#image-position-y').value=${value};document.querySelector('#image-position-y').dispatchEvent(new Event('input'))`);};
    const until=expression=>evaluate(`new Promise((resolve,reject)=>{let n=0;const check=()=>{if(${expression})resolve(true);else if(++n>150)reject(new Error('condition timeout'));else setTimeout(check,20)};check()})`);
    await send('Page.navigate',{url:`http://127.0.0.1:${server.address().port}/`});
    await until(`document.querySelector('[data-image-position-open]') && document.querySelector('.admin-destination-image-preview img').complete`);
    await evaluate(`window.uiErrors=[];addEventListener('error',e=>{if(e.message)uiErrors.push(e.message)});addEventListener('unhandledrejection',e=>uiErrors.push(String(e.reason)));window.originalImage=document.querySelector('.admin-destination-image-preview img')`);

    for (const width of [360,390,430,768,1440]) {
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await settle();
        const beforeRequests=imageRequests, beforePosts=posts.length;
        await click('[data-image-position-open]'); await settle();
        assert.equal(await evaluate(`document.querySelector('[data-position-stage] img')===originalImage`),true);
        const state=await evaluate(`(()=>{const d=document.querySelector('dialog'),s=document.querySelector('[data-position-stage]'),r=s.getBoundingClientRect();return {open:d.open,ratio:r.width/r.height,overflow:document.documentElement.scrollWidth>innerWidth,x:document.querySelector('#image-position-x').disabled,y:document.querySelector('#image-position-y').disabled,fit:getComputedStyle(s.querySelector('img')).objectFit}})()`);
        assert.equal(state.open,true); assert.ok(Math.abs(state.ratio-4/3)<0.01);
        assert.equal(state.overflow,false);assert.equal(state.x,true);assert.equal(state.y,false);assert.equal(state.fit,'cover');
        const saveStyle=await evaluate(`(()=>{const s=getComputedStyle(document.querySelector('[data-position-save]'));return {background:s.backgroundColor,color:s.color}})()`);
        assert.equal(saveStyle.background,'rgb(118, 87, 200)');assert.equal(saveStyle.color,'rgb(255, 255, 255)');
        const box=await evaluate(`(()=>{const r=document.querySelector('[data-position-stage]').getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2}})()`);
        const initial=await evaluate(`Number(document.querySelector('#image-position-y').value)`);
        await send('Input.dispatchMouseEvent',{type:'mousePressed',x:box.x,y:box.y,button:'left',clickCount:1});
        await send('Input.dispatchMouseEvent',{type:'mouseMoved',x:box.x,y:box.y+30,button:'left',buttons:1});
        await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:box.x,y:box.y+30,button:'left',clickCount:1});
        assert.ok(await evaluate(`Number(document.querySelector('#image-position-y').value)`)<initial,'drag down moves photo down');
        if (width === 390) {
            const start=await evaluate(`Number(document.querySelector('#image-position-y').value)`);
            await send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:box.x,y:box.y}]});
            await send('Input.dispatchTouchEvent',{type:'touchMove',touchPoints:[{x:box.x,y:box.y+20}]});
            await send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]});
            assert.ok(await evaluate(`Number(document.querySelector('#image-position-y').value)`)<start,'touch drag follows the grabbed image');
        }
        assert.equal(posts.length,beforePosts);
        await click('[data-position-reset]');
        assert.equal(await evaluate(`document.querySelector('#image-position-y').value`),'50');
        if (width === 390 || width === 1440) {
            const screenshot=await send('Page.captureScreenshot',{format:'png'});
            fs.writeFileSync(path.join(reports,`editor-${width}.png`),Buffer.from(screenshot.data,'base64'));
        }
        await click('[data-position-cancel]');await settle();
        assert.equal(await evaluate(`originalImage.style.getPropertyValue('--card-position-y')`),'70%');
        assert.equal(imageRequests,beforeRequests,'opening modal reuses loaded image without another request');
        const list=await evaluate(`(()=>{const cards=[...document.querySelectorAll('.destination-card')],media=[...document.querySelectorAll('.destination-card-media')];return {cols:getComputedStyle(document.querySelector('.destination-card-list')).gridTemplateColumns.split(' ').length,ratios:media.map(e=>{const r=e.getBoundingClientRect();return r.width/r.height}),heights:cards.map(e=>e.getBoundingClientRect().height),fits:media.flatMap(e=>[...e.querySelectorAll('img')].map(i=>getComputedStyle(i).objectFit)),empty:media[4].children.length,overflow:document.documentElement.scrollWidth>innerWidth}})()`);
        assert.equal(list.cols,width>1024?4:width>820?2:width>600?3:2);
        assert.ok(list.ratios.every(r=>Math.abs(r-4/3)<0.01));
        assert.ok(Math.max(...list.heights)-Math.min(...list.heights)<1);
        assert.ok(list.fits.every(f=>f==='cover'));assert.equal(list.empty,0);assert.equal(list.overflow,false);
    }
    await click('[data-image-position-open]'); await settle();
    await slider(20); await click('[data-position-save]');
    await until(`!document.querySelector('dialog').open`);await settle();
    assert.deepEqual(posts.at(-1),{url:'/admin/api/destinations/3/images/8/card-position',token:'test-csrf',body:{x:50,y:20}});
    await click('[data-image-position-open]'); await settle();
    assert.equal(await evaluate(`document.querySelector('#image-position-y').value`),'20');
    rejectSave=true;await slider(80);await click('[data-position-save]');
    await until(`document.querySelector('[data-position-status]').textContent.includes('대표 이미지가 변경')`);
    await click('[data-position-cancel]');await settle();
    assert.equal(await evaluate(`originalImage.style.getPropertyValue('--card-position-y')`),'20%');

    for (const kind of ['landscape','square','small']) {
        await evaluate(`originalImage.src='/fixture/${kind}.svg'`);
        await until(`originalImage.complete && originalImage.naturalWidth===${dimensions[kind][0]}`);
        await click('[data-image-position-open]');await settle();
        const disabled=await evaluate(`[document.querySelector('#image-position-x').disabled,document.querySelector('#image-position-y').disabled]`);
        assert.deepEqual(disabled,kind==='landscape'?[false,true]:kind==='square'?[true,false]:[true,true]);
        const parity=await evaluate(`(()=>{const m=document.querySelector('[data-position-stage]'),i=m.querySelector('img'),c=document.querySelector('.destination-card-media');c.style.width=m.clientWidth+'px';c.style.height=m.clientHeight+'px';c.querySelector('img').src=i.currentSrc;c.querySelector('img').style.cssText=i.style.cssText;return [getComputedStyle(i).objectPosition,getComputedStyle(c.querySelector('img')).objectPosition]})()`);
        assert.equal(parity[0],parity[1]);
        await click('[data-position-cancel]');await settle();
    }
    assert.deepEqual(await evaluate('uiErrors'),[]);assert.deepEqual(assetFailures,[]);
});
