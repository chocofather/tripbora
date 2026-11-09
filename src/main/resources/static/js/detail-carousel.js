/** Live cursor windows retain at most three server pages. No visited-page history is retained. */
(function () {
    class GalleryWindow {
        constructor(endpoint, initial, onChange = () => {}) {
            this.endpoint = endpoint;
            this.onChange = onChange;
            this.filter = 'ALL';
            this.generation = 0;
            this.pages = initial ? [this.page(initial)] : [];
            this.selectedKey = this.items[0]?.galleryKey;
            this.error = false;
            this.lastDirection = 'next';
        }
        page(value) {
            return {items: (Array.isArray(value.items) ? value.items : []).slice(0, 30)
                .filter(item => item && typeof item.galleryKey === 'string' && typeof item.imageUrl === 'string'),
                nextCursor: value.nextCursor || null, previousCursor: value.previousCursor || null};
        }
        get items() {
            const keys = new Set();
            return this.pages.flatMap(page => page.items).filter(item => {
                if (keys.has(item.galleryKey)) return false;
                keys.add(item.galleryKey); return true;
            });
        }
        get index() { return Math.max(0, this.items.findIndex(item => item.galleryKey === this.selectedKey)); }
        get current() { return this.items[this.index]; }
        cursor(direction) {
            return direction === 'previous' ? this.pages[0]?.previousCursor : this.pages.at(-1)?.nextCursor;
        }
        canMove(step) {
            return step < 0 ? this.index > 0 || !!this.cursor('previous')
                : this.index < this.items.length - 1 || !!this.cursor('next') || !this.pages.length;
        }
        cancel() {
            this.generation++;
            this.request?.controller.abort();
            this.request = null;
        }
        reset(filter) {
            this.cancel(); this.filter = filter; this.pages = []; this.selectedKey = null; this.error = false;
            this.onChange(); return this.load('next');
        }
        async load(direction = 'next') {
            if (this.request) return this.request.promise;
            const cursor = this.cursor(direction);
            if (this.pages.length && !cursor) return false;
            const generation = this.generation, controller = new AbortController();
            const request = {controller};
            this.request = request; this.error = false; this.lastDirection = direction;
            // Start in a microtask so duplicate callers always observe the same request promise.
            request.promise = Promise.resolve().then(async () => {
                try {
                    const url = new URL(this.endpoint, window.location.href);
                    url.searchParams.set('filter', this.filter);
                    if (cursor) url.searchParams.set('cursor', cursor);
                    const response = await fetch(url, {signal: controller.signal, cache: 'no-store',
                        headers: {'Accept': 'application/json'}, credentials: 'same-origin'});
                    if (!response.ok || response.redirected || !response.headers.get('content-type')?.includes('application/json')) throw Error('gallery');
                    const body = await response.json();
                    if (generation !== this.generation || controller.signal.aborted) return false;
                    if (!body || !Array.isArray(body.items)) throw Error('gallery');
                    const page = this.page(body);
                    // A broken server cursor must never cause automatic request loops.
                    if (cursor && page[direction + 'Cursor'] === cursor) page[direction + 'Cursor'] = null;
                    if (direction === 'previous') {
                        this.pages.unshift(page);
                        if (this.pages.length > 3) this.pages.pop();
                    } else {
                        this.pages.push(page);
                        if (this.pages.length > 3) this.pages.shift();
                    }
                    if (!this.items.some(item => item.galleryKey === this.selectedKey)) this.selectedKey = this.items[0]?.galleryKey;
                    return true;
                } catch (error) {
                    if (generation === this.generation && !controller.signal.aborted) this.error = true;
                    return false;
                } finally {
                    if (this.request === request) { this.request = null; this.onChange(); }
                }
            });
            this.onChange();
            return request.promise;
        }
        select(key) {
            if (!this.items.some(item => item.galleryKey === key)) return;
            this.selectedKey = key; this.onChange();
            if (this.index >= this.items.length - 4 && this.cursor('next') && !this.error) void this.load('next');
        }
        discard(key) {
            const index = this.index;
            this.pages.forEach(page => { page.items = page.items.filter(item => item.galleryKey !== key); });
            if (this.selectedKey === key) this.selectedKey = (this.items[index] || this.items[index - 1])?.galleryKey;
            this.onChange();
        }
        async move(step) {
            if (this.moving) return false;
            this.moving = true;
            const generation = this.generation;
            try {
                const key = this.current?.galleryKey;
                let items = this.items, index = items.findIndex(item => item.galleryKey === key);
                if (index + step < 0 || index + step >= items.length) {
                    await this.load(step < 0 ? 'previous' : 'next');
                    if (generation !== this.generation) return false;
                    items = this.items; index = items.findIndex(item => item.galleryKey === key);
                }
                const next = items[index + step];
                if (!next) return false;
                this.select(next.galleryKey); return true;
            } finally { this.moving = false; }
        }
    }

    function mount() {
        const wrapper = document.getElementById('destination-gallery');
        if (!wrapper?.dataset.galleryEndpoint) return false;
        wrapper.hidden = false;
        const carousel = wrapper.querySelector('.carousel'), track = carousel.querySelector('.track');
        const strip = wrapper.querySelector('.destination-gallery-thumbnails');
        const grid = document.getElementById('destination-gallery-grid');
        const labels = document.getElementById('destination-detail-i18n')?.dataset || {};
        const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        const node = (tag, className) => { const value = document.createElement(tag); value.className = className; return value; };
        function fromSlide(slide) {
            return {...slide.dataset, galleryKey: slide.dataset.galleryKey, imageUrl: slide.querySelector('img').src,
                licenseDisplay: slide.dataset.licenseLabel, safeLicenseUrl: slide.dataset.licenseUrl,
                customAttribution: slide.dataset.credit, attributionUrl: slide.dataset.sourceUrl,
                travelerPhoto: !!slide.dataset.reviewId, noDerivatives: slide.classList.contains('is-no-derivatives')};
        }
        function imageNode(item, slide = false) {
            const parent = node(slide ? 'div' : 'button', slide ? 'slide' : '');
            if (!slide) parent.type = 'button';
            parent.classList.toggle('is-no-derivatives', !!item.noDerivatives);
            Object.assign(parent.dataset, {galleryKey: item.galleryKey, reviewId: item.reviewId || '', reviewUrl: item.reviewUrl || '',
                sourceName: item.travelerPhoto ? labels.galleryTraveler : item.sourceName || '', licenseLabel: item.licenseDisplay || '',
                licenseUrl: item.safeLicenseUrl || '', photographer: item.photographer || '', credit: item.customAttribution || '',
                sourceUrl: item.attributionUrl || ''});
            const image = node('img', ''); image.src = item.imageUrl; image.alt = labels.galleryFallbackAlt || ''; image.loading = 'lazy';
            parent.append(image); return parent;
        }
        function feedback(state, status, retry) {
            if (status) status.textContent = state.request ? labels.galleryLoading : state.error ? labels.galleryLoadFailed
                : !state.items.length ? (state.cursor('next') ? labels.galleryContinue : labels.galleryEmpty)
                    : !state.cursor('next') ? labels.galleryEnd : '';
            if (retry) retry.hidden = !state.error;
        }
        const initial = {items: Array.from(track.querySelectorAll('.slide')).map(fromSlide),
            nextCursor: wrapper.dataset.galleryNextCursor, previousCursor: wrapper.dataset.galleryPreviousCursor};
        let renderedMain = [], renderedGrid = [], gridFocus = null, gridOverflow = '', gridIsOpen = false;
        let gridDirection = 'next', gridAnchor = null;
        const main = new GalleryWindow(wrapper.dataset.galleryEndpoint, initial, renderMain);
        const listing = new GalleryWindow(wrapper.dataset.galleryEndpoint, null, renderGrid);
        function sameItems(left, right) { return left.length === right.length && left.every((item, index) => item === right[index]); }
        function renderMain() {
            const items = main.items;
            if (!sameItems(renderedMain, items)) {
                renderedMain = items;
                track.replaceChildren(...items.map(item => imageNode(item, true)));
                strip?.replaceChildren(...items.map((item, index) => {
                    const button = imageNode(item); button.dataset.galleryIndex = String(index);
                    button.setAttribute('aria-label', `${labels.galleryFallbackAlt || ''} ${index + 1}`); return button;
                }));
            }
            const index = main.index;
            track.style.transform = `translateX(-${index * 100}%)`;
            track.querySelectorAll('.slide').forEach((slide, position) => {
                slide.classList.toggle('active', index === position); slide.setAttribute('aria-hidden', String(index !== position));
            });
            strip?.querySelectorAll('button').forEach((button, position) => {
                button.classList.toggle('active', index === position); button.setAttribute('aria-current', String(index === position));
            });
            const selected = strip?.children[index];
            if (selected && strip) {
                const rect = selected.getBoundingClientRect(), bounds = strip.getBoundingClientRect();
                const offset = rect.left < bounds.left ? rect.left - bounds.left : rect.right > bounds.right ? rect.right - bounds.right : 0;
                if (offset) strip.scrollBy({left: offset, behavior: reducedMotion.matches ? 'auto' : 'smooth'});
            }
            carousel.querySelector('.prev').disabled = !main.canMove(-1);
            carousel.querySelector('.next').disabled = !main.canMove(1);
            carousel.setAttribute('aria-busy', String(!!main.request));
            wrapper.classList.toggle('has-gallery-pages', !!main.cursor('next') || !!main.cursor('previous'));
            feedback(main, wrapper.querySelector('[data-gallery-status]'), wrapper.querySelector('[data-gallery-retry]'));
            let caption = wrapper.querySelector('[data-destination-image-attribution]');
            if (!caption) { caption = node('p', 'destination-image-attribution'); caption.dataset.destinationImageAttribution = ''; wrapper.append(caption); }
            const activeImage = track.children[index]?.querySelector('img');
            if (activeImage) window.TripBoraImageViewer?.caption(activeImage, caption);
            else caption.hidden = true;
        }
        function rememberAnchor(direction) {
            gridDirection = direction;
            const bounds = grid.querySelector('.destination-gallery-grid-scroll').getBoundingClientRect();
            const tile = Array.from(grid.querySelectorAll('[data-gallery-tile]')).find(tile => tile.getBoundingClientRect().bottom > bounds.top);
            gridAnchor = tile ? {key: tile.dataset.galleryKey, top: tile.getBoundingClientRect().top} : null;
        }
        function renderGrid() {
            if (!grid) return;
            const container = grid.querySelector('.destination-gallery-grid-items'), items = listing.items;
            if (!sameItems(renderedGrid, items)) {
                renderedGrid = items;
                container.replaceChildren(...items.map((item, index) => {
                    const tile = imageNode(item); tile.dataset.galleryTile = '';
                    tile.setAttribute('aria-label', `${labels.galleryFallbackAlt || ''} ${index + 1}`); return tile;
                }));
                const scroller = grid.querySelector('.destination-gallery-grid-scroll');
                const retained = gridAnchor && Array.from(container.children).find(tile => tile.dataset.galleryKey === gridAnchor.key);
                if (retained) scroller.scrollTop += retained.getBoundingClientRect().top - gridAnchor.top;
                else if (gridAnchor) scroller.scrollTop = gridDirection === 'previous' ? scroller.scrollHeight : 0;
                gridAnchor = null;
            }
            container.setAttribute('aria-busy', String(!!listing.request));
            const previous = grid.querySelector('[data-gallery-grid-previous]'), next = grid.querySelector('[data-gallery-grid-next]');
            previous.hidden = !listing.cursor('previous'); next.hidden = !!listing.pages.length && !listing.cursor('next');
            previous.disabled = next.disabled = !!listing.request;
            feedback(listing, grid.querySelector('[data-gallery-grid-status]'), grid.querySelector('[data-gallery-grid-retry]'));
        }
        function adapter(state, image) {
            const key = image.closest('[data-gallery-key]')?.dataset.galleryKey;
            if (!key) return null;
            state.selectedKey = key;
            return {current() {
                const item = state.current;
                return item ? imageNode(item, true).querySelector('img') : null;
            }, move: step => {
                if (state === listing) rememberAnchor(step < 0 ? 'previous' : 'next');
                return state.move(step);
            }, canMove: step => state.canMove(step), error: () => state.error, cancel: () => state.cancel(),
            discard: () => state.discard(state.current?.galleryKey),
            focus() {
                const scope = state === main ? track : grid;
                return Array.from(scope.querySelectorAll('[data-gallery-key]')).find(node => node.dataset.galleryKey === state.current?.galleryKey)?.querySelector('img')
                    || (state === main ? carousel : grid.querySelector('[data-gallery-close]'));
            }};
        }
        window.TripBoraGallery.viewerFor = image => image.closest('#destination-gallery-grid') ? adapter(listing, image)
            : image.closest('#destination-gallery') ? adapter(main, image) : null;
        window.TripBoraGallery.discard = image => {
            const scope = image.closest('#destination-gallery-grid') ? listing : image.closest('#destination-gallery') ? main : null;
            const key = image.closest('[data-gallery-key]')?.dataset.galleryKey;
            if (!scope || !key) return false;
            scope.discard(key); return true;
        };
        window.TripBoraGallery.states = {main, listing};
        carousel.querySelector('.prev').addEventListener('click', () => void main.move(-1));
        carousel.querySelector('.next').addEventListener('click', () => void main.move(1));
        strip?.addEventListener('click', event => {
            const button = event.target.closest('[data-gallery-key]'); if (button) main.select(button.dataset.galleryKey);
        });
        wrapper.querySelector('[data-gallery-retry]')?.addEventListener('click', () => void main.load(main.lastDirection));
        wrapper.querySelectorAll('[data-gallery-scroll]').forEach(button => button.addEventListener('click', () => {
            strip?.scrollBy({left: Number(button.dataset.galleryScroll) * strip.clientWidth * .75, behavior: reducedMotion.matches ? 'auto' : 'smooth'});
        }));
        function updateScrollButtons() {
            wrapper.querySelectorAll('[data-gallery-scroll]').forEach(button => {
                // Compare against the entire navigation width: showing buttons shrinks the strip.
                // Comparing to the shrinking strip would oscillate at the overflow threshold.
                button.hidden = !strip || strip.scrollWidth <= strip.parentElement.clientWidth + 1;
                button.disabled = Number(button.dataset.galleryScroll) < 0 ? strip.scrollLeft <= 1 : strip.scrollLeft + strip.clientWidth >= strip.scrollWidth - 1;
            });
        }
        strip?.addEventListener('scroll', updateScrollButtons, {passive: true});
        window.addEventListener('resize', updateScrollButtons);
        if (strip && typeof ResizeObserver !== 'undefined') new ResizeObserver(() => requestAnimationFrame(updateScrollButtons)).observe(strip);
        let drag = null, dragged = false;
        strip?.addEventListener('dragstart', event => event.preventDefault());
        strip?.addEventListener('pointerdown', event => {
            if (event.pointerType === 'mouse' && event.button === 0) { drag = {x: event.clientX, left: strip.scrollLeft}; dragged = false; }
        });
        strip?.addEventListener('pointermove', event => {
            if (!drag) return;
            if (Math.abs(event.clientX - drag.x) > 5) { dragged = true; strip.setPointerCapture(event.pointerId); }
            if (dragged) strip.scrollLeft = drag.left - event.clientX + drag.x;
        });
        ['pointerup', 'pointercancel'].forEach(name => strip?.addEventListener(name, () => { drag = null; }));
        strip?.addEventListener('click', event => { if (dragged) { event.preventDefault(); event.stopImmediatePropagation(); dragged = false; } }, true);
        let swipe = null;
        carousel.addEventListener('pointerdown', event => { if (event.pointerType !== 'mouse') swipe = {x: event.clientX, y: event.clientY}; });
        carousel.addEventListener('pointerup', event => {
            if (!swipe) return;
            const dx = event.clientX - swipe.x, dy = event.clientY - swipe.y; swipe = null;
            if (Math.abs(dx) > 40 && Math.abs(dx) > Math.abs(dy) * 1.4) {
                event.preventDefault(); window.TripBoraGallery.suppressClick = true; void main.move(dx < 0 ? 1 : -1);
                setTimeout(() => { window.TripBoraGallery.suppressClick = false; }, 400);
            }
        });
        carousel.addEventListener('pointercancel', () => { swipe = null; });
        document.addEventListener('keydown', event => {
            if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey
                    || !['ArrowLeft', 'ArrowRight'].includes(event.key) || document.querySelector('dialog[open]')) return;
            const active = document.activeElement;
            if (event.target?.closest?.('input,textarea,select,[contenteditable],[role="slider"],[role="combobox"],[role="listbox"]')
                    || active?.closest?.('a') || (active !== document.body && active !== document.documentElement && !wrapper.contains(active))) return;
            const rect = carousel.getBoundingClientRect();
            if (rect.width <= 0 || rect.right <= 0 || rect.left >= innerWidth || Math.min(rect.bottom, innerHeight) - Math.max(rect.top, 0) < Math.min(rect.height, innerHeight) / 2) return;
            event.preventDefault(); void main.move(event.key === 'ArrowRight' ? 1 : -1);
        });
        function finishGridClose() {
            if (!gridIsOpen) return;
            gridIsOpen = false;
            listing.cancel(); document.body.style.overflow = gridOverflow;
            gridFocus?.focus({preventScroll: true});
        }
        function closeGrid() { if (grid?.open) { grid.close(); finishGridClose(); } }
        wrapper.querySelector('[data-gallery-open]')?.addEventListener('click', event => {
            gridFocus = event.currentTarget; gridOverflow = document.body.style.overflow;
            grid.showModal(); gridIsOpen = true; document.body.style.overflow = 'hidden';
            if (!listing.pages.length) void listing.load();
        });
        grid?.querySelector('[data-gallery-close]').addEventListener('click', closeGrid);
        grid?.addEventListener('click', event => { if (event.target === grid) closeGrid(); });
        grid?.addEventListener('cancel', event => { event.preventDefault(); closeGrid(); });
        grid?.addEventListener('close', () => { if (!grid.open) finishGridClose(); });
        grid?.querySelectorAll('[data-gallery-filter]').forEach(button => button.addEventListener('click', () => {
            if (listing.filter === button.dataset.galleryFilter && listing.pages.length) return;
            grid.querySelectorAll('[data-gallery-filter]').forEach(other => other.setAttribute('aria-pressed', String(other === button)));
            gridAnchor = null; grid.querySelector('.destination-gallery-grid-scroll').scrollTop = 0;
            void listing.reset(button.dataset.galleryFilter);
        }));
        function loadGrid(direction) { if (!listing.request) { rememberAnchor(direction); void listing.load(direction); } }
        grid?.querySelector('[data-gallery-grid-previous]').addEventListener('click', () => loadGrid('previous'));
        grid?.querySelector('[data-gallery-grid-next]').addEventListener('click', () => loadGrid('next'));
        grid?.querySelector('[data-gallery-grid-retry]').addEventListener('click', () => loadGrid(listing.lastDirection));
        grid?.querySelector('.destination-gallery-grid-scroll').addEventListener('scroll', event => {
            const area = event.currentTarget;
            if (grid.open && !listing.error && area.scrollTop > 0 && area.scrollTop + area.clientHeight >= area.scrollHeight - 180) loadGrid('next');
        }, {passive: true});
        renderMain(); renderGrid(); updateScrollButtons();
        return true;
    }
    window.TripBoraGallery = {GalleryWindow, mount};
})();

