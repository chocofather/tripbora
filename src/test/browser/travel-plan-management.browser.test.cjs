// Run TravelPlanControllerTest first to export the actual Thymeleaf fixtures.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const read = file => fs.readFileSync(path.join(root,'src/main/resources',file),'utf8');

test('real list management and final snapshot friends remain compact and safe across viewports', {skip:!fs.existsSync(chromePath),timeout:60000}, async t=>{
    const profile=fs.mkdtempSync(path.join(os.tmpdir(),'tripbora-management-'));
    const chrome=spawn(chromePath,['--headless=new','--no-first-run','--no-default-browser-check','--disable-gpu','--remote-debugging-pipe',`--user-data-dir=${profile}`],{stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
    let seq=0,buffer='',session;
    const pending=new Map();
    chrome.stdio[4].on('data',data=>{
        buffer+=data;let at;
        while((at=buffer.indexOf('\0'))>=0){const raw=buffer.slice(0,at);buffer=buffer.slice(at+1);if(!raw)continue;const m=JSON.parse(raw),task=pending.get(m.id);if(task){pending.delete(m.id);m.error?task.reject(new Error(JSON.stringify(m.error))):task.resolve(m.result);}}
    });
    const send=(method,params={},target=session)=>new Promise((resolve,reject)=>{const id=++seq;pending.set(id,{resolve,reject});chrome.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');});
    chrome.on('exit',()=>{pending.forEach(p=>p.reject(new Error('Chrome exited')));pending.clear();});
    t.after(async()=>{chrome.kill();for(let i=0;i<10;i++){try{fs.rmSync(profile,{recursive:true,force:true});break;}catch{await new Promise(r=>setTimeout(r,100));}}});
    const target=await send('Target.createTarget',{url:'about:blank'},null);
    session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;await send('Page.enable');
    const evaluate=async expression=>{const r=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.equal(r.exceptionDetails,undefined,JSON.stringify(r.exceptionDetails));return r.result?.value;};
    const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const settle=()=>evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    const fixture=name=>fs.readFileSync(path.join(root,`build/reports/travel-plan-management-ui/${name}-fixture.html`),'utf8')
        .replace(/<script\b[\s\S]*?<\/script>/gi,'').replace(/<link\b[^>]*>/gi,tag=>{const css=tag.match(/href="(\/css\/[^"?]+)"/);return css?`<style>${read('static'+css[1].replace(/-[a-f0-9]{32}(?=\.css$)/,''))}</style>`:'';});
    const load=async name=>{
        await send('Page.navigate',{url:'about:blank'});
        await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html:fixture(name)});
        await evaluate(`window.posts=[];window.followed=[];document.querySelectorAll('form').forEach(form=>form.addEventListener('submit',event=>{event.preventDefault();posts.push({action:form.getAttribute('action'),fields:[...new FormData(form).keys()]})}));document.querySelectorAll('a.travel-plan-card-link').forEach(a=>a.addEventListener('click',event=>{event.preventDefault();followed.push(a.getAttribute('href'))}));`);
        await evaluate(read('static/js/travel-plan-management.js'));
        if(name!=='final') await evaluate(read('static/js/travel-plan-list.js'));
        await evaluate(`document.dispatchEvent(new Event('DOMContentLoaded'))`);
    };
    for(const width of [1440,1280,768,390]){
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await load('list');await settle();
        assert.deepEqual(await evaluate('[...document.querySelectorAll("[data-plan-filter]")].map(tab=>tab.querySelector("span").textContent)'),['2','1','3']);
        assert.equal(await evaluate('document.querySelector("[data-plan-filter=active]").getAttribute("aria-selected")'),'true');
        assert.equal(await evaluate('[...document.querySelectorAll("[data-plan-status]")].filter(card=>!card.hidden).length'),2);
        await click('[data-plan-filter="completed"]');
        assert.equal(await evaluate('[...document.querySelectorAll("[data-plan-status]")].filter(card=>!card.hidden).length'),1);
        await click('[data-plan-filter="all"]');
        const columns=await evaluate('getComputedStyle(document.querySelector("[data-plan-grid]")).gridTemplateColumns.split(" ").length');
        assert.equal(columns,width>740?2:1);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        const heights=await evaluate('[...document.querySelectorAll(".travel-plan-card-link")].map(a=>a.getBoundingClientRect().height)');
        if(width>=1280)assert.ok(heights.every(h=>h>=120&&h<=170),JSON.stringify(heights));
        assert.equal(await evaluate('document.querySelectorAll("a.travel-plan-card-link form,a.travel-plan-card-link button").length'),0);
        const summaries=['.travel-plan-card:nth-child(1) .tp-card-menu > summary','.travel-plan-card:nth-child(2) .tp-card-menu > summary','[data-plan-status="completed"] .tp-card-menu > summary'];
        const buttons=['[data-manage-kind="delete"] button','[data-manage-kind="leave"] button','[data-manage-kind="hide"] button'];
        for(let i=0;i<buttons.length;i++){
            await click(summaries[i]);await settle();await click(buttons[i]);await settle();
            assert.equal(await evaluate('document.querySelector("[data-plan-manage-dialog]").open'),true);
            assert.deepEqual(await evaluate('followed'),[],'management controls never follow card link');
            assert.deepEqual(await evaluate('posts'),[],'opening confirmation does not submit');
            const text=await evaluate('document.querySelector("[data-plan-manage-description]").textContent');
            if(i===0)assert.ok(text.includes('여행 전체')&&text.includes('되돌릴 수 없습니다'));
            if(i===1)assert.ok(text.includes('내 참여 관계만'));
            if(i===2)assert.ok(text.includes('마지막 보관자'));
            await click('[data-plan-manage-cancel]');await settle();
            assert.deepEqual(await evaluate('posts'),[],'cancel never submits');
            await click(summaries[i]);await click(buttons[i]);await click('[data-plan-manage-confirm]');await click('[data-plan-manage-confirm]');
            assert.equal(await evaluate('posts.length'),1,'confirm submits only once');
            const posted=await evaluate('posts[0]');
            assert.equal(posted.action,['/travel-plans/42/delete','/travel-plans/43/members/leave','/travel-plans/44/final/delete'][i]);
            assert.ok(posted.fields.includes('_csrf'));
            await evaluate(`window.posts=[];window.dispatchEvent(new PageTransitionEvent('pageshow'));document.querySelector('[data-plan-manage-dialog]').close()`);await settle();
        }
        await click('.travel-plan-card-link');assert.deepEqual(await evaluate('followed'),['/travel-plans/42']);
        if(process.env.TRIPBORA_CAPTURE_UI)fs.writeFileSync(path.join(root,`build/reports/travel-plan-management-ui/list-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        await load('final');await settle();
        assert.equal(await evaluate('document.querySelectorAll(".travel-plan-back-link").length'),0);
        await click('.tp-final-friends > summary');await settle();
        const panel=await evaluate(`(()=>{const p=document.querySelector('.tp-final-friends-panel'),r=p.getBoundingClientRect();return{left:r.left,right:r.right,overflow:p.scrollWidth>p.clientWidth,count:p.querySelectorAll('li').length}})()`);
        assert.ok(panel.left>=0&&panel.right<=width,JSON.stringify(panel));assert.equal(panel.overflow,false);assert.equal(panel.count,2);
        assert.equal(await evaluate('document.querySelectorAll(".tp-final-friends-panel button,.tp-final-friends-panel form").length'),0,'friends remain read only');
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        if(process.env.TRIPBORA_CAPTURE_UI)fs.writeFileSync(path.join(root,`build/reports/travel-plan-management-ui/final-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});
        assert.equal(await evaluate('document.querySelector(".tp-final-friends").open'),false);
        await click('.tp-final-delete');await settle();
        assert.equal(await evaluate('document.querySelector("[data-plan-manage-dialog]").open'),true);
        await click('[data-plan-manage-cancel]');await settle();assert.deepEqual(await evaluate('posts'),[]);
        t.diagnostic(`${width}px: role-specific menus, native actions/CSRF, confirmation/cancel, snapshot friends, compact rows and overflow passed`);
    }
    await load('paged-1');
    await evaluate(`document.querySelector('[name="title"]').value='입력 중인 새 여행';window.fetches=0;window.fetch=()=>{fetches++;return new Promise(resolve=>window.resolvePage=()=>resolve(new Response(${JSON.stringify(fixture('paged-2'))},{headers:{'Content-Type':'text/html'}})))};`);
    await click('[data-plan-filter="completed"]');
    assert.equal(await evaluate('[...document.querySelectorAll("[data-plan-status]")].filter(card=>!card.hidden).length'),8);
    await click('[data-completed-pagination] a[href*="completedPage=2"]');
    await click('[data-plan-status="completed"] .tp-card-menu summary');
    await click('[data-plan-status="completed"] [data-plan-manage-open]');
    await evaluate('window.resolvePage()');await settle();
    assert.equal(await evaluate(`!!document.querySelector('[data-plan-grid] form[action="/travel-plans/44/final/delete"]')`),true,'pending page cannot detach a confirmed action form');
    await click('[data-plan-manage-confirm]');
    assert.equal(await evaluate('posts.length'),1);
    assert.equal(await evaluate('posts[0].action'),'/travel-plans/44/final/delete');
    await evaluate(`window.dispatchEvent(new PageTransitionEvent('pageshow'));document.querySelector('[data-plan-manage-dialog]').close()`);await settle();
    assert.equal(await evaluate('fetches'),1);
    assert.equal(await evaluate('document.querySelector("[data-selected-filter]").dataset.selectedFilter'),'completed');
    assert.equal(await evaluate('[...document.querySelectorAll("[data-plan-status]")].filter(card=>!card.hidden).length'),1);
    assert.ok((await evaluate('document.querySelector("[data-plan-grid]").textContent')).includes('완료 여행 9'));
    assert.equal(await evaluate('document.querySelector("[name=title]").value'),'입력 중인 새 여행','paging preserves creation draft');
    assert.deepEqual(await evaluate('[...document.querySelectorAll("[data-plan-filter] span")].map(span=>span.textContent)'),['2','9','11']);
    await evaluate('window.fetch=async()=>{throw new Error("offline")}');
    await click('[data-completed-pagination] a[href*="completedPage=1"]');await settle();
    assert.ok((await evaluate('document.querySelector("[data-plan-list-status]").textContent')).includes('불러오지 못했어요'));
    assert.equal(await evaluate('document.querySelector("[name=title]").value'),'입력 중인 새 여행');
    await evaluate('document.querySelectorAll("[data-plan-status]").forEach(card=>card.remove())');
    await click('[data-plan-filter="active"]');assert.equal(await evaluate('document.querySelector("[data-plan-empty]").textContent'),'진행 중인 여행이 없어요.');
    await click('[data-plan-filter="completed"]');assert.equal(await evaluate('document.querySelector("[data-plan-empty]").textContent'),'아직 완료된 여행이 없어요.');
    await click('[data-plan-filter="all"]');assert.equal(await evaluate('document.querySelector("[data-plan-empty]").hidden'),false);
});
