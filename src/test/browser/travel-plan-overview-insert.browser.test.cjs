// Run explicitly: node --test src/test/browser/travel-plan-overview-insert.browser.test.cjs
// Uses an isolated Chrome profile, actual source JS/CSS and trusted pointer input.
// The creation endpoint/realtime refresh are stubs: no application or DB needed.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const chromePath = [process.env.TRIPBORA_CHROME_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium'].find(file => file && fs.existsSync(file));
const read = file => fs.readFileSync(path.join(root, 'src/main/resources/static', file), 'utf8');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

function fixture() {
    const card = id => `<div class="tp-overview-item" data-overview-item-id="${id}">${id}</div>`;
    const bar = `<div class="tp-overview-insert-bar" data-overview-insert-bar data-day-id="3" data-insert-placement="end" data-insert-default="true" data-before-item-id="">
        <button type="button" class="tp-overview-insert-grip" draggable="true" data-overview-insert-handle>⠿</button>
        ${[['FLIGHT', '항공'], ['ACCOMMODATION', '숙소'], ['TRANSPORT', '이동']].map(([type, name]) => `<button type="button" class="tp-item-add-type" data-overview-insert-type="${type}">+ ${name}</button>`).join('')}
        <span class="tp-overview-insert-hint">이 자리에 추가됩니다</span><span data-overview-insert-status></span></div>`;
    return `<!doctype html><html><head><meta charset="utf-8"><base href="http://tripbora.test/"><style>${read('css/travel-plan-item-types.css')}
        body{margin:0;font-family:Arial}.tp-planner-overview{width:calc(100% - 32px);max-width:760px;margin:20px auto}
        .tp-planner-summary{padding:8px 0}.tp-planner-summary h3{height:24px;margin:0 0 6px}
        .tp-planner-summary-items{display:grid;align-items:start;gap:10px;min-height:60px!important}
        .tp-overview-item{height:48px;border-radius:8px;background:#e7f3ff;padding:0 12px;display:flex;align-items:center;box-sizing:border-box}
        </style></head><body><main class="is-planning"><div data-plan-id="1"><div class="tp-planner-overview"><div data-planner-summaries>
        ${[1, 2, 3].map(id => `<section class="tp-planner-summary" data-planner-summary="${id}"><h3>DAY ${id}</h3><div class="tp-planner-summary-items" data-planner-summary-items>${id === 1 ? card('a') + card('b') : id === 3 ? bar : ''}</div></section>`).join('')}
        </div></div>${[1, 2, 3].map(id => `<section data-travel-plan-day data-day-id="${id}">${id === 1 ? '<li data-travel-plan-item data-item-id="a"></li><li data-travel-plan-item data-item-id="b"></li>' : ''}
        <div data-travel-plan-slot data-day-id="${id}"><form data-travel-plan-slot-form action="/travel-plans/1/days/${id}/items"><input name="_csrf" value="test"></form></div></section>`).join('')}
        </div></main></body></html>`;
}

