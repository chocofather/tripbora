// Run the MVC rendering test first: CourseDetailLocaleRenderingTest.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const read = file => fs.readFileSync(path.join(root, 'src/main/resources', file), 'utf8');

test('rendered course CTA, modal, nested range, errors and creation work across viewport sizes', {skip: !fs.existsSync(chromePath), timeout: 60000}, async t => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-course-plan-'));
    const chrome = spawn(chromePath, ['--headless=new','--no-first-run','--no-default-browser-check','--disable-gpu','--remote-debugging-pipe',`--user-data-dir=${profile}`], {stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
    let seq=0, buffer='', session;
    const pending = new Map();
    chrome.stdio[4].on('data', data => {
        buffer += data;
        let at;
        while ((at=buffer.indexOf('\0'))>=0) {
            const raw=buffer.slice(0,at); buffer=buffer.slice(at+1);
            if (!raw) continue;
            const message=JSON.parse(raw), task=pending.get(message.id);
            if(task) {pending.delete(message.id); message.error?task.reject(new Error(JSON.stringify(message.error))):task.resolve(message.result);}
        }
    });
    const send=(method,params={},target=session)=>new Promise((resolve,reject)=>{
        const id=++seq; pending.set(id,{resolve,reject});
        chrome.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');
    });
    chrome.on('exit',()=>{pending.forEach(task=>task.reject(new Error('Chrome exited')));pending.clear();});
    t.after(async()=>{
        chrome.kill();
        for(let i=0;i<10;i++) {try {fs.rmSync(profile,{recursive:true,force:true}); break;} catch {await new Promise(resolve=>setTimeout(resolve,100));}}
    });
    const target=await send('Target.createTarget',{url:'about:blank'},null);
    session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;
    await send('Page.enable');
    const evaluate=async expression=>{
        const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});
        assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));
        return result.result?.value;
    };
    const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const settle=()=>evaluate('new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)))');
    let html=fs.readFileSync(path.join(root,'build/reports/course-plan-ui/detail-fixture.html'),'utf8')
        .replace(/<script\b[\s\S]*?<\/script>/gi,'')
        .replace(/<link\b[^>]*>/gi,tag=>{
            const local=tag.match(/href="(\/css\/[^"?]+)"/);
            return local ? `<style>${read('static'+local[1].replace(/-[a-f0-9]{32}(?=\.css$)/,''))}</style>` : '';
        });
    html=html.replace(/(<img\b[^>]*\bsrc=")(\/[^"?]+)(")/gi,(tag,prefix,src,suffix)=>{
        const local=path.join(root,'src/main/resources/static',src);
        return fs.existsSync(local) ? `${prefix}data:image/png;base64,${fs.readFileSync(local).toString('base64')}${suffix}` : tag;
    }).replace('<head>','<head><base href="https://tripbora.test/">');
    const script=read('static/js/course-plan-create.js');
    for (const width of [1440,1280,768,430,390,360]) {
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await send('Page.navigate',{url:'about:blank'});
        await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html});
        await evaluate('document.querySelector("[data-course-hero-image]").decode()');
        await evaluate(`window.calls=[];window.assignedUrl='';window.postStatus=400;window.getStatus=200;
            window.fetch=async(url,options={})=>{calls.push({url:String(url),method:options.method||'GET',body:String(options.body||'')});
                if(options.method==='POST') {await new Promise(r=>setTimeout(r,30));return new Response(JSON.stringify(postStatus===200?{redirectUrl:'/travel-plans/42'}:{errors:[{field:'endDate',message:'기간을 조정해 주세요.'}]}),{status:postStatus,headers:{'Content-Type':'application/json'}});}
                return new Response(JSON.stringify(getStatus===200?{title:'오사카와 교토 여행',regionLabel:'일본 · 오사카',dayCount:4,durationLabel:'3박 4일',creationToken:'test-form-token'}:{message:'코스를 찾을 수 없습니다.'}),{status:getStatus,headers:{'Content-Type':'application/json'}});};`);
        await evaluate(read('static/js/travel-plan-date-range.js'));
        await evaluate(read('static/js/course-detail.js'));
        // Inject navigation only; actual DOM, events, form serialization and fetch stay in Chrome.
        await evaluate(`window.api={exports:{}};new Function('module','document',${JSON.stringify(script)})(api,undefined);
            api.exports.mount({querySelector:s=>document.querySelector(s),body:document.body,defaultView:{addEventListener:window.addEventListener.bind(window),location:{search:'',assign:url=>window.assignedUrl=url}}});`);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false,`${width}: page overflow`);
        assert.deepEqual(await evaluate('[...document.querySelectorAll(".course-plan-stats dd")].map(node=>node.textContent)'),['3박 4일','2곳']);
        const stats=await evaluate(`(()=>{const nodes=[...document.querySelectorAll('.course-plan-stats > div')].map(node=>node.getBoundingClientRect());const button=document.querySelector('[data-course-plan-open]').getBoundingClientRect();return {equal:Math.abs(nodes[0].width-nodes[1].width)<1,sameRow:nodes[0].top===nodes[1].top,overflow:nodes.some(node=>node.right>innerWidth),buttonHeight:button.height}})()`);
        assert.equal(stats.equal,true);assert.equal(stats.sameRow,true);assert.equal(stats.overflow,false);assert.ok(stats.buttonHeight>=48);
        assert.equal(await evaluate('document.querySelector(".course-meta > div dd").textContent'),'오사카');
        const buttonContent=await evaluate(`(()=>{const button=document.querySelector('[data-course-plan-open]'),icon=button.querySelector('svg').getBoundingClientRect(),text=button.querySelector('span').getBoundingClientRect(),bounds=button.getBoundingClientRect();return {icons:button.querySelectorAll('svg').length,centerOffset:Math.abs((icon.left+text.right)/2-(bounds.left+bounds.right)/2),position:getComputedStyle(document.querySelector('.course-plan-action')).position}})()`);
        assert.equal(buttonContent.icons,1);assert.ok(buttonContent.centerOffset<1);assert.equal(buttonContent.position,'static');
        const scrolling=await evaluate(`(()=>{const cta=document.querySelector('.course-plan-action');window.scrollTo(0,cta.getBoundingClientRect().top+scrollY-110);const before=cta.getBoundingClientRect().top;const start=scrollY;window.scrollBy(0,120);const moved=scrollY-start;const delta=before-cta.getBoundingClientRect().top;window.scrollTo(0,0);return {delta,moved}})()`);
        assert.ok(scrolling.moved>0);assert.ok(Math.abs(scrolling.delta-scrolling.moved)<1,'CTA scrolls with normal page content');
        const layout=await evaluate(`(()=>{const summary=document.querySelector('.course-official-summary'),hero=document.querySelector('.course-hero'),main=document.querySelector('.course-detail-main'),cta=document.querySelector('.course-plan-action'),route=document.querySelector('.course-route');return {summaryTop:summary.getBoundingClientRect().top,heroBottom:hero.getBoundingClientRect().bottom,mainTop:main.getBoundingClientRect().top,ctaTop:cta.getBoundingClientRect().top,ctaWidth:cta.getBoundingClientRect().width,headerSummary:!!document.querySelector('.course-summary-header .course-official-summary'),routeBorder:getComputedStyle(route).borderTopWidth}})()`);
        assert.ok(layout.summaryTop>=layout.heroBottom); assert.equal(layout.headerSummary,false); assert.equal(layout.routeBorder,'0px');
        if(width>1000) {assert.equal(layout.ctaWidth,350);assert.ok(Math.abs(layout.mainTop-layout.ctaTop)<1);}
        await click('[data-course-day-filter="2"]');
        assert.deepEqual(await evaluate('[...document.querySelectorAll("[data-course-day]")].filter(day=>!day.hidden).map(day=>day.dataset.courseDay)'),['2']);
        await click('[data-course-day-filter="all"]');
        // No-summary courses start directly with the itinerary, without a reserved summary gap.
        await evaluate(`window.summaryNode=document.querySelector('.course-summary-description');summaryNode.remove()`);
        assert.equal(await evaluate('getComputedStyle(document.querySelector(".course-route")).marginTop'),'0px');
        await evaluate(`document.querySelector('.course-detail-main').prepend(summaryNode)`);
        const summaryStyle=await evaluate(`(()=>{const p=document.querySelector('.course-official-summary'),s=getComputedStyle(p),h=document.querySelector('.course-summary-description h2');return {color:s.color,size:s.fontSize,weight:s.fontWeight,whiteSpace:s.whiteSpace,title:h.textContent,gap:getComputedStyle(h).marginBottom}})()`);
        assert.deepEqual(summaryStyle,{color:'rgb(55, 65, 81)',size:'15px',weight:'400',whiteSpace:'pre-line',title:'이런 여행이에요',gap:'12px'});
        await evaluate(`window.summaryText=document.querySelector('.course-official-summary').textContent;document.querySelector('.course-official-summary').textContent=${JSON.stringify('긴 여행 요약 설명입니다. '.repeat(30)+'\n\n관리자가 작성한 다음 문단입니다.')}`);
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false,'long multiline summary stays within viewport');
        await evaluate(`document.querySelector('.course-official-summary').textContent=summaryText`);
        const bounds=await evaluate(`(()=>{const a=document.querySelector('.course-plan-action').getBoundingClientRect(),m=document.querySelector('.course-detail-main').getBoundingClientRect();return {a:{x:a.x,y:a.y,right:a.right},m:{x:m.x,bottom:m.bottom,right:m.right}}})()`);
        if(width>1000) assert.ok(bounds.a.x>bounds.m.right,'desktop CTA occupies right column');
        else assert.ok(bounds.a.y>=bounds.m.bottom,'mobile CTA follows the main content');
        if(process.env.TRIPBORA_CAPTURE_UI && [1440,1280,768,390].includes(width)) {
            await evaluate(`window.scrollTo(0,document.querySelector(${JSON.stringify(width > 1000 ? '.course-detail-columns' : '.course-plan-action')}).getBoundingClientRect().top+scrollY-160)`);await settle();
            fs.writeFileSync(path.join(root,`build/reports/course-plan-ui/cta-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
            await evaluate('window.scrollTo(0,0)');await settle();
        }
        await click('[data-course-plan-open]'); await settle();
        assert.equal(await evaluate('document.querySelector("#course-plan-dialog").open'),true);
        assert.equal(await evaluate('window.assignedUrl'),'','opening does not navigate');
        assert.equal(await evaluate('document.querySelector("#course-plan-name").value'),'오사카와 교토 여행');
        assert.equal(await evaluate('document.querySelector("[data-course-plan-duration]").textContent'),'3박 4일');
        const modal=await evaluate(`(()=>{const d=document.querySelector('#course-plan-dialog'),r=d.getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,overflow:d.scrollWidth>d.clientWidth}})()`);
        assert.ok(Math.abs(modal.x+modal.w/2-width/2)<1); assert.ok(Math.abs(modal.y+modal.h/2-950/2)<1); assert.equal(modal.overflow,false);
        await evaluate(`document.querySelector('#course-plan-name').value='내가 바꾼 제목';document.querySelector('#course-plan-name').dispatchEvent(new Event('input'));document.querySelector('#course-plan-display-name').value='민준';document.querySelector('[data-course-plan-form]').requestSubmit()`);
        assert.equal(await evaluate('calls.filter(c=>c.method==="POST").length'),0,'missing dates cannot submit');
        assert.equal(await evaluate('document.querySelectorAll("dialog[open]").length'),2,'range opens above create modal');
        const dates=await evaluate('[...document.querySelectorAll("[data-range-date]")].map(b=>b.dataset.rangeDate)');
        await click(`[data-range-date="${dates[10]}"]`); await click(`[data-range-date="${dates[10]}"]`);
        await click('[data-range-action="confirm"]');
        assert.ok((await evaluate('document.querySelector("[data-range-error]").textContent')).includes('3박 4일'));
        await click(`[data-range-date="${dates[10]}"]`); await click(`[data-range-date="${dates[13]}"]`);
        assert.ok((await evaluate('document.querySelector("[data-range-summary]").textContent')).includes('3박 4일'));
        assert.equal(await evaluate('getComputedStyle(document.querySelector(".is-in-range")).borderRadius'),'999px');
        if(process.env.TRIPBORA_CAPTURE_UI && [1440,390].includes(width)) fs.writeFileSync(path.join(root,`build/reports/course-plan-ui/range-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        await click('[data-range-action="confirm"]'); await settle();
        assert.equal(await evaluate('document.querySelectorAll("dialog[open]").length'),1);
        assert.equal(await evaluate('document.body.style.overflow'),'hidden','nested picker retains parent scroll lock');
        await evaluate(`document.querySelector('[data-course-plan-form]').requestSubmit();document.querySelector('[data-course-plan-form]').requestSubmit();`);
        await evaluate('new Promise(resolve=>setTimeout(resolve,50))');
        assert.equal(await evaluate('calls.filter(c=>c.method==="POST").length'),1,'duplicate submissions are suppressed');
        const body=await evaluate('calls.find(c=>c.method==="POST").body');
        assert.equal(new URLSearchParams(body).get('title'),'내가 바꾼 제목');
        assert.equal(new URLSearchParams(body).get('creationToken'),'test-form-token');
        assert.ok((await evaluate('document.querySelector("[data-course-plan-error]").textContent')).includes('기간을 조정'));
        assert.equal(await evaluate('document.querySelector("#course-plan-dialog").open'),true,'server rejection retains modal');
        assert.equal(await evaluate('document.querySelector("#course-plan-name").value'),'내가 바꾼 제목');
        if(process.env.TRIPBORA_CAPTURE_UI) {
            const dir=path.join(root,'build/reports/course-plan-ui'); fs.mkdirSync(dir,{recursive:true});
            fs.writeFileSync(path.join(dir,`modal-${width}.png`),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));
        }
        await evaluate('window.postStatus=200;document.querySelector("[data-course-plan-form]").requestSubmit()');
        await evaluate('new Promise(resolve=>setTimeout(resolve,50))');
        assert.equal(await evaluate('window.assignedUrl'),'/travel-plans/42');
        if(width===390) {
            await send('Emulation.setDeviceMetricsOverride',{width,height:600,deviceScaleFactor:1,mobile:false});
            const small=await evaluate(`(()=>{const d=document.querySelector('#course-plan-dialog'),r=d.getBoundingClientRect();d.scrollTop=d.scrollHeight;return {top:r.top,bottom:r.bottom,overflow:document.documentElement.scrollWidth>innerWidth}})()`);
            assert.ok(small.top>=0 && small.bottom<=600); assert.equal(small.overflow,false);
        }
        await click('[data-course-plan-close]'); await settle();
        assert.equal(await evaluate('document.querySelector("#course-plan-dialog").open'),false);
        assert.equal(await evaluate('document.body.style.overflow'),'');
        assert.equal(await evaluate('document.activeElement.hasAttribute("data-course-plan-open")'),true);
        t.diagnostic(`${width}px: real rendered CTA placement, title, duration, nested date range, validation, duplicate guard, retained errors, redirect and no overflow passed`);
    }
    // Login return automatically reopens the selected course; failed template loads never enable creation.
    for(const status of [401,404]) {
        await send('Page.navigate',{url:'about:blank'});
        await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html});
        await evaluate(`window.assignedUrl='';window.fetch=async()=>new Response(JSON.stringify({message:'삭제된 코스입니다.'}),{status:${status}});
            window.api={exports:{}};new Function('module','document',${JSON.stringify(script)})(api,undefined);
            api.exports.mount({querySelector:s=>document.querySelector(s),body:document.body,defaultView:{addEventListener:window.addEventListener.bind(window),location:{search:'?createPlan=true',assign:url=>window.assignedUrl=url}}});`);
        await settle();
        assert.equal(await evaluate('document.querySelector("#course-plan-dialog").open'),true);
        if(status===401) assert.equal(await evaluate('window.assignedUrl'),'/login?redirect='+encodeURIComponent('/courses/7?createPlan=true'));
        else {
            assert.equal(await evaluate('document.querySelector(".course-plan-submit").disabled'),true);
            assert.ok((await evaluate('document.querySelector("[data-course-plan-error]").textContent')).includes('삭제된 코스'));
        }
    }
    // Slow authenticated reload: pageshow must not unlock an unprepared form or poison its submit guard.
    await send('Page.navigate',{url:'about:blank'});
    await send('Page.setDocumentContent',{frameId:(await send('Page.getFrameTree')).frameTree.frame.id,html});
    await evaluate(read('static/js/travel-plan-date-range.js'));
    await evaluate(`window.assignedUrl='';window.fetch=(url,options={})=>options.method==='POST'
        ? Promise.resolve(new Response(JSON.stringify({redirectUrl:'/travel-plans/42'})))
        : new Promise(resolve=>window.resolveTemplate=()=>resolve(new Response(JSON.stringify({title:'코스',regionLabel:'일본',dayCount:4,durationLabel:'3박 4일',creationToken:'slow-token'}))));
        window.api={exports:{}};new Function('module','document',${JSON.stringify(script)})(api,undefined);
        api.exports.mount({querySelector:s=>document.querySelector(s),body:document.body,defaultView:{addEventListener:window.addEventListener.bind(window),location:{search:'?createPlan=true',assign:url=>window.assignedUrl=url}}});
        window.dispatchEvent(new PageTransitionEvent('pageshow'));
        document.querySelector('[data-range-start]').value='2026-10-20';document.querySelector('[data-range-end]').value='2026-10-23';document.querySelector('#course-plan-display-name').value='나';`);
    assert.equal(await evaluate('document.querySelector(".course-plan-submit").disabled'),true,'pageshow keeps unprepared course form disabled');
    await evaluate(`document.querySelector('[data-course-plan-form]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`);
    assert.equal(await evaluate('document.querySelector("[data-course-plan-form]").dataset.submitting'),undefined);
    await evaluate('window.resolveTemplate()'); await settle();
    assert.equal(await evaluate('document.querySelector(".course-plan-submit").disabled'),false);
    await evaluate('document.querySelector("[data-course-plan-form]").requestSubmit()'); await settle();
    assert.equal(await evaluate('window.assignedUrl'),'/travel-plans/42');
});
