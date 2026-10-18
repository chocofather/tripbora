// Actual production markup/CSS/scripts in Chrome; HTTP/STOMP are fixtures, not a running application.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const {deflateSync} = require('node:zlib');
const root = path.resolve(__dirname, '../../..');
const chromePath = 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const read = file => fs.readFileSync(path.join(root, 'src/main/resources', file), 'utf8');
// An inspectable 16:9 test photo: four colored quadrants, encoded without an image dependency.
function fixturePng() {
    const crc32 = bytes => { let crc = 0xffffffff; for (const byte of bytes) {crc ^= byte;for(let bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)?0xedb88320:0);}return (crc^0xffffffff)>>>0; };
    const chunk = (name, data) => {const type=Buffer.from(name),size=Buffer.alloc(4),crc=Buffer.alloc(4);size.writeUInt32BE(data.length);crc.writeUInt32BE(crc32(Buffer.concat([type,data])));return Buffer.concat([size,type,data,crc]);};
    const width=320,height=180,rows=Buffer.alloc((width*3+1)*height),header=Buffer.alloc(13);
    header.writeUInt32BE(width);header.writeUInt32BE(height,4);header[8]=8;header[9]=2;
    const colors=[[100,160,220],[235,170,95],[110,190,150],[170,135,205]];
    for(let y=0;y<height;y++)for(let x=0;x<width;x++){const color=colors[(y>=height/2?2:0)+(x>=width/2?1:0)];for(let c=0;c<3;c++)rows[y*(width*3+1)+1+x*3+c]=color[c];}
    return Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',header),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]).toString('base64');
}
const png = fixturePng();