test('Y-only insertion and card sorting preserve full order, color and cross-view state', {skip: !chromePath, timeout: 180000}, async t => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-drag-test-'));
    const chrome = spawn(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--remote-debugging-pipe', `--user-data-dir=${profile}`],
        {stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'], windowsHide: true});
    let sequence = 0, buffer = '', session;
    const pending = new Map();
    const rejectAll = error => { pending.forEach(task => task.reject(error)); pending.clear(); };
    chrome.on('error', rejectAll);
    chrome.on('exit', code => rejectAll(new Error(`Chrome exited (${code}) before CDP response`)));
    chrome.stdio[4].on('data', data => {
        buffer += data;
        let at;
        while ((at = buffer.indexOf('\0')) >= 0) {
            const raw = buffer.slice(0, at); buffer = buffer.slice(at + 1);
            if (!raw) continue;
            const message = JSON.parse(raw), task = pending.get(message.id);
            if (task) { pending.delete(message.id); message.error ? task.reject(new Error(JSON.stringify(message.error))) : task.resolve(message.result); }
        }
    });
    const send = (method, params = {}, targetSession = session) => new Promise((resolve, reject) => {
        const id = ++sequence; pending.set(id, {resolve, reject});
        chrome.stdio[3].write(JSON.stringify({id, method, params, ...(targetSession ? {sessionId: targetSession} : {})}) + '\0');
    });
    const evaluate = async expression => {
        const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
        assert.equal(result.exceptionDetails, undefined, JSON.stringify(result.exceptionDetails));
        return result.result?.value;
    };
    t.after(async () => {
        chrome.kill();
        // Only remove the exact temporary profile created above, after Chrome releases it.
        for (let attempt = 0; attempt < 10; attempt++) {
            try { fs.rmSync(profile, {recursive: true, force: true}); break; }
            catch { await wait(100); }
        }
    });
    const browser = await send('Browser.getVersion', {}, null);
    t.diagnostic(browser.product);
    const target = await send('Target.createTarget', {url: 'about:blank'}, null);
    session = (await send('Target.attachToTarget', {targetId: target.targetId, flatten: true}, null)).sessionId;
    await send('Page.enable'); await send('Runtime.enable');
    const size = width => send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
    const setup = async () => {
        await send('Page.navigate', {url: 'about:blank'});
        const frameId = (await send('Page.getFrameTree')).frameTree.frame.id;
        await send('Page.setDocumentContent', {frameId, html: fixture()});
        await evaluate(`window.trace=[];window.errors=[];window.calls=[];
            window.addEventListener('error',e=>errors.push(e.message));
            for(const type of ['pointerdown','pointermove','pointerup','pointercancel','dragstart'])document.addEventListener(type,e=>trace.push(type),true);
            window.travelPlanRealtime={requestLock:async()=>({granted:true}),releaseLock(){},refreshDay:async dayId=>{
                const row=document.createElement('li');row.dataset.travelPlanItem='';row.dataset.itemId='new';
                document.querySelector('[data-travel-plan-day][data-day-id="'+dayId+'"]').append(row);
            }};
            window.fetch=async(url,request)=>{calls.push({url,body:[...request.body]});return {ok:true,json:async()=>({itemId:'new'})}};`);
        // Keep the existing lower and upper reorder listeners in the event chain.
        for (const file of ['travel-plan-pointer-sort.js', 'travel-plan-reorder.js', 'travel-plan-overview-insert.js', 'travel-plan-overview-reorder.js']) await evaluate(read('js/' + file));
        await evaluate(`document.dispatchEvent(new Event('DOMContentLoaded'))`);
    };
    const state = () => evaluate(`(()=>{const bar=document.querySelector('[data-overview-insert-bar]');return {
        day:bar.dataset.dayId,placement:bar.dataset.insertPlacement,before:bar.dataset.beforeItemId,
        parent:bar.parentElement.closest('[data-planner-summary]').dataset.plannerSummary,
        children:[...bar.parentElement.children].map(n=>n===bar?'BAR':n.dataset.overviewItemId),
        count:document.querySelectorAll('[data-overview-insert-bar]').length,
        artifacts:document.querySelectorAll('.tp-overview-insert-ghost,.tp-overview-insert-placeholder').length,
        dragging:window.travelPlanOverviewInsert.isDragging(),trace,errors,calls,
        radius:getComputedStyle(bar).borderRadius,height:bar.getBoundingClientRect().height,
        background:getComputedStyle(bar).backgroundColor,shadow:getComputedStyle(bar).boxShadow,
        overflow:document.documentElement.scrollWidth>innerWidth}})()`);
    const point = (selector, part = .5) => evaluate(`(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return{x:r.left+r.width/2,y:r.top+r.height*${part}}})()`);
    const mouse = (type, point, extra = {}) => send('Input.dispatchMouseEvent', {type, ...point, ...extra});
    const drag = async (selector, part = .5, cancel = false) => {
        await evaluate('window.trace=[]');
        const from = await point('[data-overview-insert-handle]'), to = await point(selector, part);
        const original = await evaluate(`document.querySelector('[data-overview-insert-bar]').getBoundingClientRect().left`);
        await mouse('mouseMoved', from);
        await mouse('mousePressed', from, {button: 'left', buttons: 1, clickCount: 1});
        for (let step = 1; step <= 14; step++) {
            await mouse('mouseMoved', {x: from.x + (to.x - from.x) * step / 14, y: from.y + (to.y - from.y) * step / 14}, {button: 'left', buttons: 1});
            await wait(20);
        }
        // Follow the visible target after the placeholder/FLIP changes its layout.
        for (let step = 0; step < 4; step++) {
            const end = await point(selector, part);
            await mouse('mouseMoved', {x: end.x + step * .1, y: end.y}, {button: 'left', buttons: 1});
            await wait(160);
        }
        const during = await evaluate(`(()=>{const bar=document.querySelector('[data-overview-insert-bar]');return {left:bar.getBoundingClientRect().left,transform:getComputedStyle(bar).transform,background:getComputedStyle(bar).backgroundColor,count:document.querySelectorAll('[data-overview-insert-bar]').length,ghosts:document.querySelectorAll('.tp-overview-insert-ghost').length}})()`);
        assert.equal(during.left, original); assert.equal(during.count, 1); assert.equal(during.ghosts, 0); assert.equal(during.background, 'rgb(255, 255, 255)');
        if (cancel) {
            await send('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
            await send('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27});
        }
        await mouse('mouseReleased', await point(selector, part), {button: 'left', buttons: 0, clickCount: 1});
        await wait(180);
        const result = await state();
        assert.equal(result.count, 1); assert.equal(result.artifacts, 0); assert.equal(result.dragging, false); assert.deepEqual(result.errors, []);
        return result;
    };
    const first = '[data-overview-item-id="a"]', second = '[data-overview-item-id="b"]';
    const host = day => `[data-planner-summary="${day}"] [data-planner-summary-items]`;
    const setupCards = async saved => {
        await send('Page.navigate', {url: 'about:blank'});
        const frameId = (await send('Page.getFrameTree')).frameTree.frame.id;
        const html = fixture().replace('</style>', `${read('css/travel-plan-reorder.css')}${read('css/travel-plan-detail-rows.css')}
            .travel-plan-lines{display:grid;position:relative;gap:8px;min-height:56px;padding:0;margin:0;list-style:none}
            [data-travel-plan-day]{max-width:760px;width:calc(100% - 32px);margin:12px auto}
            .tp-overview-item{display:block;height:auto;background:transparent;padding:0}
            .tp-detail-item-header{display:flex;gap:8px;align-items:center;min-height:38px}
            </style>`);
        await send('Page.setDocumentContent', {frameId, html});
        await evaluate(`window.records=${JSON.stringify(saved || {1: ['n', 'f', 's', 'm'], 2: [], 3: []})};window.versions={n:0,f:0,s:0,m:0};window.errors=[];window.posts=[];
            window.addEventListener('error',e=>errors.push(e.message));
            window.kind={n:'SCHEDULE',f:'FLIGHT',s:'ACCOMMODATION',m:'TRANSPORT'};
            window.dayHTML=id=>'<section data-travel-plan-day data-day-id="'+id+'" data-plan-date="2026-01-0'+id+'"><h3>DAY '+id+'</h3><ol class="travel-plan-lines">'+records[id].map(item=>
                '<li class="travel-plan-line is-item" data-travel-plan-item data-item-id="'+item+'" data-version="'+versions[item]+'" data-item-type="'+kind[item]+'" data-item-title="'+item+'"><div class="tp-detail-item-shell tp-item-card" data-type="'+kind[item]+'"><div class="tp-item-order-controls"><button type="button" class="tp-item-drag-handle" data-item-drag-handle draggable="false">⠿</button></div><div class="travel-plan-line-body"><div data-item-view class="tp-detail-item-header"><span>'+item+'</span><button type="button" data-item-edit-button>⌄</button></div></div></div>'+['update','move-up','move-down'].map(action=>'<form hidden action="http://tripbora.test/travel-plans/1/days/'+id+'/items/'+item+'/'+action+'"><input name="version" value="'+versions[item]+'"><input name="_csrf" value="test"></form>').join('')+'</li>'
            ).join('')+'<li data-travel-plan-slot data-day-id="'+id+'">+ 일정</li></ol></section>';
            document.querySelectorAll('[data-travel-plan-day]').forEach(day=>day.remove());
            var lowerContainer=document.createElement('div');lowerContainer.dataset.travelPlanDays='';lowerContainer.innerHTML=[1,2,3].map(dayHTML).join('');document.querySelector('[data-plan-id]').append(lowerContainer);
            window.travelPlanRealtime={refreshDay:async id=>{
                const old=document.querySelector('[data-travel-plan-day][data-day-id="'+id+'"]');
                if(old.querySelector('.is-editing'))throw new Error('refresh blocked by editing');
                old.outerHTML=dayHTML(id);
            }};
            window.fetch=async(url,request)=>{
                const [,day,id,action]=/days\\/(\\d+)\\/items\\/([^/]+)\\/(.+)$/.exec(url);
                const body=Object.fromEntries(request.body);posts.push({url,body});
                if(Number(body.version)!==versions[id]||body._csrf!=='test')return {ok:false,status:409};
                if(action==='move'){
                    records[day]=records[day].filter(item=>item!==id);records[body.targetDayId].push(id);versions[id]++;
                }else{
                    const at=records[day].indexOf(id),next=at+(action==='move-up'?-1:1),other=records[day][next];
                    [records[day][at],records[day][next]]=[records[day][next],records[day][at]];versions[id]++;versions[other]+=2;
                }
                return {ok:true,text:async()=>'<main>'+[1,2,3].map(dayHTML).join('')+'</main>'};
            };`);
        for (const file of ['travel-plan-pointer-sort.js', 'travel-plan-reorder.js', 'travel-plan-overview.js', 'travel-plan-overview-insert.js', 'travel-plan-overview-reorder.js']) await evaluate(read('js/' + file));
        await evaluate(`document.dispatchEvent(new Event('DOMContentLoaded'))`); await wait(40);
    };
    const upper = id => `[data-overview-item-id="${id}"]`;
    const lower = id => `[data-travel-plan-item][data-item-id="${id}"]`;
    const lowerHost = day => `[data-travel-plan-day][data-day-id="${day}"] .travel-plan-lines`;
    const cardState = () => evaluate(`({records,errors,posts,upper:[...document.querySelectorAll('[data-planner-summary]')].map(day=>[...day.querySelectorAll('[data-overview-item-id]')].map(row=>row.dataset.overviewItemId)),lower:[...document.querySelectorAll('[data-travel-plan-day]')].map(day=>[...day.querySelectorAll('[data-travel-plan-item]')].map(row=>row.dataset.itemId)),gaps:document.querySelectorAll('.tp-pointer-sort-gap').length})`);
    const dragCard = async (source, target, cancel = false) => {
        const grip = source + ' .tp-item-drag-handle';
        const from = await point(grip), to = await point(target, .15);
        const before = await evaluate(`(()=>{const row=document.querySelector(${JSON.stringify(source)}),rect=row.getBoundingClientRect();return{left:rect.left,width:rect.width,height:rect.height,color:getComputedStyle(row.querySelector('.tp-item-card')).backgroundColor}})()`);
        await mouse('mousePressed', from, {button:'left',buttons:1,clickCount:1});
        for(let step=1;step<=14;step++){
            await mouse('mouseMoved',{x:from.x+(to.x-from.x)*step/14,y:from.y+(to.y-from.y)*step/14},{button:'left',buttons:1});await wait(20);
        }
        for(let step=0;step<3;step++){await mouse('mouseMoved',await point(target,.15),{button:'left',buttons:1});await wait(210);}
        const during=await evaluate(`(()=>{const row=document.querySelector(${JSON.stringify(source)}),rect=row.getBoundingClientRect();return{left:rect.left,width:rect.width,height:rect.height,color:getComputedStyle(row.querySelector('.tp-item-card')).backgroundColor,opacity:getComputedStyle(row).opacity,active:row.classList.contains('tp-pointer-sorting')}})()`);
        assert.equal(during.left,before.left);assert.equal(during.width,before.width);assert.equal(during.height,before.height);assert.equal(during.color,before.color);assert.equal(during.opacity,'1');assert.equal(during.active,true);
        if(cancel){await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});}
        await mouse('mouseReleased',await point(target,.15),{button:'left',buttons:0,clickCount:1});await wait(100);
        const result=await cardState();assert.deepEqual(result.errors,[]);assert.equal(result.gaps,0);
        for(let day=1;day<=3;day++){
            assert.deepEqual(result.lower[day-1],result.records[day]);
            assert.deepEqual(result.upper[day-1],result.records[day].filter(id=>id!=='n'));
        }
        return result;
    };
    const setupEditors = async () => {
        await setupCards();
        await evaluate(`(()=>{
            window.editorData={};window.editPosts=[];window.failNextSave=false;window.delaySave=0;
            const base=dayHTML;
            window.dayHTML=id=>{
                const root=document.createElement('div');root.innerHTML=base(id);
                const day=root.firstElementChild;day.dataset.travelPlanDayId=id;
                day.querySelectorAll('[data-travel-plan-item]').forEach(row=>{
                    const item=row.dataset.itemId,data=editorData[item]||{},form=document.createElement('form');
                    form.hidden=true;form.className='travel-plan-item-editor';form.dataset.travelPlanItemForm='';
                    form.action='http://tripbora.test/travel-plans/1/days/'+id+'/items/'+item+'/update';
                    form.innerHTML='<input type="hidden" name="version"><input type="hidden" name="_csrf" value="test"><input type="hidden" name="itemType">'+
                        '<div data-item-type-fields="GENERAL"><input name="title" data-item-title-input><input type="hidden" name="startTime"></div>'+
                        '<div data-item-type-fields="ROUTE"><input name="details.departurePlace"><input name="details.arrivalPlace"><input type="hidden" name="details.departureDate"><input type="hidden" name="details.departureTime"></div>'+
                        '<div data-item-type-fields="FLIGHT"><input name="details.flightNumber"></div><div data-item-type-fields="ACCOMMODATION"><input name="details.stayName"></div>'+
                        '<textarea data-item-note-input></textarea><textarea name="content" hidden></textarea>'+
                        '<div class="tp-item-editor-actions"><span role="status" data-item-save-status>자동 저장</span><button type="button" data-item-save-retry hidden>다시 시도</button><button type="submit" class="tp-editor-delete" data-item-editor-delete formnovalidate>삭제</button></div>';
                    form.querySelectorAll('[name]').forEach(field=>{field.value=field.name==='version'?versions[item]:data[field.name]??(field.name==='itemType'?kind[item]:field.name==='title'||field.name==='content'?item:field.value);field.defaultValue=field.value;});
                    form.querySelector('[data-item-note-input]').defaultValue=(data.content||'').split('\\n').slice(1).join('\\n');
                    form.querySelector('[data-item-editor-delete]').formAction='http://tripbora.test/travel-plans/1/days/'+id+'/items/'+item+'/delete';
                    row.querySelector('.travel-plan-line-body').append(form);
                });return root.innerHTML;
            };
            document.querySelector('[data-travel-plan-days]').innerHTML=[1,2,3].map(dayHTML).join('');
            Object.assign(travelPlanRealtime,{isLockedByOther:()=>false,hasAlternativeEditing:()=>false,requestLock:async()=>({granted:true}),releaseLock(){},sendDraft(){},
                refreshDay:async id=>{const old=document.querySelector('[data-travel-plan-day][data-day-id="'+id+'"]');if(old.querySelector('.is-editing'))return;
                    const temp=document.createElement('div');temp.innerHTML=dayHTML(id);const fresh=temp.firstElementChild;old.replaceWith(fresh);document.dispatchEvent(new CustomEvent('travelplan:schedule-updated',{detail:{root:fresh}}));}
            });
            const previousFetch=window.fetch;
            window.fetch=async(url,request)=>{
                if(!request?.method)return {ok:true,text:async()=>'<main data-plan-id="1">'+[1,2,3].map(dayHTML).join('')+'</main>'};
                const match=/days\\/(\\d+)\\/items\\/([^/]+)\\/(update|delete)$/.exec(url);
                if(!match)return previousFetch(url,request);
                const [,day,item,action]=match,body=Object.fromEntries(request.body);editPosts.push({day,item,action,body});
                if(delaySave)await new Promise(resolve=>setTimeout(resolve,delaySave));
                if(failNextSave){failNextSave=false;return {ok:false,text:async()=>'저장 충돌: 입력 유지'};}
                if(action==='delete'){records[day]=records[day].filter(id=>id!==item);return {ok:true,text:async()=>'<main data-plan-id="1">'+[1,2,3].map(dayHTML).join('')+'</main>'};}
                if(Number(body.version)!==versions[item])return {ok:false,text:async()=>'version conflict'};
                editorData[item]=body;versions[item]++;return {ok:true,text:async()=>''};
            };
            window.confirm=()=>true;
        })()`);
        await evaluate(read('js/travel-plan-item-autosave.js'));
        await evaluate(`window.schedulerInit=null;window.originalAddListener=document.addEventListener;document.addEventListener=function(type,fn,...args){if(type==='DOMContentLoaded')window.schedulerInit=fn;else originalAddListener.call(this,type,fn,...args);};`);
        await evaluate(read('js/travel-plan-scheduler.js'));
        await evaluate(`document.addEventListener=originalAddListener;window.schedulerInit();`);
    };
    const click = async selector => {
        await evaluate(`document.querySelector(${JSON.stringify(selector)}).scrollIntoView({block:'center'})`);
        const p=await point(selector);await mouse('mousePressed',p,{button:'left',buttons:1,clickCount:1});await mouse('mouseReleased',p,{button:'left',buttons:0,clickCount:1});await wait(60);
    };
    const editField = async (name,value) => evaluate(`(()=>{const form=document.querySelector('[data-travel-plan-item-form]:not([hidden])'),field=form.querySelector('[name="'+${JSON.stringify(name)}+'"]');field.value=${JSON.stringify(value)};field.dispatchEvent(new Event('input',{bubbles:true}));})()`);
    const editorScenario = async width => {
        await setupEditors();
        await wait(30);
        for(const [id,field,value] of [['f','details.flightNumber','KE123'],['s','details.stayName','호텔'],['m','details.departurePlace','인천']]){
            await click('[data-overview-item-edit="'+id+'"]');
            await editField(field,value);await wait(750);
            assert.equal(await evaluate('editorData['+JSON.stringify(id)+']['+JSON.stringify(field)+']'),value);
            const style=await evaluate(`(()=>{const form=document.querySelector('[data-travel-plan-item-form]:not([hidden])'),button=form.querySelector('[data-item-editor-delete]'),css=getComputedStyle(button);return{display:css.display,border:css.borderTopWidth,radius:css.borderRadius,color:css.color,height:button.getBoundingClientRect().height}})()`);
            assert.ok(['inline-flex','flex'].includes(style.display));assert.equal(style.border,'1px');assert.equal(style.radius,'8px');assert.ok(style.height>=34);
            await click('[data-overview-item-edit="'+id+'"]');
        }
        await dragCard(upper('m'),host(2));
        assert.deepEqual(await evaluate('records[2]'),['m']);
        await click(lower('n')+' [data-item-edit-button]');await editField('title','점심 식사');await wait(750);
        assert.equal(await evaluate('editorData.n.title'),'점심 식사');
        await evaluate(`window.delaySave=180`);await editField('title','먼저');await wait(680);await editField('title','최신 일정');await wait(500);
        assert.equal(await evaluate('editorData.n.title'),'최신 일정');
        assert.equal(await evaluate(`document.querySelector('[data-travel-plan-item-form]:not([hidden]) [name=title]').value`),'최신 일정');
        await evaluate(`window.delaySave=0;window.failNextSave=true`);await editField('title','실패 후 보존');await wait(750);
        assert.equal(await evaluate(`document.querySelector('[data-travel-plan-item-form]:not([hidden]) [name=title]').value`),'실패 후 보존');
        assert.equal(await evaluate(`document.querySelector('[data-travel-plan-item-form]:not([hidden]) [data-item-save-status]').textContent`),'저장 실패');
        await click('[data-item-save-retry]:not([hidden])');await wait(100);assert.equal(await evaluate('editorData.n.title'),'실패 후 보존');
        await click(lower('n')+' [data-item-edit-button]');await click('[data-overview-item-edit="f"]');
        await evaluate(`(()=>{const form=document.querySelector('[data-travel-plan-item-form]:not([hidden])');for(const [name,value] of [['details.departureDate','2026-12-31'],['details.departureTime','12:30']]){const field=form.querySelector('[name="'+name+'"]');field.value=value;field.dispatchEvent(new Event('change',{bubbles:true}));}})()`);await wait(100);
        assert.equal(await evaluate(`editorData.f['details.departureTime']`),'12:30',JSON.stringify(await evaluate(`({posts:editPosts,data:editorData.f,active:document.querySelector('[data-travel-plan-item-form]:not([hidden])')?.outerHTML,errors})`)));
        await evaluate(`window.failNextSave=true`);
        await click('[data-item-editor-delete]');await wait(100);
        assert.equal(await evaluate(`document.querySelector('[data-travel-plan-item-form]:not([hidden])')!==null&&document.querySelector('[data-item-id="f"]')!==null`),true);
        await click('[data-item-editor-delete]');await wait(100);
        assert.equal(await evaluate(`document.querySelector('[data-overview-item-id="f"]')===null&&document.querySelector('[data-item-id="f"]')===null`),true);
        await click(lower('n')+' [data-item-edit-button]');await editField('title','');await click('[data-item-editor-delete]');await wait(100);
        assert.equal(await evaluate(`document.querySelector('[data-item-id="n"]')===null`),true);
        await click(lower('s')+' [data-item-edit-button]');await click('[data-item-editor-delete]');await wait(100);
        assert.equal(await evaluate(`document.querySelector('[data-overview-item-id="s"]')===null&&document.querySelector('[data-item-id="s"]')===null`),true);
        assert.deepEqual(await evaluate('errors'),[]);
        t.diagnostic(width+'px: shared upper/lower autosave, serialized latest input, failure/retry, confirmed datetime and deletion sync passed');
    };
    for (const width of [1440, 1280, 390]) {
        if (process.env.TRIPBORA_EDITOR_BROWSER_ONLY === '1') { await size(width); await editorScenario(width); continue; }
        await size(width); await setup();
        let result = await state();
        assert.equal(result.day, '3'); assert.equal(result.count, 1); assert.equal(result.radius, '999px'); assert.equal(result.height, 46); assert.equal(result.overflow, false);
        assert.equal(result.background, 'rgba(0, 0, 0, 0)'); assert.equal(result.shadow, 'none');
        result = await drag(first, .15);
        assert.deepEqual(result.children, ['BAR', 'a', 'b']); assert.equal(result.parent, '1');
        for (const type of ['pointerdown', 'pointermove', 'pointerup']) assert.ok(result.trace.includes(type), `${width}px missing ${type}: ${result.trace}`);
        assert.ok(!result.trace.includes('dragstart'));
        result = await drag(host(2)); assert.equal(result.parent, '2');
        result = await drag(second, .15); assert.deepEqual(result.children, ['a', 'BAR', 'b']); assert.equal(result.before, 'b');
        // Invalid header drop and Escape cancellation must leave the last valid position intact.
        result = await drag('[data-planner-summary="2"] h3'); assert.equal(result.parent, '1'); assert.equal(result.before, 'b');
        result = await drag(host(3), .5, true); assert.equal(result.parent, '1'); assert.equal(result.before, 'b'); assert.ok(!result.trace.includes('drop'));
        result = await drag(host(3)); assert.equal(result.parent, '3');
        result = await drag(second, .15); assert.deepEqual(result.children, ['a', 'BAR', 'b']);
        const button = await point('[data-overview-insert-type="ACCOMMODATION"]');
        await mouse('mousePressed', button, {button: 'left', buttons: 1, clickCount: 1});
        await mouse('mouseReleased', button, {button: 'left', buttons: 0, clickCount: 1}); await wait(80);
        result = await state(); assert.equal(result.calls.length, 1);
        assert.ok(result.calls[0].url.endsWith('/days/1/items'), JSON.stringify(result.calls));
        assert.equal(Object.fromEntries(result.calls[0].body).beforeItemId, 'b');
        assert.equal(Object.fromEntries(result.calls[0].body).itemType, 'ACCOMMODATION');
        await setup(); result = await state(); assert.equal(result.parent, '3'); assert.equal(result.count, 1);
        t.diagnostic(`${width}px: first/empty/between/header rejection/cancel/retry/add anchor/reinitialization passed`);
        await setupCards();
        const initialOrder = (await cardState()).records;
        let rejected = await dragCard(upper('f'),'[data-planner-summary="3"] h3'); assert.deepEqual(rejected.records,initialOrder);
        let cards = await dragCard(upper('f'),upper('m'));
        assert.deepEqual(cards.records[1],['n','s','f','m']);
        cards = await dragCard(upper('s'),host(2)); assert.deepEqual(cards.records[2],['s']);
        cards = await dragCard(upper('m'),upper('f')); assert.deepEqual(cards.records[1],['m','n','f']);
        cards = await dragCard(lower('n'),lower('s')); assert.deepEqual(cards.records[2],['n','s']);
        cards = await dragCard(lower('f'),lowerHost(3)); assert.deepEqual(cards.records[3],['f']);
        cards = await dragCard(lower('s'),lowerHost(3)); assert.deepEqual(cards.records[3],['s','f']);
        const saved = JSON.parse(JSON.stringify(cards.records));
        cards = await dragCard(lower('m'),lowerHost(2),true); assert.deepEqual(cards.records,saved);
        cards = await dragCard(lower('m'),lowerHost(2)); assert.deepEqual(cards.records[1],[]);
        const stored=cards.records;await setupCards(stored);cards=await cardState();assert.deepEqual(cards.records,stored);assert.deepEqual(cards.lower,Object.values(stored));
        t.diagnostic(`${width}px: upper FLIGHT/STAY/MOVE, lower all types, full-order/cross-DAY/readback/sync/cancel/opaque-color/X lock passed`);
        await editorScenario(width);
    }
});
