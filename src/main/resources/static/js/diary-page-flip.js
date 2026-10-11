/**
 * 다이어리 페이지 넘김.
 *
 * 읽기 화면에서는 화면을 새로 고치지 않고, 넘김 연출을 보여 주는 동안 서버에서 그 펼침 조각
 * (diary/detail :: readBoard)만 받아 책 자리를 통째로 갈아 끼운다.
 * 종이/사진/스티커 HTML 은 서버 조각을 그대로 쓰므로 여기서 다시 만들지 않는다.
 * 연출 자체(넘어가는 종이 한 면)는 예전 그대로다.
 *
 * 편집 화면의 페이지 이동은 지금까지처럼 연출 뒤에 주소로 이동한다.
 *
 * 이벤트는 문서에 한 번만 걸어 두므로 조각을 몇 번 갈아 끼워도 늘어나지 않는다.
 */
document.addEventListener('DOMContentLoaded', () => {
    let board = document.getElementById('diary-read-board');
    let spread = document.querySelector('.diary-book-spread');
    if (!spread) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const compactLayout = window.matchMedia('(max-width: 860px)');
    const FLIP_DURATION = 560;
    const SLIDE_DURATION = 440;

    let flipping = false;
    let request = null;

    document.addEventListener('click', (event) => {
        const link = event.target.closest('[data-flip]');
        if (!link) return;
        // 새 탭으로 여는 조작은 그대로 둔다.
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

        event.preventDefault();
        move(link.dataset.flip === 'next', link.href);
    });

    // 읽기 화면에서는 좌우 방향키로도 같은 함수를 탄다. (입력 중일 때는 넘기지 않는다)
    document.addEventListener('keydown', (event) => {
        if (!board || event.metaKey || event.ctrlKey || event.altKey) return;
        if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
        if (event.target.closest('input, textarea, select, [contenteditable="true"]')) return;
        // 좁은 화면은 펼침이 아니라 한 장씩 넘긴다(diary-mobile-pages.js 가 맡는다).
        if (compactLayout.matches) return;

        const link = board.querySelector(
            `a.diary-spread-arrow[data-flip="${event.key === 'ArrowRight' ? 'next' : 'previous'}"]`);
        if (!link) return;
        event.preventDefault();
        move(event.key === 'ArrowRight', link.href);
    });

    // 뒤로/앞으로: 주소의 spread 를 그대로 되살린다. (연출 없이 조각만 바꾼다)
    window.addEventListener('popstate', () => {
        if (!board) return;
        const target = spreadOf(window.location.search);
        if (target === null || String(target) === board.dataset.spread) return;
        fetchBoard(target)
            .then(applyBoard)
            .catch((error) => {
                if (error.name === 'AbortError' || error.stale) return;
                // 실패하면 보고 있던 펼침을 그대로 둔다. (주소만 앞서가지 않게 되돌린다)
                history.replaceState({}, '', spreadUrl(board.dataset.spread));
            });
    });

    /**
     * 한 번의 펼침 이동.
     * 읽기 화면은 [연출 시작 → 조각 요청 → 연출이 끝나면 갈아 끼우기] 로 이어 붙여
     * 화면이 비거나 깜빡이지 않게 한다.
     */
    function move(isNext, href) {
        if (flipping) return; // 넘어가는 동안의 연속 조작은 무시한다.
        flipping = true;

        // 본문에 저장하지 않은 입력이 있으면 먼저 저장하고, 실패하면 이동하지 않는다.
        saveOpenPages().then((saved) => {
            if (!saved) {
                flipping = false;
                return;
            }

            // 편집 화면(조각 없음)은 지금까지처럼 주소로 이동한다.
            if (!board) {
                if (reducedMotion.matches) {
                    window.location.href = href;
                    return;
                }
                startFlip(isNext, () => {
                    window.location.href = href;
                });
                return;
            }

            const target = spreadOf(href);
            if (target === null) {
                flipping = false;
                return;
            }

            // 연출과 요청을 함께 시작하되, 갈아 끼우는 것은 연출이 끝난 뒤다.
            // (연출 도중에 종이가 바뀌면 넘김이 끊겨 보인다)
            const loaded = fetchBoard(target).catch(error => error);
            const shown = reducedMotion.matches
                ? Promise.resolve()
                : new Promise(done => startFlip(isNext, done));

            Promise.all([loaded, shown]).then(([fresh]) => {
                flipping = false;
                if (!(fresh instanceof Element)) {
                    // 취소되었거나 더 최신 요청이 있으면 조용히 넘어간다.
                    if (fresh && (fresh.name === 'AbortError' || fresh.stale)) return;
                    showError();
                    return;
                }
                applyBoard(fresh);
                // 사용자가 스스로 넘긴 것이라 뒤로 가기로 돌아올 수 있게 남긴다.
                history.pushState({}, '', spreadUrl(target));
            });
        });
    }

    /** 그 펼침 조각을 받아 온다. (늦게 온 응답은 버려 마지막 화면을 덮지 않는다) */
    async function fetchBoard(target) {
        const url = board.dataset.spreadUrl;
        if (!url) throw new Error('펼침 주소를 찾을 수 없습니다');

        request?.abort();
        const controller = new AbortController();
        request = controller;

        const response = await fetch(`${url}?spread=${target}`, {
            credentials: 'same-origin',
            headers: {'Accept': 'text/html'},
            signal: controller.signal
        });
        if (response.status === 401) {
            window.location.href = '/login?redirect='
                + encodeURIComponent(window.location.pathname + window.location.search);
            throw stale();
        }
        if (!response.ok) throw new Error('페이지를 불러오지 못했습니다');

        const html = await response.text();
        // 기다리는 동안 더 최신 요청이 시작됐으면 이 응답은 버린다.
        if (request !== controller) throw stale();

        const fresh = new DOMParser().parseFromString(html, 'text/html')
            .getElementById('diary-read-board');
        if (!fresh) throw new Error('페이지를 불러오지 못했습니다');
        return fresh;
    }

    /** 받아 온 조각으로 책 자리를 통째로 바꾼다. */
    function applyBoard(fresh) {
        board.replaceWith(fresh);
        board = fresh;
        spread = board.querySelector('.diary-book-spread');
        window.diaryPageScale?.refresh(board);
        // 새로 그려진 되풀이형 마스킹테이프를 같은 렌더러로 다시 이어 붙인다.
        board.querySelectorAll('.diary-sticker[data-tape-center]')
            .forEach(item => window.diaryTape?.render(item));
        // 좁은 화면이면 새 조각에서 다시 모든 장을 한 줄로 이어 넘긴다.
        window.diaryMobilePages?.attach(board);
    }

    /** 취소/지난 응답 표시. (사용자 오류로 알리지 않는다) */
    function stale() {
        const error = new Error('지난 요청');
        error.stale = true;
        return error;
    }

    /** 본문 편집기가 있으면 저장을 먼저 끝낸다. (편집기가 없으면 그대로 통과) */
    function saveOpenPages() {
        const editor = window.diaryEditor;
        if (!editor || typeof editor.flush !== 'function') {
            return Promise.resolve(true);
        }
        return editor.flush().catch(() => false);
    }

    function startFlip(isNext, done) {
        // 넘어가는 동안에는 편집(드래그/resize/rotate)과 액션이 시작되지 않게 막는다.
        spread.classList.add('is-flipping');

        let paper = null;
        const stateClass = compactLayout.matches
            ? (isNext ? 'is-slide-next' : 'is-slide-previous')
            : (isNext ? 'is-flipping-next' : 'is-flipping-previous');
        spread.classList.add(stateClass);

        if (!compactLayout.matches) {
            paper = document.createElement('div');
            paper.className = 'diary-flip-paper ' + (isNext ? 'is-flip-next' : 'is-flip-previous');
            paper.setAttribute('aria-hidden', 'true');
            // 넘어가는 면의 종이 배경을 그대로 따라간다.
            const source = spread.querySelector(
                isNext ? '.diary-sheet-right' : '.diary-sheet-left');
            const background = Array.from(source ? source.classList : [])
                .find(name => name.startsWith('diary-sheet-bg-'));
            if (background) paper.classList.add(background);
            spread.append(paper);
        }

        const flipped = spread;
        window.setTimeout(() => {
            if (paper) paper.remove();
            // 조각이 갈린 뒤라면 이미 사라진 종이지만, 남아 있으면 표시만 걷어 낸다.
            flipped.classList.remove('is-flipping', stateClass);
            done();
        }, paper ? FLIP_DURATION : SLIDE_DURATION);
    }

    /** 주소(?spread=N)에서 펼침 번호만 꺼낸다. */
    function spreadOf(href) {
        const value = new URL(href, window.location.origin).searchParams.get('spread');
        const target = Number.parseInt(value ?? '0', 10);
        return Number.isNaN(target) || target < 0 ? null : target;
    }

    function spreadUrl(target) {
        return `${window.location.pathname}?spread=${target}`;
    }

    function showError() {
        window.alert('페이지를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.');
    }
});