test('actual chat emoji, poll and image flows remain usable across desktop and mobile', {skip: !fs.existsSync(chromePath), timeout: 120000}, async t => {
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-chat-'));
    const chrome = spawn(chromePath, ['--headless=new','--no-first-run','--no-default-browser-check','--disable-gpu','--remote-debugging-pipe',`--user-data-dir=${profile}`], {stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
    let seq = 0, buffer = '', session, html;
    const pending = new Map(), errors = [];
    const send = (method, params = {}, target = session) => new Promise((resolve,reject) => {
        const id = ++seq; pending.set(id,{resolve,reject});
        chrome.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');
    });
    chrome.stdio[4].on('data', data => {
        buffer += data; let at;
        while ((at=buffer.indexOf('\0'))>=0) {
            const raw=buffer.slice(0,at); buffer=buffer.slice(at+1); if(!raw)continue;
            const message=JSON.parse(raw), task=pending.get(message.id);
            if(task) {pending.delete(message.id);message.error?task.reject(new Error(JSON.stringify(message.error))):task.resolve(message.result);}
            else if(message.method==='Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text+': '+message.params.exceptionDetails.exception?.description);
            else if(message.method==='Runtime.consoleAPICalled'&&message.params.type==='error') errors.push(message.params.args.map(argument=>argument.value||argument.description).join(' '));
            else if(message.method==='Fetch.requestPaused') {
                const isPage = message.params.request.url.endsWith('/fixture');
                send('Fetch.fulfillRequest',{requestId:message.params.requestId,responseCode:200,responseHeaders:[{name:'Content-Type',value:isPage?'text/html; charset=utf-8':'image/png'}],body:isPage?Buffer.from(html).toString('base64'):png}).catch(error=>errors.push(error.message));
            }
        }
    });
    chrome.on('exit',()=>{pending.forEach(p=>p.reject(new Error('Chrome exited')));pending.clear();});
    t.after(async()=>{chrome.kill();for(let i=0;i<10;i++){try{fs.rmSync(profile,{recursive:true,force:true});break;}catch{await new Promise(r=>setTimeout(r,100));}}});
    const target=await send('Target.createTarget',{url:'about:blank'},null);
    session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;
    await send('Page.enable');await send('Runtime.enable');await send('Fetch.enable',{patterns:[{urlPattern:'https://tripbora.test/*'}]});
    const evaluate=async expression=>{const result=await send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));return result.result?.value;};
    const click=selector=>evaluate(`document.querySelector(${JSON.stringify(selector)}).click()`);
    const settle=()=>evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');
    const waitFor=async expression=>{for(let i=0;i<100;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,20));}assert.fail('Timed out: '+expression+' errors: '+JSON.stringify(errors)+' state: '+await evaluate('document.querySelector("[data-travel-plan-chat]")?.outerHTML.slice(0,300)+" / "+document.querySelector("[data-travel-plan-chat-emoji-panel]")?.outerHTML.slice(0,400)'));};
    const escape=async()=>{await send('Input.dispatchKeyEvent',{type:'keyDown',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await send('Input.dispatchKeyEvent',{type:'keyUp',key:'Escape',code:'Escape',windowsVirtualKeyCode:27});await settle();};
    const template=read('templates/travelplan/detail.html');
    let markup=template.slice(template.indexOf('<div class="travel-plan-chat"'),template.lastIndexOf('</th:block>'));
    markup=markup.replace(/\s+th:[\w-]+="[^"]*"/g,'').replace(/data-travel-plan-chat(?=[\s>])/,'data-travel-plan-chat data-chat-plan-id="42" data-current-member-id="7"');
    const css=['style.css','travel-plan.css','travel-plan-chat.css','travel-plan-poll.css','travel-plan-emoji.css','travel-plan-chat-images.css'].map(name=>read('static/css/'+name)).join('\n');
    html=`<!doctype html><html><head><meta charset="utf-8"><style>${css}</style></head><body><main class="travel-plan-page is-planning">${markup}</main></body></html>`;
    const report=path.join(root,'build/reports/travel-plan-chat-enhancements');fs.mkdirSync(report,{recursive:true});
    const files=Array.from({length:5},(_,i)=>{const file=path.join(report,`fixture-${i}.png`);fs.writeFileSync(file,Buffer.from(png,'base64'));return file;});
    const setFiles=async count=>{
        const dom=await send('DOM.getDocument');const found=await send('DOM.querySelector',{nodeId:dom.root.nodeId,selector:'[data-chat-image-files]'});
        await send('DOM.setFileInputFiles',{nodeId:found.nodeId,files:files.slice(0,count)});await settle();
    };
    const imagesLoaded=async()=>waitFor('[...document.querySelectorAll("img")].every(image=>image.complete&&image.naturalWidth>0)');
    const capture=async name=>{await imagesLoaded();await settle();fs.writeFileSync(path.join(report,name+'.png'),Buffer.from((await send('Page.captureScreenshot',{format:'png'})).data,'base64'));};
    for(const width of [1440,1280,768,430,390,360,320]) {
        await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
        await send('Page.navigate',{url:'https://tripbora.test/fixture'});
        await waitFor('!!document.querySelector("[data-travel-plan-chat-input]")');
        await evaluate(`localStorage.clear();window.imagePosts=[];window.failNextImage=false;window.holdImage=false;window.releaseImage=null;
            const poll={id:1,title:'점심 메뉴를 골라 주세요',createdByDisplayName:'여행 친구',status:'OPEN',selectionType:'SINGLE',votedMemberCount:2,activeMemberCount:4,resultsVisible:true,options:[{id:11,content:'한식',voteCount:2},{id:12,content:'파스타',voteCount:0}]};
            window.textSends=[];window.reconnectCallbacks=[];window.travelPlanRealtime={isConnected(){return true;},subscribeChat(callback){window.chatCallback=callback;},subscribePolls(){},subscribeConnection(){},onReconnected(callback){reconnectCallbacks.push(callback);},markChatRead(){},sendChat(content){window.textSends.push(content);setTimeout(()=>window.chatCallback({type:'MESSAGE_CREATED',message:{id:89,memberId:7,displayName:'나',content,createdAt:'2026-10-19T12:00:00'}}),0);return true;}};
            window.fetch=async(url,options={})=>{
                url=String(url);let value={};
                if(url.includes('/data/travel-plan-emoji.json'))value=${read('static/data/travel-plan-emoji.json')};
                else if(url.endsWith('/chat/unread'))value={unreadCount:0};
                else if(url.includes('/chat/timeline'))value={items:[],hasMore:false};
                else if(url.endsWith('/polls/counts'))value={openCount:1,closedCount:1};
                else if(url.endsWith('/polls/open'))value={polls:[poll]};
                else if(url.endsWith('/polls/closed'))value={polls:[{...poll,id:2,status:'CLOSED',winnerSummary:'한식'}]};
                else if(url.endsWith('/polls/2'))value={...poll,id:2,status:'CLOSED',winnerSummary:'한식'};
                else if(url.endsWith('/polls/1'))value=poll;
                else if(url.endsWith('/chat/image-messages')){
                    const body=options.body;window.imagePosts.push({uuid:body.get('clientRequestId'),count:body.getAll('images').length,text:body.get('content')});
                    if(window.holdImage)await new Promise(r=>window.releaseImage=r);
                    if(window.failNextImage){window.failNextImage=false;throw new TypeError('fixture lost response');}
                    if(window.denyNextImage){window.denyNextImage=false;return {ok:false,status:403,json:async()=>({message:'일시적인 요청 확인 실패'})};}
                    const id=window.imagePosts.length===1?90:91;
                    value={id,memberId:7,displayName:'나',content:body.get('content'),messageType:'IMAGE',createdAt:'2026-10-19T12:00:00',images:body.getAll('images').map((file,i)=>({id:i+1,url:'/travel-plans/42/chat/messages/'+id+'/images/'+(i+1),width:320,height:180}))};
                }
                return {ok:true,status:200,json:async()=>value};
            };`);
        for(const script of ['travel-plan-scheduler.js','travel-plan-emoji-picker.js','travel-plan-chat-images.js','travel-plan-chat.js','travel-plan-chat-drag.js','travel-plan-poll.js'])await evaluate(read('static/js/'+script));
        await evaluate('document.dispatchEvent(new Event("DOMContentLoaded"))');
        await click('[data-travel-plan-chat-toggle]');await settle();
        const panelGeometry=await evaluate(`(()=>{const p=document.querySelector('[data-travel-plan-chat-panel]'),r=p.getBoundingClientRect();return{height:r.height,width:r.width,top:r.top,bottom:r.bottom,scrollHeight:p.scrollHeight,clientHeight:p.clientHeight}})()`);
        assert.equal(panelGeometry.height,720,'chat uses expanded 720px height when viewport allows');
        assert.ok(panelGeometry.top>=0 && panelGeometry.bottom<=950);
        assert.ok(panelGeometry.scrollHeight<=panelGeometry.clientHeight+1,'only message area scrolls');
        if(width>=1280)assert.equal(panelGeometry.width,440,'chat width is unchanged');
        await evaluate('document.querySelector("[data-travel-plan-chat-input]").value="ESC 후에도 남는 초안"');
        await evaluate('document.querySelector("[data-travel-plan-chat-input]").dispatchEvent(new CompositionEvent("compositionstart"))');
        await escape();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'IME composition ESC does not close chat');
        await evaluate('document.querySelector("[data-travel-plan-chat-input]").dispatchEvent(new CompositionEvent("compositionend"))');
        await evaluate('const externalEditor=document.createElement("textarea");externalEditor.id="external-editor";externalEditor.addEventListener("keydown",e=>{if(e.key==="Escape")window.externalEscape=true;});document.body.append(externalEditor);externalEditor.focus()');
        await escape();
        assert.equal(await evaluate('window.externalEscape'),true,'planner editor keeps its own ESC handling');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false);
        await evaluate('document.querySelector("#external-editor").remove();document.querySelector("[data-travel-plan-chat-input]").focus()');
        await escape();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),true,'ESC closes chat with no nested UI');
        assert.equal(await evaluate('document.activeElement.hasAttribute("data-travel-plan-chat-toggle")'),true);
        await click('[data-travel-plan-chat-toggle]');await settle();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-input]").value'),'ESC 후에도 남는 초안');
        await click('[data-chat-attach-toggle]');await escape();
        assert.equal(await evaluate('document.querySelector("[data-chat-attach-menu]").hidden'),true);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'attachment ESC does not close chat');
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false,`${width} document overflow`);
        await evaluate(`{const input=document.querySelector('[data-travel-plan-chat-input]');input.value='앞뒤';input.setSelectionRange(1,1);}`);
        await click('[data-travel-plan-chat-emoji-toggle]');await waitFor('document.querySelectorAll(".travel-plan-chat-emoji-item").length>0');
        assert.equal(await evaluate('document.querySelectorAll(".tp-emoji-category").length'),10);
        for(const query of ['비행기','AIRPLANE']){
            await evaluate(`{const search=document.querySelector('.tp-emoji-search');search.value=${JSON.stringify(query)};search.dispatchEvent(new Event('input'));}`);
            assert.ok(await evaluate(`[...document.querySelectorAll('.travel-plan-chat-emoji-item')].some(b=>b.textContent==='✈️')`));
        }
        await evaluate(`[...document.querySelectorAll('.travel-plan-chat-emoji-item')].find(b=>b.textContent==='✈️').click()`);
        await evaluate(`[...document.querySelectorAll('.travel-plan-chat-emoji-item')].find(b=>b.textContent==='✈️').click()`);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-input]").value'),'앞✈️✈️뒤');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-emoji-panel]").hidden'),false);
        await click('[data-emoji-category="recent"]');
        assert.deepEqual(await evaluate('JSON.parse(localStorage.getItem("travelPlan.chat.recentEmoji"))'),['✈️']);
        const bounds=await evaluate(`(()=>{const p=document.querySelector('.tp-emoji-picker'),r=p.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,overflow:p.scrollWidth>p.clientWidth,scroll:document.querySelector('.tp-emoji-scroll').clientHeight}})()`);
        assert.ok(bounds.left>=0&&bounds.right<=width&&bounds.top>=0&&bounds.bottom<=950,JSON.stringify(bounds));assert.equal(bounds.overflow,false);assert.ok(bounds.scroll>30);
        if([1440,390].includes(width))await capture('emoji-'+width);
        await escape();assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-emoji-panel]").hidden'),true);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'emoji ESC closes only picker');
        await click('[data-travel-plan-chat-emoji-toggle]');await evaluate('document.body.click()');assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-emoji-panel]").hidden'),true);
        await click('[data-travel-plan-poll-entry]');await waitFor('document.querySelectorAll(".travel-plan-poll-card").length===1');
        await click('[data-travel-plan-poll-create-open]');
        assert.equal(await evaluate('document.querySelectorAll("[data-travel-plan-poll-options] li").length'),2);
        await click('[data-travel-plan-poll-add]');assert.equal(await evaluate('document.querySelectorAll("[data-travel-plan-poll-options] li").length'),3);
        await evaluate('document.querySelector("[data-travel-plan-poll-options] li:last-child button").click()');
        assert.equal(await evaluate('document.querySelectorAll("[data-travel-plan-poll-options] li").length'),2);
        await click('[data-travel-plan-poll-selection][value="MULTIPLE"]');assert.equal(await evaluate('document.querySelector("[data-travel-plan-poll-selection]:checked").value'),'MULTIPLE');
        if([1440,390].includes(width))await capture('poll-create-'+width);
        await click('[data-travel-plan-poll-create-view] [data-travel-plan-poll-back]');await click('[data-travel-plan-poll-tab="CLOSED"]');await waitFor('document.querySelector("[data-poll-id]")?.dataset.pollId==="2"');
        await click('.travel-plan-poll-card');await waitFor('!!document.querySelector(".travel-plan-poll-winner")');
        assert.equal(await evaluate('document.querySelectorAll(".travel-plan-poll-result-row").length'),2);
        assert.equal(await evaluate('document.querySelectorAll("[data-travel-plan-poll-choice]").length'),0);
        const modalOverflow=await evaluate('document.querySelector(".travel-plan-poll-modal-panel").scrollWidth>document.querySelector(".travel-plan-poll-modal-panel").clientWidth');assert.equal(modalOverflow,false);
        if([1440,390].includes(width))await capture('poll-result-'+width);
        await escape();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'poll ESC closes only poll');
        await evaluate('document.querySelector("[data-travel-plan-chat-input]").value="기존 텍스트 채팅 회귀";document.querySelector("[data-travel-plan-chat-form]").requestSubmit()');
        await waitFor('document.querySelector("[data-message-id=\\"89\\"]")?.textContent.includes("기존 텍스트 채팅 회귀")');
        assert.deepEqual(await evaluate('window.textSends'),['기존 텍스트 채팅 회귀']);
        await evaluate('document.querySelector("[data-travel-plan-chat-input]").value="사진 다섯 장"');
        await setFiles(5);assert.equal(await evaluate('document.querySelectorAll(".tp-chat-preview-tile").length'),5);
        await click('.tp-chat-preview-remove');assert.equal(await evaluate('document.querySelectorAll(".tp-chat-preview-tile").length'),4);
        await setFiles(1);assert.equal(await evaluate('document.querySelectorAll(".tp-chat-preview-tile").length'),5);
        if([1440,390].includes(width))await capture('image-preview-'+width);
        await evaluate('window.holdImage=true;document.querySelector("[data-travel-plan-chat-form]").requestSubmit();document.querySelector("[data-travel-plan-chat-form]").requestSubmit()');
        assert.equal(await evaluate('imagePosts.length'),1);assert.equal(await evaluate('imagePosts[0].count'),5);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-input]").readOnly'),true);
        await evaluate('window.holdImage=false;window.releaseImage()');await waitFor('document.querySelectorAll(".tp-chat-image-tile").length===5');
        assert.equal(await evaluate('document.querySelector("[data-chat-image-preview]").hidden'),true);
        if([1440,390].includes(width))await capture('image-messages-'+width);
        await click('.tp-chat-image-tile');await waitFor('!!document.querySelector("dialog[open]")');
        await imagesLoaded();
        const viewerImage=await evaluate(`(()=>{const image=document.querySelector('dialog img'),r=image.getBoundingClientRect();return {naturalWidth:image.naturalWidth,naturalHeight:image.naturalHeight,width:r.width,height:r.height,left:r.left,right:r.right,top:r.top,bottom:r.bottom}})()`);
        assert.equal(viewerImage.naturalWidth,320);assert.equal(viewerImage.naturalHeight,180);assert.ok(viewerImage.width>100&&viewerImage.height>50);assert.ok(viewerImage.left>=0&&viewerImage.right<=width&&viewerImage.top>=0&&viewerImage.bottom<=950);
        await click('.tp-chat-viewer-next');assert.equal(await evaluate('document.querySelector("dialog header p").textContent'),'2 / 5');
        await click('.tp-chat-viewer-previous');assert.equal(await evaluate('document.querySelector("dialog header p").textContent'),'1 / 5');
        if([1440,390].includes(width))await capture('image-viewer-'+width);
        await click('[data-travel-plan-poll-entry]');
        await escape();assert.equal(await evaluate('document.querySelector("dialog")'),null);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-poll-modal]").hidden'),false,'viewer ESC leaves underlying poll open');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'viewer ESC closes only viewer');
        await escape();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-poll-modal]").hidden'),true);
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-panel]").hidden'),false,'next ESC closes only poll');
        await setFiles(1);await evaluate('window.failNextImage=true;document.querySelector("[data-travel-plan-chat-form]").requestSubmit()');
        await waitFor('document.querySelector("[data-travel-plan-chat-send]").textContent==="재시도"');
        const requestId=await evaluate('imagePosts.at(-1).uuid');
        await evaluate('window.denyNextImage=true;document.querySelector("[data-travel-plan-chat-form]").requestSubmit()');
        await waitFor('document.querySelector("[data-travel-plan-chat-form]").getAttribute("aria-busy")==="false"');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-input]").readOnly'),true,'uncertain earlier delivery stays locked after a denied retry');
        await evaluate('document.querySelector("[data-travel-plan-chat-form]").requestSubmit()');await waitFor('document.querySelector("[data-chat-image-preview]").hidden');
        assert.equal(await evaluate('imagePosts.at(-1).uuid'),requestId);assert.equal(await evaluate('imagePosts.length'),4);
        await waitFor('document.querySelectorAll(".tp-chat-image-grid.is-single .tp-chat-image-tile").length===1');
        await evaluate(`chatCallback({type:'MESSAGE_CREATED',message:{id:92,memberId:8,displayName:'친구',content:'사진과 의견',createdAt:'2026-10-19T12:01:00',images:[{id:1,url:'/travel-plans/42/chat/messages/92/images/1',width:320,height:180}]}})`);
        const photoStyles=await evaluate(`[...document.querySelectorAll('.travel-plan-chat-bubble:has(.tp-chat-image-grid)')].map(b=>{const s=getComputedStyle(b),p=b.querySelector('.travel-plan-chat-content'),c=getComputedStyle(p);return{background:s.backgroundColor,padding:s.padding,border:s.borderTopWidth,captionHidden:p.hidden,captionBackground:c.backgroundColor}})`);
        assert.ok(photoStyles.every(s=>s.background==='rgba(0, 0, 0, 0)' && s.padding==='0px' && s.border==='0px'),'image wrappers are transparent with no padding/border');
        assert.ok(photoStyles.some(s=>!s.captionHidden && s.captionBackground==='rgb(242, 236, 252)'),'image caption alone retains own-message bubble');
        assert.ok(photoStyles.some(s=>s.captionHidden),'image-only message has no caption bubble');
        assert.ok(photoStyles.some(s=>!s.captionHidden && s.captionBackground==='rgb(246, 245, 248)'),'other sender image caption retains neutral bubble');
        assert.equal(await evaluate(`getComputedStyle(document.querySelector('[data-message-id="89"] .travel-plan-chat-bubble')).backgroundColor`),'rgb(242, 236, 252)','text-only bubble keeps purple background');
        await imagesLoaded();
        await click('.tp-chat-image-grid.is-single .tp-chat-image-tile');await imagesLoaded();
        await evaluate('reconnectCallbacks.forEach(callback=>callback())');await settle();
        assert.equal(await evaluate('document.querySelector("dialog")'),null,'reconnection clears any stale image viewer before replacing history');
        await evaluate(`for(let i=0;i<30;i++)chatCallback({type:'MESSAGE_CREATED',message:{id:200+i,memberId:8,displayName:'친구',content:'스크롤 확인 '+i,createdAt:1792400000000+i*60000}})`);await settle();
        const fixedPositions=await evaluate(`(()=>{const h=document.querySelector('.travel-plan-chat-header').getBoundingClientRect(),f=document.querySelector('.travel-plan-chat-form').getBoundingClientRect();return{header:h.top,form:f.bottom}})()`);
        await evaluate(`document.querySelector('[data-travel-plan-chat-body]').scrollTop=0`);await settle();
        await evaluate(`chatCallback({type:'MESSAGE_CREATED',message:{id:300,memberId:8,displayName:'친구',content:'이전 대화를 읽는 중 도착',createdAt:1792500000000}})`);await settle();
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-body]").scrollTop'),0,'incoming message does not jump while reading old history');
        assert.equal(await evaluate('document.querySelector("[data-travel-plan-chat-jump]").hidden'),false);
        await click('[data-travel-plan-chat-jump]');await settle();
        await evaluate(`chatCallback({type:'MESSAGE_CREATED',message:{id:301,memberId:8,displayName:'친구',content:'아래에서는 자동 스크롤',createdAt:1792500060000}})`);await settle();
        assert.ok(await evaluate(`(()=>{const b=document.querySelector('[data-travel-plan-chat-body]');return b.scrollHeight-b.scrollTop-b.clientHeight<2})()`),'new messages keep scrolling only at bottom');
        const afterScroll=await evaluate(`(()=>{const h=document.querySelector('.travel-plan-chat-header').getBoundingClientRect(),f=document.querySelector('.travel-plan-chat-form').getBoundingClientRect(),p=document.querySelector('[data-travel-plan-chat-panel]');return{header:h.top,form:f.bottom,outerScroll:p.scrollHeight>p.clientHeight+1}})()`);
        assert.equal(afterScroll.header,fixedPositions.header);assert.equal(afterScroll.form,fixedPositions.form);assert.equal(afterScroll.outerScroll,false);
        await send('Emulation.setDeviceMetricsOverride',{width,height:500,deviceScaleFactor:1,mobile:false});await settle();
        assert.ok(await evaluate(`(()=>{const p=document.querySelector('[data-travel-plan-chat-panel]').getBoundingClientRect(),f=document.querySelector('.travel-plan-chat-form').getBoundingClientRect();return p.top>=0&&p.bottom<=500&&f.bottom<=500})()`),'short viewport keeps header/composer visible');
        if(width<=768){
            await evaluate(`window.originalVisualViewport=window.visualViewport;Object.defineProperty(window,'visualViewport',{configurable:true,value:{height:360,width:innerWidth,offsetTop:0,offsetLeft:0}});dispatchEvent(new Event('resize'))`);await settle();
            assert.ok(await evaluate(`(()=>{const p=document.querySelector('[data-travel-plan-chat-panel]').getBoundingClientRect(),f=document.querySelector('.travel-plan-chat-form').getBoundingClientRect();return p.top>=0&&p.bottom<=360&&f.bottom<=360})()`),'visual viewport keyboard simulation keeps composer visible');
            await evaluate(`Object.defineProperty(window,'visualViewport',{configurable:true,value:originalVisualViewport});dispatchEvent(new Event('resize'))`);await settle();
        }
        assert.equal(await evaluate('document.documentElement.scrollWidth>innerWidth'),false);
        assert.deepEqual(errors,[]);
        t.diagnostic(`${width}px: Unicode search/category/recent/cursor/ESC/bounds; poll editor/results; native 5-file preview/cancel, busy submit guard, gallery/viewer and same-UUID retry passed (mock HTTP/STOMP)`);
    }
});
