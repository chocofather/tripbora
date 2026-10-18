// Chrome + actual inline template/CSS/JS. Server authorization/persistence are covered separately by Java tests.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const read = file => fs.readFileSync(path.join(root, 'src/main/resources', file), 'utf8');

test('inline creation and two-month date range work in Chrome without overflow', {skip: !fs.existsSync(chromePath), timeout: 60000}, async t => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-list-'));
    const chrome = spawn(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--remote-debugging-pipe', `--user-data-dir=${profile}`], {stdio:['ignore','ignore','ignore','pipe','pipe'], windowsHide:true});
    let seq=0, buffer='', session;
    const pending=new Map();
    chrome.stdio[4].on('data', data => {
        buffer+=data;
        let at;
        while((at=buffer.indexOf('\0'))>=0) {
            const raw=buffer.slice(0,at); buffer=buffer.slice(at+1);
            if(!raw) continue;
            const message=JSON.parse(raw), task=pending.get(message.id);
            if(task){pending.delete(message.id);message.error?task.reject(new Error(JSON.stringify(message.error))):task.resolve(message.result);}
        }
    });
    const send=(method, params={}, target=session)=>new Promise((resolve,reject)=>{
        const id=++seq;pending.set(id,{resolve,reject});
        chrome.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');
    });
    chrome.on('exit',()=>{pending.forEach(p=>p.reject(new Error('Chrome exited')));pending.clear();});
    t.after(async()=>{
        chrome.kill();
        for(let i=0;i<10;i++) {try{fs.rmSync(profile,{recursive:true,force:true});break;}catch{await new Promise(r=>setTimeout(r,100));}}
    });
    const target=await send('Target.createTarget',{url:'about:blank'},null);
    session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;
    await send('Page.enable');
    const evaluate=async expression=>{
        const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
        assert.equal(r.exceptionDetails,undefined,JSON.stringify(r.exceptionDetails));return r.result?.value;
    };
    const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    let form=read('templates/travelplan/fragments/create-inline.html').match(/<section[\s\S]*<\/section>/)[0]
        .replace(/<div class="travel-plan-form-error"[\s\S]*?<\/div>/g,'')
        .replace(/<p class="travel-plan-field-error"[\s\S]*?<\/p>/g,'')
        .replace(/th:field="\*\{(\w+)\}"/g,'name="$1"');
    const css=['style.css','travel-plan.css','travel-plan-list.css'].map(f=>read('static/css/'+f)).join('\n');
    // Use the actual list markup as well as the shared reset: a hand-written card
    // fixture missed both the production dialog margin reset and list regressions.
    const template=read('templates/travelplan/list.html');
    let list=template.slice(template.indexOf('<section class="travel-plan-list-page"'), template.lastIndexOf('</section>')+'</section>'.length)
        .replace(/<th:block th:replace="[^"]*create-inline[^"]*"><\/th:block>/,form)
        .replace(/<p class="travel-plan-(?:empty|notice)"[\s\S]*?<\/p>/g,'')
        .replace(/<nav class="travel-plan-pagination"[\s\S]*?<\/nav>/,'')
        .replace(/th:href="@\{\|([^|]+)\|\}"/g,(_,href)=>`href="${href.replace('${plan.travelPlanId}','42')}"`)
        .replace(/\s+th:[\w-]+="[^"]*"/g,'');
    let cardSection=0;
    list=list.replace(/<li class="travel-plan-card"[\s\S]*?<\/li>/g,card=>Array.from({length:cardSection++===0?5:2},(_,i)=>card.replace('제주도 여행',i===0?'제주도에서 함께 보내는 가을 여행':'아주 긴 여행 제목으로 함께 떠나는 제주 여행 '.repeat(4))).join(''));
    let countSection=0;
    list=list.replace(/(travel-plan-section-count"><span\s*>)[^<]+/g,(_,prefix)=>prefix+(countSection++===0?5:2))
        .replaceAll('class="travel-plan-card-role"','class="travel-plan-card-role is-owner"');
    const settle=()=>evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const centered=async(width,height)=>{
        const bounds=await evaluate(`(()=>{const r=document.querySelector('dialog').getBoundingClientRect();return {x:r.x,y:r.y,width:r.width,height:r.height}})()`);
        assert.ok(Math.abs(bounds.x+bounds.width/2-width/2)<1, 'dialog horizontally centered with shared reset');
        assert.ok(Math.abs(bounds.y+bounds.height/2-height/2)<1, 'dialog vertically centered with shared reset');
        assert.ok(bounds.x>=0 && bounds.y>=0 && bounds.x+bounds.width<=width && bounds.y+bounds.height<=height, 'dialog contained in viewport');
        if(width>=768) assert.equal(bounds.width,Math.min(800,width-32));
    };
    for(const width of [1440,1280,768,430,390,360]) {
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await send('Page.navigate',{url:'about:blank'});
        const html=`<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body><main>${list}</main></body></html>`;
        await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html});
        await evaluate(read('static/js/travel-plan-date-range.js'));
        await evaluate(`document.dispatchEvent(new Event('DOMContentLoaded'))`);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        const cards=await evaluate(`[...document.querySelectorAll('.travel-plan-card-link')].map(a=>{const r=a.getBoundingClientRect();return{x:r.x,y:r.y,width:r.width,height:r.height,href:a.getAttribute('href'),chevron:!!a.querySelector('.travel-plan-card-chevron')}})`);
        assert.equal(cards.length,7);
        assert.ok(cards.every(c=>c.x===cards[0].x && c.width===cards[0].width && c.chevron));
        assert.ok(cards[1].y>=cards[0].y+cards[0].height+10, 'one-column rows with spacing');
        assert.equal(cards[0].href,'/travel-plans/42');
        assert.equal(cards.at(-1).href,'/travel-plans/42/final');
        if(width>=1280) assert.ok(cards.every(c=>c.height>=90 && c.height<=110), 'compact desktop cards');
        if(process.env.TRIPBORA_CAPTURE_UI && [1440,390].includes(width)) {
            const dir=path.join(root,'build/reports/travel-plan-ui');fs.mkdirSync(dir,{recursive:true});
            fs.writeFileSync(path.join(dir,`list-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        }
        await click('[data-range-open]');
        await centered(width,950);
        assert.equal(await evaluate('document.body.style.overflow'),'hidden');
        assert.equal(await evaluate('document.querySelector("dialog").scrollWidth>document.querySelector("dialog").clientWidth'),false);
        if(process.env.TRIPBORA_CAPTURE_UI && [1440,390].includes(width)) {
            fs.writeFileSync(path.join(root,`build/reports/travel-plan-ui/range-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        }
        assert.equal(await evaluate('document.querySelectorAll(".tp-range-months section").length'),2);
        assert.equal(await evaluate('document.querySelectorAll("[aria-current=date]").length'),1);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        const dates=await evaluate('[...document.querySelectorAll("[data-range-date]")].map(b=>b.dataset.rangeDate)');
        await click(`[data-range-date="${dates[10]}"]`); await click(`[data-range-date="${dates[14]}"]`);
        await click('[data-range-action="confirm"]');
        await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
        assert.equal(await evaluate('document.querySelector("[data-range-start]").value'),dates[10]);
        assert.equal(await evaluate('document.querySelector("[data-range-end]").value'),dates[14]);
        await click('[data-range-open]');await click('[data-range-action="clear"]');
        await click('[data-range-action="close"]');
        await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
        assert.equal(await evaluate('document.querySelector("[data-range-start]").value'),dates[10], 'dismiss does not discard committed range');
        await click('[data-range-open]');await click('[data-range-action="next"]');
        assert.notEqual(await evaluate('document.querySelector("[data-range-date]").dataset.rangeDate'),dates[0], 'month navigation changes dates');
        await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
        await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
        await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
        assert.equal(await evaluate('document.querySelector("dialog")'),null);
        assert.equal(await evaluate('document.activeElement.hasAttribute("data-range-open")'),true);
        assert.equal(await evaluate('document.body.style.overflow'),'');
        await evaluate('window.scrollTo(0,400)');
        await settle();
        assert.ok(await evaluate('window.scrollY>0'), 'test starts with a scrolled page');
        await click('[data-range-open]');
        await centered(width,950);
        await send('Emulation.setDeviceMetricsOverride',{width,height:480,deviceScaleFactor:1,mobile:false});
        await centered(width,480);
        assert.equal(await evaluate('document.querySelector("dialog").scrollHeight>document.querySelector("dialog").clientHeight'),true, 'short viewport allows internal scrolling');
        await click('[data-range-action="close"]');await settle();
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await click('[data-range-open]');
        await send('Input.dispatchMouseEvent',{type:'mousePressed',x:2,y:2,button:'left',clickCount:1});
        await send('Input.dispatchMouseEvent',{type:'mouseReleased',x:2,y:2,button:'left',clickCount:1});
        await settle();
        assert.equal(await evaluate('document.querySelector("dialog")'),null, 'backdrop click closes modal');
        // Submitted fields remain ordinary form fields for the existing POST endpoint.
        assert.deepEqual(await evaluate('[...new FormData(document.querySelector("form"))].map(([k])=>k)'),['title','startDate','endDate','displayName']);
        await click('[data-range-open]');await click('[data-range-action="clear"]');await click('[data-range-action="confirm"]');
        await evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
        assert.equal(await evaluate('document.querySelector("[data-range-start]").value'), '');
        assert.equal(await evaluate('document.querySelector("[data-range-end]").value'), '');
        t.diagnostic(`${width}px: actual one-column cards, routes, centered/scrolled/short dialog, two months, today, range commit/cancel/clear, Escape focus, fields and overflow passed`);
        const owner=read('templates/travelplan/fragments/owner-actions.html');
        const invite=owner.slice(owner.indexOf('<div class="travel-plan-invite"'),owner.indexOf('    <!--\n      방을 통째로'));
        const inviteCss=['travel-plan.css','travel-plan-planner.css','travel-plan-invite.css'].map(f=>read('static/css/'+f)).join('\n');
        await send('Page.navigate',{url:'about:blank'});
        await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html:`<!doctype html><html><head><style>body{margin:0}${inviteCss}</style></head><body><main class="travel-plan-page is-planning"><div class="travel-plan-page-top-row"><div class="travel-plan-top-actions">${invite}</div></div><article data-plan-id="1"></article></main></body></html>`});
        await evaluate(`document.querySelectorAll('*').forEach(node=>{if(node.getAttribute('th:unless')==='\u0024{travelPlanInviteActive}')node.remove()});document.querySelector('[data-travel-plan-invite-url]').value='https://tripbora.test/travel-plans/invitations/example';Object.defineProperty(navigator,'clipboard',{configurable:true,value:{writeText:async text=>window.copiedText=text}});`);
        await evaluate(read('static/js/travel-plan-scheduler.js'));
        await evaluate(`document.dispatchEvent(new Event('DOMContentLoaded'))`);
        await click('[data-travel-plan-invite-toggle]');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-invite-panel]").hidden'),false);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        const bounds=await evaluate(`(()=>{const r=document.querySelector('[data-travel-plan-invite-panel]').getBoundingClientRect();return{left:r.left,right:r.right}})()`);
        assert.ok(bounds.left>=0 && bounds.right<=width);
        await click('[data-travel-plan-invite-copy]');
        assert.equal(await evaluate('window.copiedText'),'https://tripbora.test/travel-plans/invitations/example');
        assert.ok((await evaluate('document.querySelector("[data-invite-copy-feedback]").textContent')).includes('복사했어요'));
        await evaluate(`Object.defineProperty(navigator,'clipboard',{configurable:true,value:undefined})`);
        await click('[data-travel-plan-invite-copy]');
        assert.ok((await evaluate('document.querySelector("[data-invite-copy-feedback]").textContent')).includes('직접 복사'));
        t.diagnostic(`${width}px: actual invite template open/copy/fallback feedback and viewport bounds passed`);
    }
});