$(document).ready(function () {
        if (window.TripBoraGallery.mount()) return;
        const $carousel = $('.carousel-wrapper .carousel');
        const carousel = $carousel.get(0);
        if (!carousel || carousel.dataset.carouselInitialized === 'true') return;
        carousel.dataset.carouselInitialized = 'true';
        const $track = $carousel.find('.track');
        const $slides = $track.find('.slide');
        const $thumbnails = $('.carousel-wrapper [data-gallery-index]');
        const thumbnailStrip = document.querySelector('.destination-gallery-thumbnails');
        const scrollButtons = document.querySelectorAll('[data-gallery-scroll]');
        const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
        function updateScrollButtons() {
            if (!thumbnailStrip) return;
            const end = thumbnailStrip.scrollWidth - thumbnailStrip.clientWidth;
            scrollButtons.forEach(button => {
                button.hidden = end <= 1;
                button.disabled = Number(button.dataset.galleryScroll) < 0
                    ? thumbnailStrip.scrollLeft <= 1 : thumbnailStrip.scrollLeft >= end - 1;
            });
        }
        scrollButtons.forEach(button => button.addEventListener('click', () => {
            thumbnailStrip?.scrollBy({left: Number(button.dataset.galleryScroll) * thumbnailStrip.clientWidth * .75,
                behavior: reducedMotion.matches ? 'auto' : 'smooth'});
        }));
        thumbnailStrip?.addEventListener('scroll', updateScrollButtons, {passive: true});
        // 터치는 네이티브 스와이프, 마우스는 드래그로 같은 strip을 탐색한다.
        let drag = null;
        let dragged = false;
        thumbnailStrip?.addEventListener('dragstart', event => event.preventDefault());
        thumbnailStrip?.addEventListener('pointerdown', event => {
            if (event.pointerType !== 'mouse' || event.button !== 0) return;
            dragged = false;
            drag = {x: event.clientX, left: thumbnailStrip.scrollLeft};
        });
        thumbnailStrip?.addEventListener('pointermove', event => {
            if (!drag) return;
            if (Math.abs(event.clientX - drag.x) > 5) {
                dragged = true;
                thumbnailStrip.setPointerCapture(event.pointerId);
            }
            if (dragged) thumbnailStrip.scrollLeft = drag.left - (event.clientX - drag.x);
        });
        thumbnailStrip?.addEventListener('pointerup', () => { drag = null; });
        thumbnailStrip?.addEventListener('pointercancel', () => { drag = null; });
        thumbnailStrip?.addEventListener('pointerleave', () => { if (!dragged) drag = null; });
        thumbnailStrip?.addEventListener('click', event => {
            if (dragged) { event.preventDefault(); event.stopPropagation(); dragged = false; }
        }, true);
        window.addEventListener('resize', updateScrollButtons);
        if (thumbnailStrip && typeof ResizeObserver !== 'undefined') new ResizeObserver(updateScrollButtons).observe(thumbnailStrip);
        const $attribution = $('[data-destination-image-attribution]');
        const $source = $attribution.find('[data-destination-image-source]');
        const $separator = $attribution.find('[data-destination-image-separator]');
        const $license = $attribution.find('[data-destination-image-license]');
        const $photographerSeparator = $attribution.find('[data-destination-image-photographer-separator]');
        const $photographerPrefix = $attribution.find('[data-destination-image-photographer-prefix]');
        const $photographer = $attribution.find('[data-destination-image-photographer]');
        const $sourceLink = $attribution.find('[data-destination-image-source-link]');
        const $licenseLink = $attribution.find('[data-destination-image-license-link]');
        const $creditSeparator = $attribution.find('[data-destination-image-credit-separator]');
        const $creditPrefix = $attribution.find('[data-destination-image-credit-prefix]');
        const $credit = $attribution.find('[data-destination-image-credit]');
        const $reviewLink = $attribution.find('[data-destination-image-review-link]');
        let idx = 0;

        if ($slides.length === 0) return;

        // 초기 스타일 적용
        $track.css({
            display: 'flex',
            transition: 'transform 0.4s ease'
        });

        $slides.each(function () {
            $(this).css({
                minWidth: '100%',
                flexShrink: 0
            });
        });

        function updateAttribution() {
            if ($attribution.length === 0) return;
            const slide = $slides.get(idx);
            const sourceName = slide?.dataset.sourceName || '';
            const licenseLabel = slide?.dataset.licenseLabel || '';
            const photographer = slide?.dataset.photographer || '';
            const sourceUrl = slide?.dataset.sourceUrl || '';
            const licenseUrl = slide?.dataset.licenseUrl || '';
            const credit = slide?.dataset.credit || '';
            const reviewUrl = slide?.dataset.reviewUrl || '';
            $reviewLink.prop('hidden', !reviewUrl);
            if (reviewUrl) $reviewLink.attr('href', reviewUrl);
            else $reviewLink.removeAttr('href');

            $attribution.prop('hidden', !sourceName && !licenseLabel && !photographer && !sourceUrl);
            $source.text(sourceName).prop('hidden', !sourceName);
            // 라이선스 전문 주소가 있으면 라이선스명을 링크로 보여준다.
            $license.text(licenseLabel).prop('hidden', !licenseLabel || Boolean(licenseUrl));
            $licenseLink.text(licenseLabel).prop('hidden', !licenseLabel || !licenseUrl);
            if (licenseLabel && licenseUrl) {
                $licenseLink.attr('href', licenseUrl);
            } else {
                $licenseLink.removeAttr('href');
            }
            $creditSeparator.prop('hidden', !credit);
            $creditPrefix.prop('hidden', !credit);
            $credit.text(credit).prop('hidden', !credit);
            $separator.prop('hidden', !sourceName || !licenseLabel);
            $photographerSeparator.prop('hidden', !photographer || (!sourceName && !licenseLabel));
            $photographerPrefix.prop('hidden', !photographer);
            $photographer.text(photographer).prop('hidden', !photographer);
            $sourceLink.prop('hidden', !sourceUrl);
            if (sourceUrl) {
                $sourceLink.attr('href', sourceUrl);
            } else {
                $sourceLink.removeAttr('href');
            }
        }

        function move(n) {
            idx = (n + $slides.length) % $slides.length;
            $track.css('transform', `translateX(-${idx * 100}%)`);
            $slides.each(function (index) {
                const active = index === idx;
                $(this).toggleClass('active', active).attr('aria-hidden', String(!active));
            });
            updateAttribution();
            $thumbnails.each(function (index) {
                $(this).toggleClass('active', index === idx).attr('aria-current', String(index === idx));
            });
            const selected = $thumbnails.get(idx);
            if (selected && thumbnailStrip) {
                const item = selected.getBoundingClientRect(), strip = thumbnailStrip.getBoundingClientRect();
                const offset = item.left < strip.left ? item.left - strip.left
                    : item.right > strip.right ? item.right - strip.right : 0;
                if (offset) thumbnailStrip.scrollBy({left: offset, behavior: reducedMotion.matches ? 'auto' : 'smooth'});
            }
            updateScrollButtons();
        }

        $thumbnails.on('click', function () {
            const index = Number(this.dataset.galleryIndex);
            if (Number.isInteger(index) && index >= 0 && index < $slides.length) move(index);
        });

        $carousel.find('.prev').click(function () {
            move(idx - 1);
        });

        $carousel.find('.next').click(function () {
            move(idx + 1);
        });

        document.addEventListener('keydown', function (event) {
            if (event.defaultPrevented || event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey
                    || !['ArrowLeft', 'ArrowRight'].includes(event.key) || $slides.length <= 1) return;
            const modal = document.getElementById('destination-image-modal');
            if (modal && (modal.open || modal.classList.contains('is-open'))) return;
            const active = document.activeElement;
            if ([event.target, active].some(element => element?.closest?.('input, textarea, select, [role="slider"], [role="combobox"], [role="listbox"]')
                    || element?.isContentEditable)) return;
            const gallery = carousel.closest('.carousel-wrapper');
            if (active && active !== document.body && active !== document.documentElement && !gallery.contains(active)) return;
            if (active?.closest?.('a')) return;
            const rect = carousel.getBoundingClientRect();
            const visibleHeight = Math.min(rect.bottom, window.innerHeight) - Math.max(rect.top, 0);
            if (rect.width <= 0 || rect.height <= 0 || rect.right <= 0 || rect.left >= window.innerWidth
                    || visibleHeight < Math.min(rect.height, window.innerHeight) / 2) return;
            event.preventDefault();
            move(idx + (event.key === 'ArrowRight' ? 1 : -1));
        });

        // 확대 모달의 이전/다음 이동도 배경 캐러셀과 출처 캡션에 같은 index를 반영한다.
        document.addEventListener('destination-gallery-change', function (event) {
            const nextIndex = Number(event.detail?.index);
            if (Number.isInteger(nextIndex) && nextIndex >= 0 && nextIndex < $slides.length
                    && nextIndex !== idx) {
                move(nextIndex);
            }
        });

        move(0);
    });
