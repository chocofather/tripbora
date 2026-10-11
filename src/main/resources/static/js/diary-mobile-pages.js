/**
 * 다이어리 읽기 - 좁은 화면(860px 이하)의 한 장씩 넘기기.
 *
 * 서버는 펼침(두 장) 하나만 그려 보낸다. 넓은 화면은 그 펼침을 그대로 쓰고,
 * 좁은 화면에서는 펼침 개념을 화면에서 지운다. 지금 펼침의 장들과 옆 펼침의 장들을
 * 한 줄(.diary-page-strip)에 이어 담아 다이어리의 모든 장을 한 장씩 넘긴다.
 *
 * - 장 HTML 은 PC 넘김·PDF 와 같은 서버 조각(diary/detail :: readBoard)을 그대로 가져다 쓴다.
 *   종이·사진·스티커·좌표·구멍 방향(왼쪽 장=홀수 번째, 오른쪽 장=짝수 번째)을 여기서 다시 만들지 않는다.
 * - 옆 펼침은 지금 보는 장이 줄 끝에 가까워질 때 하나씩만 받아 온다. (처음부터 다 받지 않는다)
 *
 * 넘기기는 일반 carousel 이다. 브라우저 가로 스크롤(관성)을 쓰지 않고, 줄 하나에만 transform 을 건다.
 * - 끄는 동안 줄이 손가락을 바로 따라온다. 다만 지금 장 기준 한 장 폭 안에서만 움직여
 *   아무리 세게 밀어도 바로 옆 장까지만 보인다.
 * - 놓으면 지금 장 / 앞 장 / 뒷 장 중 하나에 짧게 미끄러져 앉는다. (넘어갔다 되돌아오는 보정이 없다)
 * - 세로 손짓은 브라우저에 맡긴다(touch-action: pan-y).
 *
 * 넓은 화면으로 바뀌면 이어 붙인 장을 걷어 내고 서버가 그린 펼침 그대로 되돌린다.
 */
