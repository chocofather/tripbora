// First run StaticResourceRenderingTest. Assets below are actual Spring HTTP responses,
// including transformed CSS; no inline replacement of CSS/JS is used in this test.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const http = require('node:http');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const root = path.resolve(__dirname, '../../..');
const fixtures = path.join(root, 'build/reports/static-resources');
const chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe';

test('rendered hashed URLs load over HTTP without script errors, oversized map icons or overflow',
    {skip: !fs.existsSync(chrome), timeout: 60000}, async t => {
        const requests = [];
        const server = http.createServer((req, res) => {
            const url = new URL(req.url, 'http://localhost');
            if (url.pathname === '/list' || url.pathname === '/course') {
                const file = url.pathname === '/list' ? '_travel-plans.html' : 'course.html';
                let html = fs.readFileSync(path.join(fixtures, file), 'utf8');
                // Offline UI check: omit only third-party font stylesheets.
                html = html.replace(/<link\b[^>]*href="https:\/\/[^>]*>/g, '');
                html = html.replace('<head>', `<head><script>
                    window.resourceErrors=[];
                    addEventListener('error', e => {if(e.message) resourceErrors.push(e.message)});
                    addEventListener('unhandledrejection', e => resourceErrors.push(String(e.reason)));
                    addEventListener('load', () => setTimeout(() => {
                        const result={width:innerWidth,overflow:document.documentElement.scrollWidth>innerWidth,
                            icons:[...document.querySelectorAll('.tp-trip-icon')].map(e=>e.getBoundingClientRect().width),
                            assets:[...document.querySelectorAll('link[href^="/css/"],script[src^="/js/"]')].map(e=>e.getAttribute('href')||e.getAttribute('src')),
                            errors:resourceErrors,cta:!!document.querySelector('[data-course-plan-open]')};
                        const pre=document.createElement('pre');pre.id='resource-results';pre.textContent=JSON.stringify(result);document.body.append(pre);
                    },100));
                </script>`);
                res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'}); res.end(html); return;
            }
            let file = path.resolve(fixtures, 'assets', '.' + url.pathname);
            const assetRoot = path.resolve(fixtures, 'assets') + path.sep;
            if (!file.startsWith(assetRoot)) {res.writeHead(400);res.end();return;}
            // Fonts/images are not the CSS/JS under test. Resolve their hashed URLs locally.
            if (!fs.existsSync(file) && /^\/(images|fonts)\//.test(url.pathname)) {
                file = path.join(root, 'src/main/resources/static', url.pathname.replace(/-[a-f0-9]{32}(?=\.)/, ''));
            }
            const extension = path.extname(file);
            const mime = {'.css':'text/css','.js':'text/javascript','.svg':'image/svg+xml','.woff2':'font/woff2','.png':'image/png','.jpg':'image/jpeg'}[extension] || 'application/octet-stream';
            if (fs.existsSync(file) && fs.statSync(file).isFile()) {
                requests.push({url: url.pathname, status:200, mime});
                res.writeHead(200, {'Content-Type':mime});res.end(fs.readFileSync(file));return;
            }
            // The course comment read endpoint is outside this static-resource test.
            if (url.pathname.includes('/comments')) {res.writeHead(200, {'Content-Type':'text/html'});res.end('');return;}
            requests.push({url:url.pathname,status:404,mime});res.writeHead(404);res.end();
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        t.after(() => server.close());
        const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-resource-'));
        const browser = spawn(chrome, ['--headless=new','--no-first-run','--no-default-browser-check',
            '--disable-gpu','--remote-debugging-pipe',`--user-data-dir=${profile}`],
            {stdio:['ignore','ignore','ignore','pipe','pipe'],windowsHide:true});
        let sequence=0, buffer='', session, loadComplete;
        const pending = new Map();
        browser.stdio[4].on('data', chunk => {
            buffer+=chunk;
            let at;
            while ((at=buffer.indexOf('\0'))>=0) {
                const raw=buffer.slice(0,at);buffer=buffer.slice(at+1);if(!raw)continue;
                const message=JSON.parse(raw), task=pending.get(message.id);
                if(task){pending.delete(message.id);message.error?task.reject(new Error(JSON.stringify(message.error))):task.resolve(message.result);}
                if(message.method==='Page.loadEventFired' && loadComplete) loadComplete();
            }
        });
        const send=(method,params={},target=session)=>new Promise((resolve,reject)=>{
            const id=++sequence;pending.set(id,{resolve,reject});
            browser.stdio[3].write(JSON.stringify({id,method,params,...(target?{sessionId:target}:{})})+'\0');
        });
        browser.on('exit',()=>{pending.forEach(task=>task.reject(new Error('Chrome exited')));pending.clear();});
        t.after(async()=>{
            browser.kill();
            assert.ok(path.resolve(profile).startsWith(path.resolve(os.tmpdir())+path.sep+'tripbora-resource-'));
            for(let i=0;i<10;i++){try{fs.rmSync(profile,{recursive:true,force:true});break;}catch{await new Promise(r=>setTimeout(r,100));}}
        });
        const target=await send('Target.createTarget',{url:'about:blank'},null);
        session=(await send('Target.attachToTarget',{targetId:target.targetId,flatten:true},null)).sessionId;
        await send('Page.enable');
        for (const page of ['list', 'course']) for (const width of [360,390,430,768,1440]) {
            await send('Emulation.setDeviceMetricsOverride',{width,height:950,deviceScaleFactor:1,mobile:false});
            const loaded=new Promise(resolve=>{loadComplete=resolve;});
            await send('Page.navigate',{url:`http://127.0.0.1:${server.address().port}/${page}`});
            await loaded;
            const result=await send('Runtime.evaluate',{
                expression:"new Promise(resolve=>setTimeout(()=>resolve(JSON.parse(document.getElementById('resource-results').textContent)),250))",
                awaitPromise:true,returnByValue:true});
            assert.equal(result.exceptionDetails,undefined,JSON.stringify(result.exceptionDetails));
            const state=result.result.value;
            assert.equal(state.width,width,'actual viewport width');
            assert.equal(state.overflow,false,`${page} ${width}: no horizontal overflow`);
            assert.deepEqual(state.errors,[],`${page} ${width}: no JS errors`);
            assert.ok(state.assets.length>0);
            state.assets.forEach(asset => assert.match(asset,/^\/(css|js)\/.+-[a-f0-9]{32}\.(css|js)$/));
            if (page==='list') assert.deepEqual(state.icons,[32]);
            else assert.equal(state.cta,true);
            t.diagnostic(`${page} ${width}px: ${state.assets.length} hashed assets, no JS errors/overflow`);
        }
        const assets = requests.filter(r => /^\/(css|js)\//.test(r.url));
        assert.ok(assets.length>0);
        assert.ok(assets.every(r=>r.status===200 && ['text/css','text/javascript'].includes(r.mime)),JSON.stringify(assets.filter(r=>r.status!==200)));
    });
