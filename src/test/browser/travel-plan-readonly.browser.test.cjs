// First render the real template: gradlew test --tests '*TravelPlanControllerTest'
// Then: node --test src/test/browser/travel-plan-readonly.browser.test.cjs
// Actual Chromium + rendered Thymeleaf + source assets; dashboard/identity are isolated fixtures, no DB.
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {spawn} = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const root = path.resolve(__dirname, '../../..');
const staticRoot = path.join(root, 'src/main/resources/static');
const fixture = path.join(root, 'build/browser-fixtures/travel-plan-final.html');
const chromePath = [process.env.TRIPBORA_CHROME_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    '/usr/bin/google-chrome', '/usr/bin/chromium'].find(file => file && fs.existsSync(file));
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));

test('completed planner stays for 32 seconds, reloads and remains readonly at mobile/desktop widths',
    {skip: !chromePath, timeout: 100000}, async t => {
    assert.ok(fs.existsSync(fixture), 'Run the Controller rendering test first');
    const requests = [], errors = [];
    const dashboard = {checklist: [{id: 1, title: '여권 준비', completed: true, version: 0}],
        notice: {content: '공항에서 오전 7시 만나요.', version: 0},
        expenses: [{id: 1, title: '공항 택시', amount: 35000, memo: '함께 이동', version: 0}],
        totalAmount: 35000, currency: 'KRW'};
    const server = http.createServer((req, res) => {
        const url = new URL(req.url, 'http://localhost');
        requests.push({method: req.method, path: url.pathname});
        if (req.method !== 'GET') {res.writeHead(405); res.end(); return;}
        if (/^\/travel-plans\/42(?:\/final)?$/.test(url.pathname)) {
            res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(fs.readFileSync(fixture)); return;
        }
        if (url.pathname === '/travel-plans/42/dashboard') {
            res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(dashboard)); return;
        }
        const file = path.resolve(staticRoot, '.' + url.pathname);
        if (file.startsWith(staticRoot + path.sep) && fs.existsSync(file) && fs.statSync(file).isFile()) {
            const mime = {'.css': 'text/css', '.js': 'application/javascript', '.svg': 'image/svg+xml', '.png': 'image/png'};
            res.setHeader('Content-Type', mime[path.extname(file)] || 'application/octet-stream');
            res.end(fs.readFileSync(file)); return;
        }
        res.setHeader('Content-Type', 'application/json'); res.end('{}');
    });
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const base = `http://127.0.0.1:${server.address().port}`;
    const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'tripbora-readonly-test-'));
    const chrome = spawn(chromePath, ['--headless=new', '--no-first-run', '--no-default-browser-check',
        '--disable-gpu', '--remote-debugging-pipe', `--user-data-dir=${profile}`],
        {stdio: ['ignore', 'ignore', 'pipe', 'pipe', 'pipe'], windowsHide: true});
    let sequence = 0, buffer = '', session;
    const pending = new Map();
    const rejectAll = error => {pending.forEach(task => task.reject(error)); pending.clear();};
    chrome.on('error', rejectAll);
    chrome.on('exit', code => rejectAll(new Error(`Chrome exited: ${code}`)));
    chrome.stdio[4].on('data', data => {
        buffer += data;
        let at;
        while ((at = buffer.indexOf('\0')) >= 0) {
            const raw = buffer.slice(0, at); buffer = buffer.slice(at + 1);
            if (!raw) continue;
            const message = JSON.parse(raw), task = pending.get(message.id);
            if (task) {pending.delete(message.id); message.error ? task.reject(new Error(JSON.stringify(message.error))) : task.resolve(message.result);}
            if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
        }
    });
    const send = (method, params = {}, targetSession = session) => new Promise((resolve, reject) => {
        const id = ++sequence; pending.set(id, {resolve, reject});
        chrome.stdio[3].write(JSON.stringify({id, method, params, ...(targetSession ? {sessionId: targetSession} : {})}) + '\0');
    });
    const evaluate = async expression => {
        const result = await send('Runtime.evaluate', {expression, returnByValue: true, awaitPromise: true});
        assert.equal(result.exceptionDetails, undefined, JSON.stringify(result.exceptionDetails));
        return result.result.value;
    };
    t.after(async () => {
        chrome.kill(); server.closeAllConnections(); server.close();
        for (let attempt = 0; attempt < 10; attempt++) {
            try {fs.rmSync(profile, {recursive: true, force: true}); break;}
            catch {await wait(100);}
        }
    });
    t.diagnostic((await send('Browser.getVersion', {}, null)).product);
    const target = await send('Target.createTarget', {url: 'about:blank'}, null);
    session = (await send('Target.attachToTarget', {targetId: target.targetId, flatten: true}, null)).sessionId;
    await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');
    // Keep navigation/request evidence throughout reloads (equivalent to Preserve log).
    await send('Network.setBlockedURLs', {urls: ['https://*']});
    await send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false});
    await send('Page.navigate', {url: base + '/travel-plans/42/final'});
    for (let count = 0; count < 100; count++) {
        if (await evaluate(`document.querySelector('[data-notice-view]')?.textContent.includes('공항에서') || false`)) break;
        await wait(100);
    }
    const state = () => evaluate(`({url:location.pathname, overflow:document.documentElement.scrollWidth>innerWidth,
        readonly:!!document.querySelector('[data-plan-readonly]'), realtime:!!window.travelPlanRealtime,
        scripts:[...document.scripts].map(s=>s.src),
        notice:document.querySelector('[data-notice-view]').textContent,
        fields:[...document.querySelectorAll('[data-item-readonly-fields] input,[data-item-readonly-fields] textarea')].every(n=>n.readOnly),
        loaded:document.querySelector('[data-dashboard-status]').textContent})`);
    assert.match((await state()).notice, /공항에서/);
    await wait(32000);
    let result = await state();
    assert.equal(result.url, '/travel-plans/42/final'); assert.equal(result.realtime, false);
    assert.equal(result.fields, true); assert.equal(result.readonly, true);
    assert.ok(!result.scripts.some(src => /travel-plan-(realtime|scheduler|finalize)\.js/.test(src)));
    t.diagnostic('32-second idle: no navigation; no ACTIVE-only requests or websocket');
    await evaluate(`document.querySelector('[data-travel-plan-item] [data-item-readonly-toggle]').click()`);
    assert.equal(await evaluate(`document.querySelector('[data-item-readonly-fields]').hidden`), false);
    assert.ok(await evaluate(`[...document.querySelectorAll('[data-item-readonly-fields] textarea')].some(n=>n.readOnly && n.value==='예약 확인 정보')`));
    await evaluate(`document.querySelector('[data-travel-plan-alt-toggle]').click()`);
    assert.equal(await evaluate(`document.querySelector('[data-travel-plan-alt-list]').hidden`), false);
    for (const width of [360, 390, 430, 768, 1440]) {
        await send('Emulation.setDeviceMetricsOverride', {width, height: 1000, deviceScaleFactor: 1, mobile: false});
        await wait(100);
        assert.equal((await state()).overflow, false, `horizontal overflow at ${width}`);
        const shot = await send('Page.captureScreenshot', {format: 'png'});
        fs.writeFileSync(path.join(root, `build/browser-fixtures/travel-plan-final-${width}.png`), Buffer.from(shot.data, 'base64'));
    }
    await send('Page.reload', {ignoreCache: true}); await wait(1000);
    assert.equal((await state()).url, '/travel-plans/42/final');
    await send('Page.navigate', {url: base + '/travel-plans/42'}); await wait(1000);
    assert.equal((await state()).readonly, true);
    assert.ok(requests.every(req => req.method === 'GET'));
    assert.ok(!requests.some(req => /members\/fragment|owner-actions\/fragment|schedule\/fragment|\/ws/.test(req.path)));
    assert.deepEqual(errors, []);
    t.diagnostic('Reload, both URL fixtures, readonly expansion, dashboard GET and five viewport widths passed');
});
