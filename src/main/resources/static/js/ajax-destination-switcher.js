let currentRegionBarDepth = null;
const smartphoneDestinationList = window.matchMedia('(max-width: 600px)');
let destinationRequestVersion = 0;

// 서버의 기존 size 계약을 재사용한다. 카드 숨김 없이 응답과 URL 모두 8개 기준으로 맞춘다.
function destinationRequestUrl(url) {
    const request = new URL(url, window.location.origin);
    if (smartphoneDestinationList.matches) request.searchParams.set('size', '8');
    return request.toString();
}

function syncViewportPageSize(resetPage = false) {
    const size = smartphoneDestinationList.matches ? '8' : '12';
    if (getCurrentPageSize() === size) return;
    const params = new URLSearchParams(window.location.search);
    params.set('size', size);
    if (resetPage) params.set('page', '1');
    return replaceRegionFragment(`/destinations/fragment?${params}`);
}

// region-bar(상단) 전용 rebind 및 depth 저장
function rebindRegionBar() {
    bindRegionScrollArrows();
    const firstBtn = document.querySelector('.region-btn');
    currentRegionBarDepth = firstBtn ? parseInt(firstBtn.dataset.depth, 10) : null;
}

// fragment(카드/서브) 전용 rebind
function rebindList() {
    bindFragmentPagination();
    bindSubregionScrollArrows();
    bindSortButtons();
    if (window.initBookmarkButton) window.initBookmarkButton();
}

// 항상 region-bar-wrapper에서 type 읽기
function getCurrentType() {
    const regionBar = document.querySelector('.region-bar-wrapper');
    return regionBar?.dataset.type || document.body.dataset.type || 'domestic';
}

// 현재 sort, region id 가져오기
function getCurrentSort() {
    return document.querySelector('.sort-btn.active')?.dataset.sort || 'default';
}
function getCurrentRegionId() {
    const list = document.getElementById('destination-list');
    if (list && 'regionId' in list.dataset) return list.dataset.regionId;
    const sub = document.querySelector('.subregion-btn.selected');
    if (sub) return sub.dataset.cityId;
    const sel = document.querySelector('.region-btn.selected');
    return sel ? sel.dataset.regionId : '';
}

// 지금 목록의 고른 카테고리(하나, 없으면 '')와 쪽 크기. 서버가 #destination-list 에 적어 둔 값이다.
function getCurrentCategory() {
    const value = document.getElementById('destination-list')?.dataset.category || '';
    return /^\d+$/.test(value) ? value : '';
}
function getCurrentPageSize() {
    return document.getElementById('destination-list')?.dataset.pageSize || '12';
}

/*
  주소창을 지금 보이는 목록과 같게 맞춘다. 새로고침·공유·상세에서 뒤로 와도 같은 목록이 열린다.
  history: 'replace'(지역·정렬·쪽) | 'none'(뒤로 가기 복원)
*/
function syncAddressBar(requestUrl, history = 'replace') {
    if (history === 'none') return;
    const source = new URL(requestUrl, window.location.origin).searchParams;
    const list = document.getElementById('destination-list');
    if (list?.dataset.currentPage) source.set('page', list.dataset.currentPage);
    if (list?.dataset.pageSize) source.set('size', list.dataset.pageSize);
    if (list?.dataset.sort) source.set('sort', list.dataset.sort);
    const params = new URLSearchParams();
    const skipDefault = {sort: 'default', page: '1', size: '12'};
    ['type', 'region', 'sort', 'page', 'size', 'category'].forEach(name => {
        const value = source.get(name);
        if (value && value !== skipDefault[name]) params.set(name, value);
    });
    const query = params.toString();
    const url = '/destinations' + (query ? `?${query}` : '');
    window.history.replaceState(window.history.state, '', url);
}

// region-bar + 리스트 전체를 주어진 주소의 조각으로 바꾼다.
function replaceRegionFragment(url, history = 'replace') {
    url = destinationRequestUrl(url);
    const version = ++destinationRequestVersion;
    return fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(r => r.text())
        .then(html => {
            if (version !== destinationRequestVersion) return;
            const tmp = document.createElement('div');
            tmp.innerHTML = html;
            const nr = tmp.querySelector('#region-fragment-container');
            if (nr) {
                document.getElementById('region-fragment-container').replaceWith(nr);
                rebindRegionBar();
                rebindList();
                syncAddressBar(url, history);
            } else {
                console.error('region-fragment-container가 응답에 없음');
            }
        });
}

// region-bar + 리스트 전체 교체 (고른 카테고리는 유지)
function fetchRegionFragment(type, regionId, sort) {
    const params = new URLSearchParams({type});
    if (regionId) params.set('region', regionId);
    params.set('sort', sort);
    params.set('size', getCurrentPageSize());
    const category = getCurrentCategory();
    if (category) params.set('category', category);
    return replaceRegionFragment(`/destinations/fragment?${params}`);
}

// 리스트만 교체 (type, region, sort, page, size, category 모두 URL에서 읽어옴)
function fetchListFragmentByUrl(url, history = 'replace') {
    url = destinationRequestUrl(url);
    const version = ++destinationRequestVersion;
    return fetch(url, { headers: { 'X-Requested-With': 'XMLHttpRequest' } })
        .then(r => r.text())
        .then(html => {
            if (version !== destinationRequestVersion) return;
            const tmp = document.createElement('div');
            tmp.innerHTML = html;
            const dl = tmp.querySelector('#destination-list');
            if (dl) {
                document.getElementById('destination-list').replaceWith(dl);
                rebindList();
                syncAddressBar(url, history);
            } else {
                console.error('#destination-list가 응답에 없음');
            }
        });
}

// 지금 지역·정렬·쪽 크기·카테고리에 바꿀 값만 덮은 목록 주소 (page 는 1 로)
function listFragmentUrl(overrides) {
    const params = new URLSearchParams();
    params.set('type', getCurrentType());
    const region = overrides.region ?? getCurrentRegionId();
    if (region) params.set('region', region);
    params.set('sort', overrides.sort ?? getCurrentSort());
    params.set('page', '1');
    params.set('size', getCurrentPageSize());
    const category = 'category' in overrides ? overrides.category : getCurrentCategory();
    if (category) params.set('category', category);
    return `/destinations/list-fragment?${params}`;
}

// 정렬 버튼 바인딩 (1페이지로 돌아감, 쪽 크기·카테고리는 그대로)
function bindSortButtons() {
    document.querySelectorAll('.sort-btn').forEach(btn => {
        btn.onclick = e => {
            e.preventDefault();
            document.querySelectorAll('.sort-btn').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            fetchListFragmentByUrl(listFragmentUrl({sort: btn.dataset.sort || 'default'}));
        };
    });
}

// 뒤로·앞으로 가기: 그 주소의 지역·정렬·카테고리 목록을 다시 그린다(주소는 이미 맞으므로 기록은 건드리지 않는다).
function restoreListFromAddress() {
    const source = `/destinations/fragment${window.location.search}`;
    // 다른 폭에서 만들어진 과거 URL도 스마트폰에서는 실제 8개 목록으로 복원한다.
    const size = new URLSearchParams(window.location.search).get('size');
    replaceRegionFragment(source, smartphoneDestinationList.matches && size !== '8' ? 'replace' : 'none');
}

// 페이징 바인딩 (href 그대로 사용, sort/type만 덮어쓰기)
function bindFragmentPagination() {
    document.querySelectorAll('#destination-list .pagination a').forEach(link => {
        link.onclick = e => {
            e.preventDefault();
            const url = new URL(link.href, window.location.origin);
            url.pathname = '/destinations/list-fragment';
            url.searchParams.set('sort', getCurrentSort());
            url.searchParams.set('type', getCurrentType());
            fetchListFragmentByUrl(url.toString());
        };
    });
}

// 상단 지역 rail(대륙·국가·도시·국내 광역지역) 좌우. 화살표 표시는 실제 넘침으로 정한다.
function bindRegionScrollArrows() {
    const cont = document.querySelector('.region-buttons');
    window.DestinationRegionRail.create(cont, cont, '.region-btn',
        document.querySelectorAll('.region-selector .arrow.prev'),
        document.querySelectorAll('.region-selector .arrow.next'));
}

// 하위 지역 rail 좌우. 상단 rail과 같은 방식을 쓴다.
function bindSubregionScrollArrows() {
    const cont = document.querySelector('.subregion-scroll-container');
    window.DestinationRegionRail.create(cont, cont?.querySelector('.subregion-list'), '.subregion-btn',
        document.querySelectorAll('.subregion-arrow.prev'),
        document.querySelectorAll('.subregion-arrow.next'));
}

// 최초 바인딩 & 클릭 위임
document.addEventListener('DOMContentLoaded', () => {
    rebindRegionBar();
    rebindList();
    if (smartphoneDestinationList.matches) syncViewportPageSize();
    smartphoneDestinationList.addEventListener('change', () => {
        // 이전 폭에서 시작한 느린 응답이 새 폭의 목록을 덮지 않게 한다.
        destinationRequestVersion++;
        syncViewportPageSize(true);
    });

    document.addEventListener('click', e => {
        const regionBack = e.target.closest('[data-region-back]');
        if (regionBack) {
            e.preventDefault();
            fetchRegionFragment(getCurrentType(), null, getCurrentSort())
                .then(() => document.querySelector('.region-btn')?.focus({preventScroll: true}));
            return;
        }
        // 1) 상단 아이콘(region-btn) 클릭
        const rb = e.target.closest('.region-btn');
        if (rb) {
            e.preventDefault();
            const regionId = rb.dataset.regionId;
            const btnDepth = parseInt(rb.dataset.depth, 10);
            const type = getCurrentType();
            const sort = getCurrentSort();

            // depth 변경이면 전체 교체
            if (!document.querySelector('.region-btn.selected') || btnDepth !== currentRegionBarDepth) {
                fetchRegionFragment(type, regionId, sort);
            } else {
                // 같은 depth 이동: 리스트만 (정렬·쪽 크기·카테고리 유지, 다른 지역이므로 1쪽부터)
                window.DestinationRegionRail.select(rb);
                fetchListFragmentByUrl(listFragmentUrl({region: regionId, sort}));
            }
            return;
        }

        // 2) 서브지역(subregion-btn) 클릭
        const subregionItem = e.target.closest('.subregion-btn');
        const sb = subregionItem ? window.DestinationRegionRail.select(subregionItem) : null;
        if (sb) {
            e.preventDefault();
            // 서브는 page=1로, 카테고리는 유지
            fetchListFragmentByUrl(listFragmentUrl({region: sb.dataset.cityId}));
            return;
        }

    });

    window.addEventListener('popstate', restoreListFromAddress);
});
