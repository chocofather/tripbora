const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const http = require('node:http');
const {spawn} = require('node:child_process');
const chrome = process.env.TRIPBORA_CHROME || 'C:/Program Files/Google/Chrome/Application/chrome.exe';

test('popular responsive images settle, including thumbnail and original 404s',
    {skip: !fs.existsSync(chrome), timeout: 30000}, async t => {
        const requests = [];
        const server = http.createServer((req, res) => {
            requests.push(req.url);
            if (req.url === '/home-popular-images.js') {
                res.writeHead(200, {'Content-Type': 'text/javascript'});
                res.end(fs.readFileSync('src/main/resources/static/js/home-popular-images.js')); return;
            }
            if (req.url.endsWith('.svg')) {
                res.writeHead(200, {'Content-Type': 'image/svg+xml'});
                res.end('<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="800"><rect width="1200" height="800" fill="skyblue"/></svg>'); return;
            }
            if (req.url !== '/') { res.writeHead(404); res.end(); return; }
            res.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'});
            res.end(`<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>.recommend-card-media{width:80vw;height:200px}img{width:100%;height:100%;object-fit:cover}.is-image-unavailable{visibility:hidden}</style>
                <script>window.imageLoads=0;window.domReady=false;window.pageLoaded=false;
                document.addEventListener('load',e=>{if(e.target.tagName==='IMG')imageLoads++},true);
                document.addEventListener('DOMContentLoaded',()=>domReady=true);
                window.addEventListener('load',()=>pageLoaded=true);</script>
                <section class="popular-recommend">
                <div class="recommend-card-media"><img src="/480.svg" srcset="/480.svg 480w, /960.svg 960w" sizes="540px" data-source-width="1200" data-source-height="800"></div>
                <div class="recommend-card-media"><img src="/missing-thumbnail" data-original-src="/original.svg"></div>
                <div class="recommend-card-media"><img src="/missing-thumbnail" data-original-src="/missing-original"></div>
                </section><script src="/home-popular-images.js" defer></script>`);
        });
        await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
        t.after(() => server.close());
        const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-home-loading-'));
        const browser = spawn(chrome, ['--headless=new', '--disable-gpu', '--disable-background-networking',
            '--no-first-run', '--no-default-browser-check', '--remote-debugging-pipe', `--user-data-dir=${profile}`],
            {stdio: ['ignore', 'ignore', 'ignore', 'pipe', 'pipe'], windowsHide: true});
        let sequence = 0, buffer = '', session;
        const pending = new Map();
        browser.stdio[4].on('data', chunk => {
            buffer += chunk;
            let at;
            while ((at = buffer.indexOf('\0')) >= 0) {
                const raw = buffer.slice(0, at); buffer = buffer.slice(at + 1); if (!raw) continue;
                const message = JSON.parse(raw), task = pending.get(message.id);
                if (task) { pending.delete(message.id); message.error ? task.reject(new Error(JSON.stringify(message.error))) : task.resolve(message.result); }
            }
        });
        const send = (method, params = {}, target = session) => new Promise((resolve, reject) => {
            const id = ++sequence; pending.set(id, {resolve, reject});
            browser.stdio[3].write(JSON.stringify({id, method, params, ...(target ? {sessionId: target} : {})}) + '\0');
        });
        t.after(async () => {
            browser.kill();
            assert.ok(path.resolve(profile).startsWith(path.resolve(os.tmpdir()) + path.sep + 'tripbora-home-loading-'));
            for (let i = 0; i < 10; i++) {
                try { fs.rmSync(profile, {recursive: true, force: true}); break; }
                catch { await new Promise(resolve => setTimeout(resolve, 100)); }
            }
        });
        const target = await send('Target.createTarget', {url: 'about:blank'}, null);
        session = (await send('Target.attachToTarget', {targetId: target.targetId, flatten: true}, null)).sessionId;
        const inspect = async () => {
            const result = await send('Runtime.evaluate', {awaitPromise: true, returnByValue: true,
                expression: `new Promise(resolve=>setTimeout(()=>resolve({ready:document.readyState,domReady,pageLoaded,imageLoads,
                    overflow:document.documentElement.scrollWidth>innerWidth,
                    images:[...document.querySelectorAll('img')].map(i=>({complete:i.complete,width:i.naturalWidth,src:i.getAttribute('src'),hidden:getComputedStyle(i).visibility==='hidden'}))}),300))`});
            assert.equal(result.exceptionDetails, undefined);
            return result.result.value;
        };
        for (const width of [360, 390, 393, 430, 768, 1440]) {
            await send('Emulation.setDeviceMetricsOverride', {width, height: 950, deviceScaleFactor: 1, mobile: false});
            await send('Page.navigate', {url: `http://127.0.0.1:${server.address().port}/`});
            const first = await inspect(), settled = await inspect();
            t.diagnostic(`${width}px: ${JSON.stringify(settled)}`);
            assert.equal(settled.ready, 'complete');
            assert.equal(settled.domReady, true);
            assert.equal(settled.pageLoaded, true);
            assert.equal(settled.imageLoads, first.imageLoads, 'load events must stop after sizes selection settles');
            assert.ok(settled.imageLoads <= 3, 'no cached image load loop');
            assert.equal(settled.overflow, false);
            assert.ok(settled.images.every(image => image.complete));
            assert.ok(settled.images[0].width > 0);
            assert.ok(settled.images[1].width > 0);
            assert.equal(settled.images[1].src, '/original.svg');
            assert.equal(settled.images[2].width, 0);
            assert.equal(settled.images[2].hidden, true);
        }
        assert.equal(requests.filter(url => url === '/missing-original').length, 6, 'one original attempt per navigation');
    });
