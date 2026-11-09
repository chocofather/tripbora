// Actual MVC markup + production content-hashed resources, isolated HTTP API fixture; no DB/app server.
const fs = require('node:fs'), path = require('node:path'), http = require('node:http');
const assert = require('node:assert/strict');
const run = require('node:util').promisify(require('node:child_process').execFile);
const root = path.resolve('build/reports/destination-gallery-paging');
const source = path.resolve('src/main/resources/static');
const chrome = process.env.CHROME_PATH || 'C:/Program Files/Google/Chrome/Application/chrome.exe';
const widths = [360, 390, 393, 430, 768, 1440];
const cases = widths.flatMap(width => ['normal', 'error', 'broken', 'empty', 'legacy'].map(mode => ({width, mode})));
const resources = new Map(), requests = new Map(), failures = [];
const paths = JSON.parse(fs.readFileSync(path.join(root, 'resources.json'), 'utf8'));
for (const value of Object.values(paths)) assert.match(value, /-[a-f0-9]{32}\.(css|js)$/);

function page(width, mode) {
    let markup = fs.readFileSync(path.join(root, 'detail.html'), 'utf8');
    if (mode === 'legacy') markup = markup.replace(/data-gallery-endpoint="[^"]*"/g, '');
    if (mode === 'broken') markup = markup.replaceAll('/fixture/admin.svg', '/fixture/broken.svg');
    return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
    ${['/css/detail.css', '/css/destination-detail-mobile.css'].map(key => `<link rel="stylesheet" href="${paths[key]}">`).join('')}
    <style>body{margin:0}.carousel-wrapper{width:100%}button{box-sizing:border-box}.comment-image{max-width:100%}</style>
    <script>const errors=[];addEventListener('error',event=>{if(event.message)errors.push(event.message)});</script>
    </head><body><div class="destination-detail-page">${markup}
    <div class="comment-images"><img class="comment-image" src="/fixture/review.svg" data-review-id="20" alt="review"></div></div>
    <script src="/js/jquery-3.6.0.min.js"></script><script src="${paths['/js/detail-carousel.js']}"></script>
    <script src="${paths['/js/destination-image-modal.js']}"></script>
    <script>
    const width=${width},mode=${JSON.stringify(mode)};
    const check=(condition,message)=>{if(!condition)throw Error(message)};
    const tick=()=>new Promise(resolve=>setTimeout(resolve,20));
    async function until(condition){for(let n=0;n<150;n++){if(condition())return;await tick()}throw Error('Timed out '+condition)}
    const key=event=>document.dispatchEvent(new KeyboardEvent('keydown',{key:event,bubbles:true,cancelable:true}));
    addEventListener('load',async()=>{try{
      await tick();
      const wrapper=document.getElementById('destination-gallery'), modal=document.getElementById('destination-image-modal');
      const grid=document.getElementById('destination-gallery-grid');
      if(mode==='legacy'){
        check(!TripBoraGallery.states,'legacy branch retained');
        wrapper.querySelector('.slide img').click();check(modal.open,'legacy viewer opens');
        key('ArrowRight');check(wrapper.querySelectorAll('.slide')[1].classList.contains('active'),'legacy next sync');
        key('Escape');check(!modal.open,'legacy closes');
      }else{
        await until(()=>TripBoraGallery.states);
        const {main,listing}=TripBoraGallery.states;
        if(mode==='broken'){
          await until(()=>main.items.length===0);
          check(!wrapper.hidden,'broken first window keeps actions');
          wrapper.querySelector('.next').click();await until(()=>!main.request&&main.items.length>0);
          check(main.items[0].galleryKey==='review:31','broken window advances to good next page');
        }else if(mode==='empty'){
          main.pages[0].items=[];main.onChange();
          wrapper.querySelector('.next').click();await until(()=>!main.request);
          check(main.items.length===0&&main.cursor('next')==='cursor-60','empty bounded candidate page progresses');
          await tick();check(main.pages.length===2,'no automatic empty-page loop');
          wrapper.querySelector('.next').click();await until(()=>!main.request&&main.items.length>0);
        }else if(mode==='error'){
          main.select('admin:30');await until(()=>main.error);
          const retry=wrapper.querySelector('[data-gallery-retry]');
          check(!retry.hidden,'error has retry');check(main.items.length===30,'error preserves photos');
          retry.click();retry.click();await until(()=>!main.request&&!main.error);
          check(main.items.length===60,'retry appends once');
          while(main.cursor('next'))await main.load();
          main.selectedKey=main.items.at(-1).galleryKey;main.onChange();
          check(wrapper.querySelector('.next').disabled,'known end disables next');
          check(!(await main.move(1)),'known end never wraps');
        }else{
          main.select('admin:30');await until(()=>!main.request&&main.items.length===60);
          while(main.cursor('next'))await main.load();
          check(main.pages.length===3&&main.items.length<=90,'bounded main pages');
          check(wrapper.querySelectorAll('.slide').length<=90,'bounded slider DOM');
          check(wrapper.querySelectorAll('[data-gallery-index]').length<=90,'bounded thumbnails');
          main.selectedKey=main.items[0].galleryKey;await main.move(-1);
          check(main.current.galleryKey==='review:60','previous reloads evicted window');
          wrapper.querySelector('.slide.active img').click();check(modal.open,'paged viewer opens');
          const before=main.current.galleryKey;key('ArrowRight');await until(()=>main.current.galleryKey!==before);
          check(modal.querySelector('img').src.includes('/fixture/'),'viewer current loaded photo');
          if(width<=430){
            await tick();
            const image=modal.querySelector('.image-modal-img'),old=main.current.galleryKey;
            image.setPointerCapture=()=>{};
            image.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,isPrimary:true,pointerId:42,pointerType:'touch',button:0,clientX:200,clientY:100}));
            image.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,isPrimary:true,pointerId:42,pointerType:'touch',button:0,clientX:100,clientY:102}));
            await until(()=>main.current.galleryKey!==old);
          }
          key('Escape');check(!modal.open,'paged viewer closes');
          wrapper.querySelector('[data-gallery-open]').click();await until(()=>!listing.request&&listing.items.length>0);
          check(grid.open,'all photos grid opens');
          grid.querySelector('[data-gallery-filter=ADMIN]').click();grid.querySelector('[data-gallery-filter=TRAVELER]').click();
          await until(()=>!listing.request&&listing.filter==='TRAVELER');
          check(listing.items.every(item=>item.travelerPhoto),'latest filter wins');
          while(listing.cursor('next'))await listing.load();
          check(listing.items.length<=90&&grid.querySelectorAll('[data-gallery-tile]').length<=90,'bounded grid metadata and DOM');
          const anchor=grid.querySelector('[data-gallery-tile]'),anchorKey=anchor.dataset.galleryKey,anchorTop=anchor.getBoundingClientRect().top;
          grid.querySelector('[data-gallery-grid-previous]').click();await until(()=>!listing.request);
          const retained=Array.from(grid.querySelectorAll('[data-gallery-tile]')).find(tile=>tile.dataset.galleryKey===anchorKey);
          check(retained&&Math.abs(retained.getBoundingClientRect().top-anchorTop)<=2,'previous load preserves visible grid anchor');
          if(new URL(location.href).searchParams.has('showGrid'))return;
          const tile=Array.from(grid.querySelectorAll('[data-gallery-tile]')).at(-1);tile.click();check(modal.open&&grid.open,'existing viewer stacks over grid');
          const gridKey=listing.current.galleryKey;key('ArrowRight');await until(()=>listing.current.galleryKey!==gridKey);await tick();
          check(listing.items.length<=90&&listing.pages.length<=3,'grid viewer loads forward within bounds');
          check(modal.querySelector('figcaption').textContent.includes('리뷰'),'traveler review caption');
          key('Escape');check(!modal.open&&grid.open,'ESC closes only viewer');
          await tick();
          check(grid.contains(document.activeElement),'viewer focus returns to grid');
          grid.querySelector('[data-gallery-close]').click();await until(()=>document.activeElement===wrapper.querySelector('[data-gallery-open]'));
          check(!grid.open&&document.activeElement===wrapper.querySelector('[data-gallery-open]'),'grid restores opening focus '+document.activeElement?.outerHTML?.slice(0,240));
          document.querySelector('.comment-image').click();check(modal.open,'original review image still opens');key('Escape');
        }
      }
      check(errors.length===0,'console errors '+errors.join(';'));
      const overflow=Math.max(0,document.documentElement.scrollWidth-innerWidth);check(overflow<=1,'horizontal overflow '+overflow);
      parent.postMessage({width,mode,overflow,errors},'*');
    }catch(error){parent.postMessage({width,mode,error:error.stack,errors},'*')}});
    </script></body></html>`;
}

const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    res.setHeader('Cache-Control', 'no-store');
    if (url.pathname === '/destinations/15/gallery') {
        const ref = new URL(req.headers.referer), mode = ref.searchParams.get('mode'), width = ref.searchParams.get('width');
        const filter = url.searchParams.get('filter'), cursor = url.searchParams.get('cursor');
        const id = `${width}-${mode}-${filter}-${cursor}`, count = (requests.get(id) || 0) + 1; requests.set(id, count);
        assert.equal(req.headers.accept, 'application/json');
        res.setHeader('Content-Type', 'application/json');
        if (mode === 'error' && cursor === 'cursor-30' && count === 1) { res.writeHead(503).end('{}'); return; }
        const backwards = cursor?.startsWith('previous-');
        const position = cursor ? Number(cursor.split('-').at(-1)) : 0;
        const start = Math.max(0, position - (backwards ? 30 : 0)), end = Math.min(150, start + 30);
        let items = Array.from({length: end - start}, (_, index) => {
            const id = start + index + 1, traveler = filter === 'TRAVELER' || (filter !== 'ADMIN' && id > 30);
            return {galleryKey: (traveler ? 'review:' : 'admin:') + id, travelerPhoto: traveler, imageUrl: '/fixture/review.svg',
                reviewId: traveler ? 20 : null, reviewUrl: traveler ? '/destinations/15?commentId=20' : null,
                sourceName: traveler ? null : 'Fixture', licenseDisplay: traveler ? null : 'CC BY 4.0'};
        });
        if (mode === 'empty' && cursor === 'cursor-30') items = [];
        const body = {items, nextCursor: end < 150 ? 'cursor-' + end : null, previousCursor: start ? 'previous-' + start : null};
        setTimeout(() => res.end(JSON.stringify(body)), filter === 'ADMIN' ? 90 : 10); return;
    }
    if (url.pathname === '/case') { res.setHeader('Content-Type', 'text/html; charset=utf-8'); res.end(page(Number(url.searchParams.get('width')), url.searchParams.get('mode'))); return; }
    if (url.pathname === '/') {
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        // Native dialog focus is a page-level interaction: run one active page at a time.
        res.end('<!doctype html><pre id="results"></pre><iframe id="case" height="1200"></iframe><script>const rows=[],cases=' + JSON.stringify(cases) +
            ';let position=0;const frame=document.getElementById("case");function next(){if(position===cases.length){document.getElementById("results").textContent=JSON.stringify(rows);return;}const row=cases[position++];frame.width=row.width;frame.src="/case?width="+row.width+"&mode="+row.mode;}addEventListener("message",event=>{rows.push(event.data);next()});next();</script>'); return;
    }
    if (url.pathname === '/preview') {
        const width = Number(url.searchParams.get('width'));
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end(`<style>body{margin:0}iframe{border:0;width:${width}px;height:1500px}</style><iframe src="/case?width=${width}&mode=normal${url.searchParams.has('grid') ? '&showGrid=1' : ''}"></iframe>`); return;
    }
    if (url.pathname.startsWith('/fixture/')) {
        if (url.pathname.includes('broken')) { res.writeHead(404).end(); return; }
        res.setHeader('Content-Type', 'image/svg+xml');
        res.end('<svg xmlns="http://www.w3.org/2000/svg" width="800" height="600"><rect width="800" height="600" fill="#e8e3ef"/><path d="M0 600L300 100L800 600" fill="#b3a5c9"/></svg>'); return;
    }
    const relative = decodeURIComponent(url.pathname).replace(/^\//, '').replace(/-[a-f0-9]{32}(?=\.[^.]+$)/, '');
    const file = path.resolve(source, relative);
    if (!file.startsWith(source + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) { failures.push('404 ' + url.pathname); res.writeHead(404).end(); return; }
    const type = ({'.css': 'text/css', '.js': 'text/javascript', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'})[path.extname(file)] || 'application/octet-stream';
    resources.set(url.pathname, type); res.setHeader('Content-Type', type); res.end(fs.readFileSync(file));
});

(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const origin = 'http://127.0.0.1:' + server.address().port;
    const flags = ['--headless=new', '--disable-gpu', '--disable-background-networking', '--no-first-run', '--no-default-browser-check',
        '--user-data-dir=' + path.join(root, 'chrome-profile'), '--virtual-time-budget=45000'];
    try {
        const {stdout} = await run(chrome, [...flags, '--dump-dom', origin], {encoding: 'utf8', timeout: 60000, maxBuffer: 32 * 1024 * 1024});
        fs.writeFileSync(path.join(root, 'browser-dom.html'), stdout);
        const result = stdout.match(/<pre id="results">([\s\S]*?)<\/pre>/); assert.ok(result && result[1], 'All browser cases completed');
        const rows = JSON.parse(result[1].replaceAll('&amp;', '&').replaceAll('&lt;', '<').replaceAll('&gt;', '>'));
        fs.writeFileSync(path.join(root, 'browser-results.json'), JSON.stringify({rows, resources: [...resources], requests: [...requests], failures}, null, 2));
        for (const row of rows) assert.ok(!row.error, JSON.stringify(row));
        assert.equal(rows.length, cases.length); assert.deepEqual(failures, []);
        for (const width of [390, 1440]) for (const kind of ['gallery', 'grid']) await run(chrome, [...flags, '--window-size=' + Math.max(550, width + 10) + ',1600',
            '--screenshot=' + path.join(root, kind + '-' + width + '.png'), origin + '/preview?width=' + width + (kind === 'grid' ? '&grid=1' : '')], {timeout: 60000, maxBuffer: 1024 * 1024});
        console.log(JSON.stringify({cases: rows.length, widths, maxOverflow: Math.max(...rows.map(row => row.overflow)), resources: resources.size}));
    } finally { await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });
