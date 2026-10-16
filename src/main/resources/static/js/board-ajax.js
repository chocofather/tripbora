const BOARD_SORT_TYPES = new Set(['latest', 'oldest', 'views', 'comments', 'bookmarks']);
const smartphoneBoardList = window.matchMedia('(max-width: 600px)');
let boardRequestVersion = 0;

function boardPageSize(params) {
    const size = params.get('size');
    return smartphoneBoardList.matches ? '8' : (size === '8' ? '10' : (size || '10'));
}

function syncBoardViewportSize(resetPage = false) {
    const params = new URLSearchParams(window.location.search);
    const size = smartphoneBoardList.matches ? '8' : '10';
    const renderedSize = document.querySelector('.board-list[data-page-size]')?.dataset.pageSize;
    if (renderedSize === size && (params.get('size') || '10') === size) return;
    return loadBoardList(resetPage ? 1 : params.get('page'), params.get('sort'), 'replace', size);
}

function normalizeBoardSort(sort) {
    const normalized = typeof sort === 'string' ? sort.toLowerCase() : 'latest';
    return BOARD_SORT_TYPES.has(normalized) ? normalized : 'latest';
}

function updateBoardSortState(sort) {
    const activeSort = normalizeBoardSort(sort);
    document.querySelectorAll('.board-sort-button[data-sort]').forEach(button => {
        const isActive = button.dataset.sort === activeSort;
        button.classList.toggle('active', isActive);
        button.setAttribute('aria-pressed', String(isActive));
    });
}

function loadBoardList(page, sort, updateHistory = true, size) {
    const params = new URLSearchParams(window.location.search);
    params.set('page', Math.max(Number(page) || 1, 1).toString());
    const activeSort = normalizeBoardSort(sort);
    params.set('sort', activeSort);

    params.set('size', size || boardPageSize(params));
    const version = ++boardRequestVersion;

    return fetch(`/board/fragment?${params.toString()}`)
        .then(res => {
            if (!res.ok) {
                throw new Error(`게시판 목록 요청 실패: ${res.status}`);
            }
            return res.text();
        })
        .then(html => {
            if (version !== boardRequestVersion) return;
            document.getElementById('board-fragment-container').innerHTML = html;
            updateBoardSortState(activeSort);
            if (updateHistory === 'replace' || (!updateHistory && params.get('size') !== new URLSearchParams(window.location.search).get('size'))) {
                window.history.replaceState(null, '', `${window.location.pathname}?${params.toString()}`);
            } else if (updateHistory) {
                window.history.pushState(null, '', `${window.location.pathname}?${params.toString()}`);
            }
        })
        .catch(error => {
            if (version !== boardRequestVersion) return;
            console.error(error);
            alert('게시판 목록을 불러오지 못했습니다.');
        });
}

document.addEventListener('DOMContentLoaded', () => {
    const params = new URLSearchParams(window.location.search);
    updateBoardSortState(params.get('sort'));
    if (smartphoneBoardList.matches || params.get('size') === '8') syncBoardViewportSize();
    smartphoneBoardList.addEventListener('change', () => {
        boardRequestVersion++;
        syncBoardViewportSize(true);
    });
    document.querySelectorAll('.board-sidebar a').forEach(link => {
        link.addEventListener('click', () => {
            const url = new URL(link.href, window.location.origin);
            url.searchParams.set('size', smartphoneBoardList.matches ? '8' : '10');
            link.href = url.toString();
        });
    });

});

window.addEventListener('popstate', () => {
    const params = new URLSearchParams(window.location.search);
    loadBoardList(params.get('page'), params.get('sort'), false);
});
