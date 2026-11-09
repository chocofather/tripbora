// Real MVC-rendered pages and production CSS/JS; isolated HTTP fixtures, no application server or DB.
const fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const assert = require('node:assert/strict');
const {execFile} = require('node:child_process');
const run = require('node:util').promisify(execFile);
const root = path.resolve('build/reports/legacy-gallery-withdrawal');
const source = path.resolve('src/main/resources/static');
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const widths = [360, 390, 393, 430, 768, 1440];
const modes = ['success', 'cancel', 'conflict', 'auth', 'withdrawn', 'no-agreement'];
const cases = widths.flatMap(width => modes.map(mode => ({width, mode})));
const withdrawn = new Set(), decisions = new Map(), resources = new Map(), failures = [];

function page(width, mode) {
    const key = `${width}-${mode}`, completed = withdrawn.has(key);
    const name = mode === 'no-agreement' ? 'no-agreement' : mode === 'withdrawn' || completed ? 'withdrawn' : 'agreement';
    let html = fs.readFileSync(path.join(root, name + '.html'), 'utf8');
    const managementScript = html.match(/src="([^"]*\/js\/review-image-management-[a-f0-9]{32}\.js)"/);
    assert.ok(managementScript, 'MVC output uses the production content hash for review image management');
    assert.match(html, /href="[^"]*\/css\/review-image-management-[a-f0-9]{32}\.css"/);
    // Other page modules are outside this narrow fixture; keep rendered markup and styles.
    html = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
    html = html.replace(/<link\b[^>]*href="https?:[^>]*>/gi, '');
    const harness = `<script>
    const errors=[]; addEventListener('error',event=>{if(event.message)errors.push(event.message)});
    const mode=${JSON.stringify(mode)},completed=${completed},width=${width};
    const check=(condition,message)=>{if(!condition)throw Error(message)};
    const tick=()=>new Promise(resolve=>setTimeout(resolve,50));
    const scope=document.querySelector('[data-legacy-gallery-withdrawal]');
    const button=scope?.querySelector('[data-legacy-gallery-withdraw]');
    let confirms=0; window.confirm=()=>{confirms++;return mode!=='cancel'};
    window.alert=message=>{throw Error('Unexpected alert: '+message)};
    async function report(){
      const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);
      check(overflow<=1,'horizontal overflow '+overflow);
      const response=await fetch('/fixture-state?width='+width+'&mode='+mode),state=await response.json();
      check(state.retiredGets===0,'retired GET must never be requested');
      check(state.calls===(mode==='success'||mode==='conflict'||mode==='auth'?1:0),'unexpected decision requests '+state.calls);
      check(errors.length===0,'console errors '+errors.join(';'));
      parent.postMessage({width,mode,overflow,errors,decisions:state.calls},'*');
    }
    addEventListener('load',async()=>{try{
      await tick();
      if(mode==='no-agreement'){check(!scope,'no legacy agreement has no settings');await report();return;}
      check(scope,'legacy section exists');
      check(document.querySelectorAll('[data-review-photo-delete]').length>0,'original photo delete action remains');
      check(scope.querySelector('[data-legacy-gallery-status]').getAttribute('role')==='status','accessible live status');
      check(scope.textContent.includes('원본 리뷰와 사진은 그대로 유지'),'original review photos retained');
      if(mode==='withdrawn'||completed){
        check(!button,'withdrawn member has no button');
        check(scope.querySelector('[data-legacy-gallery-status]').textContent.includes('철회가 완료'),'completed status');
        await report();return;
      }
      check(button,'withdrawal button');
      check(scope.dataset.policyId==='17'&&scope.dataset.policyVersion==='legacy-v1','original agreed version');
      const rect=button.getBoundingClientRect();check(rect.width>0&&rect.right<=innerWidth+1,'visible button within viewport');
      button.focus();check(document.activeElement===button,'keyboard focusable');
      button.dispatchEvent(new MouseEvent('click',{bubbles:true}));
      if(mode==='cancel'){check(!button.disabled,'cancel leaves enabled');await report();return;}
      check(button.disabled,'pending request disables button');
      button.dispatchEvent(new MouseEvent('click',{bubbles:true}));check(confirms===1,'rapid click does not reconfirm');
      if(mode==='success')return; // The real production reload renders the withdrawn fixture, then reports.
      for(let i=0;i<80&&button.disabled;i++)await tick();
      check(!button.disabled&&!button.hidden,'failed request remains retryable');
      const error=scope.querySelector('[data-legacy-gallery-error]');
      check(!error.hidden&&error.textContent.length>0,'visible failure feedback');
      check(!scope.querySelector('[data-legacy-gallery-status]').textContent.includes('완료되었습니다'),'no false success');
      await report();
    }catch(error){parent.postMessage({width,mode,error:error.stack,errors},'*')}});
    </script><script type="module" src="${managementScript[1]}"></script>`;
    return html.replace('</body>', harness + '</body>');
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    res.setHeader('Cache-Control', 'no-store');
    if (url.pathname === '/mypage/gallery-consent') {
        const ref = new URL(req.headers.referer || '/', 'http://127.0.0.1');
        const key = `${ref.searchParams.get('width')}-${ref.searchParams.get('mode')}`;
        const state = decisions.get(key) || {calls: 0, retiredGets: 0}; decisions.set(key, state);
        if (req.method === 'GET') { state.retiredGets++; res.writeHead(410).end(); return; }
        state.calls++;
        let raw = ''; for await (const chunk of req) raw += chunk;
        try {
            assert.equal(req.method, 'PUT');
            assert.equal(req.headers.accept, 'application/json');
            assert.match(req.headers['content-type'], /application\/json/);
            assert.ok(Object.keys(req.headers).some(header => /csrf/i.test(header) && req.headers[header]));
            assert.deepEqual(JSON.parse(raw), {action: 'WITHDRAW', policyVersionId: 17, version: 'legacy-v1'});
        } catch (error) { failures.push(error.message); }
        const mode = ref.searchParams.get('mode');
        setTimeout(() => {
            res.setHeader('Content-Type', 'application/json');
            if (mode === 'conflict') { res.writeHead(409).end(JSON.stringify({detail: 'changed'})); return; }
            if (mode === 'auth') { res.writeHead(403).end(JSON.stringify({detail: 'forbidden'})); return; }
            withdrawn.add(key);
            res.end(JSON.stringify({agreed: false, decisionAction: 'WITHDRAW', status: 'WITHDRAWN'}));
        }, 80);
        return;
    }
    if (url.pathname === '/fixture-state') {
        const state = decisions.get(`${url.searchParams.get('width')}-${url.searchParams.get('mode')}`) || {calls: 0, retiredGets: 0};
        res.setHeader('Content-Type', 'application/json'); res.end(JSON.stringify(state)); return;
    }
    if (url.pathname === '/case') {
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end(page(Number(url.searchParams.get('width')), url.searchParams.get('mode'))); return;
    }
    if (url.pathname === '/preview') {
        const width = Number(url.searchParams.get('width'));
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end(`<style>body{margin:0}iframe{border:0;width:${width}px;height:1700px}</style><iframe src="/case?width=${width}&mode=cancel"></iframe>`);
        return;
    }
    if (url.pathname === '/') {
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end('<!doctype html><meta charset="utf-8"><pre id="results"></pre><script>const rows=[];addEventListener("message",event=>{rows.push(event.data);if(rows.length===' + cases.length + ')document.getElementById("results").textContent=JSON.stringify(rows)});</script>' +
            cases.map(({width, mode}) => `<iframe style="border:0;width:${width}px;height:1800px" src="/case?width=${width}&mode=${mode}"></iframe>`).join(''));
        return;
    }
    const relative = decodeURIComponent(url.pathname).replace(/^\//, '').replace(/-[a-f0-9]{32}(?=\.[^.]+$)/, '');
    const file = path.resolve(source, relative);
    if (!file.startsWith(source + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
        if (url.pathname === '/fixture/review.png') {
            res.setHeader('Content-Type', 'image/svg+xml');
            res.end('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="#e8e3ef"/></svg>'); return;
        }
        failures.push('404 ' + url.pathname); res.writeHead(404).end(); return;
    }
    const type = ({'.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.png': 'image/png',
        '.jpg': 'image/jpeg', '.woff2': 'font/woff2', '.ico': 'image/x-icon'})[path.extname(file)] || 'application/octet-stream';
    resources.set(url.pathname, type); res.setHeader('Content-Type', type); res.end(fs.readFileSync(file));
});

(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = 'http://127.0.0.1:' + server.address().port;
    const flags = ['--headless=new', '--disable-gpu', '--disable-background-networking', '--no-first-run',
        '--no-default-browser-check', '--user-data-dir=' + path.join(root, 'chrome-profile'), '--virtual-time-budget=20000'];
    try {
        const {stdout} = await run(chrome, [...flags, '--dump-dom', origin], {encoding: 'utf8', timeout: 60000, maxBuffer: 32 * 1024 * 1024});
        const result = stdout.match(/<pre id="results">([\s\S]*?)<\/pre>/);
        assert.ok(result && result[1], 'All browser cases completed');
        const rows = JSON.parse(result[1].replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
        fs.writeFileSync(path.join(root, 'browser-results.json'), JSON.stringify({rows, resources: [...resources], failures}, null, 2));
        assert.equal(rows.length, cases.length);
        for (const row of rows) { assert.ok(!row.error, JSON.stringify(row)); assert.deepEqual(row.errors, []); }
        assert.deepEqual(failures, []);
        for (const width of [390, 1440]) await run(chrome, [...flags, '--window-size=' + Math.max(550, width + 10) + ',1700',
            '--screenshot=' + path.join(root, 'withdrawal-' + width + '.png'), origin + '/preview?width=' + width],
        {timeout: 30000, maxBuffer: 1024 * 1024});
        console.log(JSON.stringify({cases: rows.length, widths, maxOverflow: Math.max(...rows.map(row => row.overflow)), resources: resources.size}));
    } finally { await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