(() => {
    const compactLayout = window.matchMedia('(max-width: 860px)');
    // 줄 끝에서 이만큼 남으면 옆 펼침을 미리 받아 둔다. (한 번 넘길 때 기다리지 않게)
    const PREFETCH_DISTANCE = 1;
    // 손가락이 이만큼(px) 움직이면 가로/세로를 정한다. 손 떨림 정도만 거른다.
    const DIRECTION_LOCK_PX = 6;
    // 놓았을 때 한 장 폭의 이 비율 이상 끌었으면 옆 장으로 넘어간다.
    const COMMIT_RATIO = 0.18;
    // 이보다 빠르게 튕기면 짧게 끌어도 넘어간다(px/ms). 아무리 빨라도 한 장까지만이다.
    const FLICK_SPEED = 0.3;
    // 튕김으로 넘기려면 적어도 한 장 폭의 이 비율은 움직여야 한다. (짧은 손 떨림은 넘김이 아니다)
    const FLICK_MIN_RATIO = 0.06;
    // 튕긴 속도는 놓기 직전 이 시간 동안의 움직임으로 잰다(ms).
    const FLICK_WINDOW = 100;
    // 넘길 장이 없는 쪽으로 당길 때: 끈 거리의 이 비율만, 한 장 폭의 이 비율까지만 따라온다.
    const EDGE_RESISTANCE = 0.15;
    const EDGE_LIMIT_RATIO = 0.15;
    // 놓은 뒤 앉는 시간(ms). CSS transition 이 이 값을 그대로 쓴다.
    const SETTLE_MS = 200;

    let state = null;

    function attach(board) {
        detach();
        if (!board || !compactLayout.matches) return;
        const view = board.querySelector('.diary-book-spread');
        if (!view) return;

        const spread = Number.parseInt(board.dataset.spread ?? '0', 10) || 0;
        const pages = annotate(board, spread, false);
        // 아직 장이 하나도 없으면 넘길 것도 없다. (빈 다이어리 안내만 그대로 보인다)
        if (!pages.length) return;

        // 보이는 창(.diary-book-spread) 안에 줄 하나를 두고, 장들을 그 줄로 옮긴다.
        const originalChildren = Array.from(view.children);
        const strip = document.createElement('div');
        strip.className = 'diary-page-strip';
        strip.append(...view.querySelectorAll(':scope > .diary-sheet-viewport'));
        view.prepend(strip);

        const editLink = board.querySelector(
            '.diary-read-tools-page:not(.is-right) .diary-page-edit');
        const position = board.querySelector('.diary-spread-position');
        state = {
            board,
            view,
            strip,
            originalChildren,
            editLink,
            position,
            total: Number.parseInt(board.dataset.pageCount ?? '0', 10) || 0,
            original: {
                position: position?.textContent,
                edit: editLink && {
                    href: editLink.getAttribute('href'),
                    title: editLink.getAttribute('title'),
                    label: editLink.getAttribute('aria-label')
                }
            },
            first: spread,
            last: spread,
            hasPrevious: !!board.querySelector('a.diary-spread-arrow[data-flip="previous"]'),
            hasNext: !!board.querySelector('a.diary-spread-arrow[data-flip="next"]'),
            loading: false,
            index: 0,
            offset: 0,
            active: null,
            drag: null,
            settleTimer: null,
            suppressClick: false
        };
        board.classList.add('is-page-track');
        strip.style.setProperty('--diary-page-settle', `${SETTLE_MS}ms`);
        view.addEventListener('pointerdown', onPointerDown);
        view.addEventListener('pointermove', onPointerMove);
        view.addEventListener('pointerup', onPointerUp);
        view.addEventListener('pointercancel', onPointerCancel);
        view.addEventListener('dragstart', onDragStart);
        view.addEventListener('click', onClickCapture, true);
        strip.addEventListener('transitionend', onTransitionEnd);

        apply();
        setActive(pages[0]);
    }

    function detach() {
        if (!state) return;
        const {view, strip} = state;
        view.removeEventListener('pointerdown', onPointerDown);
        view.removeEventListener('pointermove', onPointerMove);
        view.removeEventListener('pointerup', onPointerUp);
        view.removeEventListener('pointercancel', onPointerCancel);
        view.removeEventListener('dragstart', onDragStart);
        view.removeEventListener('click', onClickCapture, true);
        strip.removeEventListener('transitionend', onTransitionEnd);
        window.clearTimeout(state.settleTimer);
        // 이어 붙였던 장은 걷고, 서버가 그린 펼침의 자식 순서 그대로 되돌린다.
        view.replaceChildren(...state.originalChildren);
        if (state.position) state.position.textContent = state.original.position;
        if (state.editLink && state.original.edit) {
            setLink(state.editLink, state.original.edit.href,
                state.original.edit.title, state.original.edit.label);
        }
        state.board.classList.remove('is-page-track');
        state = null;
    }

    /**
     * 한 펼침 조각의 장마다 다이어리 안 순번과 그 장의 편집 주소를 적어 둔다.
     * 편집 주소는 서버가 그 장 위에 그려 둔 연필(왼쪽 장 / 오른쪽 장)의 것을 그대로 쓴다.
     * 아직 쓰지 않은 빈 오른쪽 종이는 장이 아니므로 돌려주지 않는다.
     */
    function annotate(root, spread, fresh) {
        const viewports = Array.from(root.querySelectorAll('.diary-book-spread > .diary-sheet-viewport'));
        const links = [
            root.querySelector('.diary-read-tools-page:not(.is-right) .diary-page-edit'),
            root.querySelector('.diary-read-tools-page.is-right .diary-page-edit')
        ];
        return viewports.flatMap((viewport, side) => {
            const sheet = viewport.querySelector(':scope > .diary-sheet');
            if (!sheet?.hasAttribute('data-diary-page-order')) return [];
            viewport.dataset.mobilePageNumber = String(spread * 2 + side + 1);
            if (links[side]) {
                viewport.dataset.mobileEditHref = links[side].getAttribute('href') ?? '';
                viewport.dataset.mobileEditTitle = links[side].getAttribute('title') ?? '';
                viewport.dataset.mobileEditLabel = links[side].getAttribute('aria-label') ?? '';
            }
            if (fresh) viewport.dataset.mobileExtra = 'true';
            return [viewport];
        });
    }

    function pagesOf() {
        return Array.from(state.strip.querySelectorAll(':scope > .diary-sheet-viewport[data-mobile-page-number]'));
    }

    // ── 옮기기 (줄 하나의 transform) ─────────────────────────

    /** 줄 자리 = 지금 장 번호 × 한 장 폭 + 끈 거리. 장 폭 기준 % 라 화면 폭이 바뀌어도 맞는다. */
    function apply() {
        state.strip.style.setProperty('--diary-page-index', String(state.index));
        state.strip.style.setProperty('--diary-page-drag', `${state.offset}px`);
    }

    /** 끈 거리를 지금 장 기준 한 장 폭 안으로 묶는다. 옆 장이 없는 쪽은 약하게만 따라온다. */
    function clampOffset(raw) {
        const width = state.view.clientWidth;
        const lastIndex = pagesOf().length - 1;
        if (raw > 0 && state.index === 0) {
            return Math.min(raw * EDGE_RESISTANCE, width * EDGE_LIMIT_RATIO);
        }
        if (raw < 0 && state.index === lastIndex) {
            return Math.max(raw * EDGE_RESISTANCE, -width * EDGE_LIMIT_RATIO);
        }
        return Math.max(-width, Math.min(width, raw));
    }

    /** 그 장으로 짧게 미끄러져 앉는다. 지금 장 ±1 밖으로는 가지 않는다. */
    function settleTo(index) {
        const lastIndex = pagesOf().length - 1;
        state.index = Math.max(0, Math.min(lastIndex, index));
        state.offset = 0;
        state.strip.classList.add('is-settling');
        apply();
        window.clearTimeout(state.settleTimer);
        // 움직일 거리가 없어 transitionend 가 오지 않는 경우의 마무리
        state.settleTimer = window.setTimeout(finishSettle, SETTLE_MS * 2);
        setActive(pagesOf()[state.index]);
    }

    function finishSettle() {
        if (!state) return;
        window.clearTimeout(state.settleTimer);
        state.strip.classList.remove('is-settling');
    }

    function onTransitionEnd(event) {
        if (event.target === state?.strip && event.propertyName === 'transform') finishSettle();
    }

    function onPointerDown(event) {
        if (!state || !event.isPrimary) return;
        if (event.pointerType === 'mouse' && event.button !== 0) return;
        state.suppressClick = false;

        // 앉는 중에 다시 잡으면 기다리게 하지 않고, 지금 보이는 자리에서 그대로 이어 잡는다.
        if (state.strip.classList.contains('is-settling')) {
            const shown = new DOMMatrixReadOnly(getComputedStyle(state.strip).transform).m41;
            window.clearTimeout(state.settleTimer);
            state.strip.classList.remove('is-settling');
            state.offset = clampOffset(shown + state.index * state.view.clientWidth);
            apply();
        }
        state.drag = {
            id: event.pointerId,
            startX: event.clientX,
            startY: event.clientY,
            baseOffset: state.offset,
            horizontal: null,
            samples: [{time: event.timeStamp, x: event.clientX}]
        };
    }

    function onPointerMove(event) {
        const drag = state?.drag;
        if (!drag || event.pointerId !== drag.id) return;
        const dx = event.clientX - drag.startX;
        const dy = event.clientY - drag.startY;

        if (drag.horizontal === null) {
            if (Math.abs(dx) < DIRECTION_LOCK_PX && Math.abs(dy) < DIRECTION_LOCK_PX) return;
            if (Math.abs(dy) >= Math.abs(dx)) {
                // 세로 손짓: 페이지 스크롤은 브라우저가 한다. 잡아 둔 자리가 있으면 제자리로.
                state.drag = null;
                if (state.offset !== 0) settleTo(state.index);
                return;
            }
            drag.horizontal = true;
            state.strip.classList.add('is-dragging');
            try {
                state.view.setPointerCapture(event.pointerId);
            } catch (error) {
                // 붙잡지 못해도 끌기는 계속한다.
            }
        }

        // 가로로 정해진 뒤에는 처음 눌렀던 자리부터의 거리를 그대로 따라온다.
        state.offset = clampOffset(drag.baseOffset + dx);
        drag.samples.push({time: event.timeStamp, x: event.clientX});
        while (drag.samples.length > 2 && event.timeStamp - drag.samples[0].time > FLICK_WINDOW) {
            drag.samples.shift();
        }
        apply();
    }

    function onPointerUp(event) {
        const drag = state?.drag;
        if (!drag || event.pointerId !== drag.id) return;
        state.drag = null;
        if (!drag.horizontal) {
            // 탭이면 링크·사진 누르기는 그대로. 앉는 중에 잡았던 것이면 마저 앉힌다.
            if (state.offset !== 0) settleTo(state.index);
            return;
        }
        endDrag(event.pointerId);
        // 끌었던 손짓 끝의 click 은 누르기가 아니다.
        state.suppressClick = true;

        const width = state.view.clientWidth;
        const first = drag.samples[0];
        const last = drag.samples[drag.samples.length - 1];
        const speed = (last.x - first.x) / Math.max(1, last.time - first.time);
        // 판단은 이번 손짓이 움직인 양으로 한다. (앉는 중에 이어 잡았으면 잡은 자리부터 잰다)
        const moved = state.offset - drag.baseOffset;
        const farEnough = Math.abs(moved) >= width * COMMIT_RATIO;
        const flicked = Math.abs(speed) >= FLICK_SPEED && Math.sign(speed) === Math.sign(moved)
            && Math.abs(moved) >= width * FLICK_MIN_RATIO;
        const direction = moved < 0 ? 1 : -1; // 왼쪽으로 끌었으면 다음 장
        // 아무리 세게 튕겨도 목표는 지금 장의 바로 옆 장까지다.
        settleTo(moved !== 0 && (farEnough || flicked) ? state.index + direction : state.index);
    }

    function onPointerCancel(event) {
        const drag = state?.drag;
        if (!drag || event.pointerId !== drag.id) return;
        state.drag = null;
        if (drag.horizontal) endDrag(event.pointerId);
        settleTo(state.index);
    }

    function endDrag(pointerId) {
        state.strip.classList.remove('is-dragging');
        try {
            if (state.view.hasPointerCapture(pointerId)) state.view.releasePointerCapture(pointerId);
        } catch (error) {
            // 이미 풀렸으면 그대로 둔다.
        }
    }

    // 마우스로 사진을 끌 때 브라우저의 그림 끌기가 손짓을 가져가지 않게 한다.
    function onDragStart(event) {
        event.preventDefault();
    }

    function onClickCapture(event) {
        if (!state?.suppressClick) return;
        state.suppressClick = false;
        event.preventDefault();
        event.stopPropagation();
    }

    // ── 지금 장 / 옆 펼침 ─────────────────────────────────────

    function setActive(page) {
        if (!state || !page || state.active === page) return;
        state.active = page;
        if (state.position && state.total > 0) {
            state.position.textContent = `${page.dataset.mobilePageNumber} / 전체 ${state.total}장`;
        }
        if (state.editLink && page.dataset.mobileEditHref) {
            setLink(state.editLink, page.dataset.mobileEditHref,
                page.dataset.mobileEditTitle, page.dataset.mobileEditLabel);
        }
        prefetch();
    }

    function setLink(link, href, title, label) {
        link.setAttribute('href', href);
        if (title) link.setAttribute('title', title);
        if (label) link.setAttribute('aria-label', label);
    }

    /** 지금 보는 장이 줄 끝에 가까우면 그쪽 옆 펼침을 하나 받아 온다. (한 번에 하나씩) */
    function prefetch() {
        if (!state || state.loading || !state.active) return;
        const pages = pagesOf();
        if (state.hasNext && state.index >= pages.length - 1 - PREFETCH_DISTANCE) {
            load(state.last + 1, true);
        } else if (state.hasPrevious && state.index <= PREFETCH_DISTANCE) {
            load(state.first - 1, false);
        }
    }

    async function load(spread, isNext) {
        const current = state;
        current.loading = true;
        try {
            const fresh = await fetchBoard(current.board.dataset.spreadUrl, spread);
            // 앞에 끼우면 줄의 자리 번호가 바뀌므로, 끄는 중이거나 앉는 중이면 끝날 때까지 기다린다.
            while (state === current && !isNext
                    && (current.drag || current.strip.classList.contains('is-settling'))) {
                await new Promise(done => window.setTimeout(done, SETTLE_MS / 2));
            }
            // 받는 동안 화면이 바뀌었으면(넓어졌거나 조각이 갈렸으면) 버린다.
            if (state !== current) return;
            const pages = annotate(fresh, spread, true);
            if (isNext) {
                current.strip.append(...pages);
                current.last = spread;
                current.hasNext = !!fresh.querySelector('a.diary-spread-arrow[data-flip="next"]');
            } else {
                const firstPage = pagesOf()[0];
                pages.forEach(page => current.strip.insertBefore(page, firstPage));
                // 보고 있던 장이 그대로 보이게 자리 번호만 늘린다(움직임 없이).
                current.index += pages.length;
                apply();
                current.first = spread;
                current.hasPrevious = !!fresh.querySelector('a.diary-spread-arrow[data-flip="previous"]');
            }
            pages.forEach(page => {
                window.diaryPageScale?.refresh(page);
                // 되풀이형 마스킹테이프는 PC 넘김과 같은 렌더러로 다시 이어 붙인다.
                page.querySelectorAll('.diary-sticker[data-tape-center]')
                    .forEach(item => window.diaryTape?.render(item));
            });
        } catch (error) {
            // 받지 못하면 그 방향은 지금 있는 장까지만 넘긴다. (다음에 그 끝에 닿으면 다시 시도한다)
        } finally {
            current.loading = false;
        }
        if (state === current) prefetch();
    }

    async function fetchBoard(url, spread) {
        if (!url) throw new Error('펼침 주소를 찾을 수 없습니다');
        const response = await fetch(`${url}?spread=${spread}`, {
            credentials: 'same-origin',
            headers: {'Accept': 'text/html'}
        });
        if (!response.ok) throw new Error('페이지를 불러오지 못했습니다');
        const fresh = new DOMParser().parseFromString(await response.text(), 'text/html')
            .getElementById('diary-read-board');
        if (!fresh) throw new Error('페이지를 불러오지 못했습니다');
        return fresh;
    }

    // 좁은 화면에서는 좌우 방향키가 펼침이 아니라 한 장씩 넘긴다. (입력 중일 때는 넘기지 않는다)
    document.addEventListener('keydown', (event) => {
        if (!state || state.drag || event.metaKey || event.ctrlKey || event.altKey) return;
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
        if (event.target.closest('input, textarea, select, [contenteditable="true"]')) return;
        event.preventDefault();
        settleTo(state.index + (event.key === 'ArrowRight' ? 1 : -1));
    });

    compactLayout.addEventListener('change', () => attach(document.getElementById('diary-read-board')));

    // PC 넘김이 책 자리를 갈아 끼우면 새 조각에 다시 붙는다. (diary-page-flip.js 가 부른다)
    window.diaryMobilePages = {attach};

    document.addEventListener('DOMContentLoaded', () => attach(document.getElementById('diary-read-board')));
})();
