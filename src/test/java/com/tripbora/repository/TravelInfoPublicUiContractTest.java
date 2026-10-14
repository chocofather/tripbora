package com.tripbora.repository;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TravelInfoPublicUiContractTest {

    @Test
    void listUsesPublicLayoutAccessibleTitleSearchAndUnifiedPillFilters()
            throws IOException {
        String template = resource("/templates/travel-info/list.html");
        String categoryFilter = resource("/templates/travel-info/fragments/category-filter.html");

        assertThat(template)
                .contains("layout/main :: layout")
                .contains("/css/travel-info.css")
                .contains("/js/travel-info-list.js")
                .contains("/js/travel-info-bookmark.js")
                .contains("method=\"get\"", "action=\"/travel-info\"")
                .contains("role=\"search\"", "data-travel-info-search")
                .contains("type=\"search\"", "name=\"keyword\"", "maxlength=\"100\"")
                .contains("th:value=\"${keyword}\"")
                .contains("data-travel-info-search-input", "data-travel-info-search-clear")
                // 화면 문구는 messages 로 옮겼고, 접근성 라벨 자리는 그대로다.
                .contains("aria-label=#{travelInfo.list.search.clear}", "type=\"submit\"")
                .contains("name=\"scope\"", "name=\"contentType\"", "name=\"categoryId\"")
                .contains("contentType=${contentType}", "categoryId=${categoryIds}")
                .contains("scope='DOMESTIC'", "scope='INTERNATIONAL'", "size=${pageSize}")
                .contains("data-filter-name=\"primary\"")
                // 여행정보와 축제·행사는 각각 독립된 화면이고, 버튼이 화면 종류를 들고 있다
                .contains("data-filter-content-type=\"GENERAL\"")
                .contains("data-filter-content-type=\"FESTIVAL\"")
                .contains("contentType='GENERAL'")
                .contains("contentType='FESTIVAL'")
                // 일반 여행정보에는 '전체'도, 축제·행사 버튼도 없다
                .doesNotContain("data-filter-value=\"FESTIVAL\"")
                .contains("travel-info-filter-pill", "aria-current=", "data-travel-info-reset")
                .contains("travel-info/fragments/list-results :: results")
                .doesNotContain("정보 유형", "data-filter-name=\"contentType\"")
                .doesNotContain("<select", ">적용<", ">카테고리<");
        assertThat(categoryFilter)
                .contains("id=\"travel-info-category-filter\"")
                .contains("data-filter-name=\"categoryId\"")
                .contains("th:each=\"category : ${categories}\"")
                .contains("travel-info-filter-pill", "travel-info-category-pills")
                // 유형에 따라 라벨이 갈리는 구조는 그대로이고, 문구만 messages 로 옮겼다.
                .contains("#{travelInfo.list.filter.category.festival}")
                .contains("#{travelInfo.list.filter.category.general}")
                .contains("type=\"button\"")
                .contains("aria-pressed=")
                .contains("#lists.isEmpty(categoryIds)", "#lists.contains(categoryIds, category.id)");
    }

    @Test
    void resultsFragmentKeepsCardsAndAddsAccessibleDetailLinkWithServerReturnUrl()
            throws IOException {
        String fragment = resource("/templates/travel-info/fragments/list-results.html");
        String asyncFragment = resource("/templates/travel-info/fragments/list-async.html");

        assertThat(fragment)
                .contains("th:fragment=\"results\"")
                .contains("id=\"travel-info-results\"")
                .contains("travel-info-grid", "travel-info-card", "travel-info-thumbnail")
                .contains("travel-info-thumbnail-placeholder", "등록된 이미지가 없습니다")
                .contains("travel-info-festival-period", "info.startDate", "info.endDate")
                .contains("travel-info-pagination", "keyword=${keyword}", "page=${pageNumber}")
                .contains("class=\"travel-info-sort\"", "data-travel-info-sort")
                .contains("aria-label=#{travelInfo.list.sort.label}")
                // 기본 정렬은 화면마다 다르다. 축제·행사는 행사일순, 여행정보는 최신순.
                .contains("data-sort-value=${defaultSort}", "최신순")
                .contains("#{travelInfo.list.sort.event}", "행사일순")
                .contains("data-sort-value=\"views\"", "조회순")
                .contains("th:classappend=\"${sort == defaultSort} ? ' is-active'\"")
                .contains("th:classappend=\"${sort == 'views'} ? ' is-active'\"")
                .contains("aria-current=${sort == defaultSort}")
                .contains("aria-current=${sort == 'views'}")
                .contains("sort=${sortParam}")
                .contains("th:if=\"${keyword != null}\"")
                // 검색어는 문자열 조립이 아니라 message parameter 로 들어간다.
                .contains("#{travelInfo.list.empty.keyword(${keyword})}")
                .doesNotContain("th:utext=\"${keyword}\"")
                .contains("class=\"travel-info-card-link\"")
                .contains("data-travel-info-bookmark")
                .contains("data-bookmark-url", "info.bookmarked")
                // 북마크 상태 문구도 messages 로 옮기고, 스크립트가 쓸 값은 data-* 로 실어 준다.
                .contains("aria-pressed", "#{travelInfo.bookmark.remove}")
                .contains("data-label-saved=#{travelInfo.bookmark.label.saved}")
                .contains("data-aria-save=#{travelInfo.bookmark.save}")
                .contains("travel-info-bookmark-icon")
                .contains("${info.contentType.name() == 'FESTIVAL'}")
                .contains("@{/festivals/{id}(id=${info.id},returnUrl=${listUrl})}")
                .contains("@{/travel-info/{id}(id=${info.id},returnUrl=${listUrl})}")
                .doesNotContain("♡", "♥", "<select", "travel-info-sort-select");
        assertThat(asyncFragment)
                .contains("th:fragment=\"response\"")
                .contains("id=\"travel-info-category-filter-template\"")
                .contains("travel-info/fragments/category-filter :: filter")
                .contains("travel-info/fragments/list-results :: results");
    }

    @Test
    void cssProvidesFourThreeTwoOneColumnGridAndSixteenByNineImages() throws IOException {
        String css = resource("/static/css/travel-info.css");

        assertThat(css)
                .contains("grid-template-columns: repeat(4, minmax(0, 1fr))")
                .contains("grid-template-columns: repeat(3, minmax(0, 1fr))")
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .contains("grid-template-columns: minmax(0, 1fr)")
                .contains("@media (max-width: 1100px)")
                .contains("@media (max-width: 880px)")
                .contains("@media (max-width: 620px)")
                .contains("aspect-ratio: 16 / 10")
                .contains("object-fit: cover")
                .contains(".travel-info-thumbnail-placeholder")
                .contains(".travel-info-results.is-loading")
                .contains(".travel-info-search-control", ".travel-info-search-submit")
                .contains(".travel-info-search-clear", ".travel-info-search-control:focus-within")
                .contains(".travel-info-category-pills .travel-info-filter-pill.is-active::after")
                .contains(".travel-info-card-link::after")
                .contains(".travel-info-card-bookmark")
                .contains(".travel-info-sort", ".travel-info-sort-option")
                .contains(".travel-info-sort-option.is-active")
                .contains("url('/uploads/icons/bookmark.png')")
                .contains("url('/uploads/icons/bookmark2.png')")
                .contains("z-index: 2")
                .contains("position: absolute", "inset: 0")
                .contains(".travel-info-card:focus-within")
                .doesNotContain("/images/default.png");
    }

    @Test
    void festivalCardsUseASeparatePosterFrameWithoutChangingGeneralCards()
            throws IOException {
        String fragment = resource("/templates/travel-info/fragments/list-results.html");
        String css = resource("/static/css/travel-info.css");

        assertThat(fragment)
                .contains("th:classappend=\"${info.contentType.name() == 'FESTIVAL'} ? ' is-festival'\"")
                .contains("travel-info-festival-thumbnail")
                .contains("travel-info-festival-period")
                .contains("#temporals.format(info.startDate, 'yyyy.MM.dd')")
                .contains("#temporals.format(info.endDate, 'yyyy.MM.dd')")
                .doesNotContain("travel-info-period-label");
        assertThat(css)
                .contains(".travel-info-card.is-festival .travel-info-thumbnail")
                .contains("aspect-ratio: 3 / 4")
                .contains(".travel-info-card.is-festival .travel-info-thumbnail img")
                .contains("object-fit: contain")
                .contains(".travel-info-card.is-festival .travel-info-thumbnail img.is-landscape")
                .contains(".travel-info-card.is-festival .travel-info-thumbnail-placeholder")
                .contains(".travel-info-card.is-festival .travel-info-type-meta")
                .contains(".travel-info-card.is-festival .travel-info-card-bookmark")
                .contains("@media (max-width: 620px)");
    }

    @Test
    void detailUsesPublicLayoutSharedQuillContentAllFestivalPeriodsAndNoThumbnailHero()
            throws IOException {
        String detail = resource("/templates/travel-info/detail.html");
        String css = resource("/static/css/travel-info-detail.css");

        assertThat(detail)
                .contains("layout/main :: layout")
                .contains("/css/quill-content.css")
                .contains("/css/travel-info-detail.css")
                .contains("travelInfo.categoryName", "travelInfo.title")
                .contains("travelInfo.scope.name()", "travelInfo.contentType.name()")
                .contains("travelInfo.createdAt", "travelInfo.views")
                .contains("th:if=\"${travelInfo.updated}\"")
                .contains("!#lists.isEmpty(travelInfo.periods)")
                .contains("th:each=\"period : ${travelInfo.periods}\"")
                .contains("period.startDate", "period.endDate")
                .contains("class=\"travel-info-detail-content rich-text-content rich-text-image-layout\"")
                .contains("th:utext=\"${travelInfo.content}\"")
                .contains("th:href=\"${listUrl}\"", "#{travelInfo.detail.backToList}")
                .contains("/js/travel-info-bookmark.js")
                .contains("travel-info-detail-bookmark", "travelInfo.bookmarked")
                // 저장 상태 문구도 messages 로 옮겼다.
                .contains("#{travelInfo.bookmark.label.saved}", "#{travelInfo.bookmark.label.save}")
                .doesNotContain("thumbnail", "info_images", "userId", "categoryId", "♡", "♥");
        assertThat(css)
                .contains("width: min(960px, calc(100% - 40px))")
                .contains("overflow-wrap: anywhere")
                .contains(".travel-info-detail-content img")
                .contains("max-width: 100%", "height: auto")
                .contains("url('/uploads/icons/bookmark.png')")
                .contains("url('/uploads/icons/bookmark2.png')")
                .contains("@media (max-width: 720px)")
                .contains("@media (max-width: 430px)");
    }

    @Test
    void travelInfoBookmarkJavascriptUsesDelegationCsrfAndPessimisticStateUpdate()
            throws IOException {
        String javascript = resource("/static/js/travel-info-bookmark.js");

        assertThat(javascript)
                .contains("document.addEventListener('click'")
                .contains("event.target.closest(BOOKMARK_SELECTOR)")
                .contains("event.preventDefault()", "event.stopPropagation()")
                .contains("window.location.assign(LOGIN_URL)")
                .contains("const LOGIN_URL = '/login'")
                .doesNotContain("redirect=")
                .contains("button.disabled = true", "button.disabled = false")
                .contains("bookmarked ? 'DELETE' : 'POST'")
                .contains("meta[name=\"_csrf\"]", "meta[name=\"_csrf_header\"]")
                .contains("[csrfHeader]: csrfToken")
                .contains("if (!response.ok)")
                .contains("updateButton(button, !bookmarked)")
                .contains("aria-pressed", "aria-label")
                .doesNotContain("♡", "♥", "icon.textContent")
                .doesNotContain("AbortController");
    }

    @Test
    void structuredGalleryModalOpensClosesMovesAndRestoresThePage() throws IOException {
        String javascript = resource("/static/js/structured-gallery.js");
        String css = resource("/static/css/travel-info-structured.css");

        // 본문 STRUCTURED 사진만 그려진 순서대로 모은다. 모달은 하나뿐이고 한 번만 초기화한다.
        assertThat(javascript)
                .contains("const IMAGE_SELECTOR = '.structured-content [data-structured-gallery-image]';")
                .contains("Array.from(document.querySelectorAll(IMAGE_SELECTOR))")
                .contains("modal.dataset.galleryReady === 'true'")
                .doesNotContain("innerHTML");
        // 열기: 사진 누르기·Enter·Space (위임). 닫기: 큰 사진 다시 누르기, 바탕(dialog 자신), 닫기 버튼, Esc.
        assertThat(javascript)
                .contains("event.target.closest('[data-structured-gallery-image]')")
                .contains("if (event.key !== 'Enter' && event.key !== ' ') return;")
                .contains("if (event.target === modalImage || event.target === modal) close();")
                .contains("button('[data-gallery-close]', close);")
                .contains("if (event.key === 'Escape') {");
        // 이동: 버튼은 바탕 닫기로 번지지 않고, ← → 와 함께 처음·끝이 이어진다. 한 장이면 버튼을 숨긴다.
        assertThat(javascript)
                .contains("event.stopPropagation();")
                .contains("button('[data-gallery-prev]', () => move(-1));")
                .contains("button('[data-gallery-next]', () => move(1));")
                .contains("currentIndex = (currentIndex + step + images.length) % images.length;")
                .contains("} else if (event.key === 'ArrowLeft') {", "} else if (event.key === 'ArrowRight') {")
                .contains("modal.classList.toggle('is-single', images.length <= 1);");
        // 키 처리는 열려 있는 동안만 걸고, 닫으면 스크롤·포커스를 되돌린다.
        assertThat(javascript)
                .contains("document.addEventListener('keydown', onKeydown);")
                .contains("document.removeEventListener('keydown', onKeydown);")
                .contains("document.body.style.overflow = 'hidden';")
                .contains("document.body.style.overflow = previousOverflow;")
                .contains("if (returnFocus) returnFocus.focus({preventScroll: true});");
        // 제목·설명은 data, 출처는 본문 출처 줄 복사(링크 target / rel 그대로). alt 는 본문 사진 것.
        assertThat(javascript)
                .contains("setText(title, source.dataset.galleryTitle);")
                .contains("setText(description, source.dataset.galleryDescription);")
                .contains("sourceCredit.cloneNode(true)")
                .contains("modalImage.alt = source.getAttribute('alt') || '';");
        // 사진은 잘리지 않고 비율 그대로 화면 안에서 크게. 누를 수 있는 사진만 zoom-in.
        assertThat(css)
                .contains(".structured-content [data-structured-gallery-image][role=\"button\"] {\n    cursor: zoom-in;")
                .contains("    object-fit: contain;\n    cursor: zoom-out;")
                .contains(".structured-gallery.is-single .structured-gallery-nav {\n    display: none;");
    }

    @Test
    void javascriptFetchesFragmentsSynchronizesHistoryCancelsStaleRequestsAndFallsBack()
            throws IOException {
        String javascript = resource("/static/js/travel-info-list.js");

        assertThat(javascript)
                .contains("const PRIMARY_FILTER_NAME = 'primary'")
                .contains("const CATEGORY_FILTER_NAME = 'categoryId'")
                .contains("const CONTENT_TYPE_PARAMETER_NAME = 'contentType'")
                .contains("const KEYWORD_PARAMETER_NAME = 'keyword'")
                .contains("const SORT_PARAMETER_NAME = 'sort'")
                .contains("const SORT_VIEWS = 'views'")
                .contains("const SEARCH_DEBOUNCE_MS = 200")
                .contains("const KEYWORD_MAX_LENGTH = 100")
                .contains("const url = new URL(selectedUrl.href)")
                // 필터 초기화는 보고 있던 화면을 유지한다
                .contains("new URL(reset.getAttribute('href'), window.location.origin)")
                .contains("searchParams.getAll(CATEGORY_FILTER_NAME)")
                .contains("searchParams.delete(CATEGORY_FILTER_NAME)")
                .contains("searchParams.append(CATEGORY_FILTER_NAME, categoryId)")
                .contains("selectedCategoryIds.has(value)")
                .contains("selectedCategoryIds.delete(value)")
                .contains("selectedCategoryIds.clear()")
                .contains("primaryFilterUrl")
                .contains("syncPrimaryFilterUi")
                // 지역 범위를 바꿔도 화면 종류(여행정보/축제·행사)는 그대로 유지한다
                .contains("url.searchParams.set(CONTENT_TYPE_PARAMETER_NAME, nextContentType)")
                .contains("normalizeContentType(control.dataset.filterContentType)")
                // 유형마다 주제 카테고리가 다르므로 유형이 바뀌면 고른 주제를 지운다
                .contains("if (currentContentType !== nextContentType) {")
                .doesNotContain("categoryContentType")
                .contains("const sortOption = event.target.closest(SORT_SELECTOR)")
                .contains("loadResults(sortUrl(sortOption), 'push')")
                .contains("control.dataset.sortValue")
                .contains("url.searchParams.delete('page')")
                .contains("url.searchParams.delete(SORT_PARAMETER_NAME)")
                .contains("url.searchParams.set(SORT_PARAMETER_NAME, SORT_VIEWS)")
                .contains("syncSortUi(url)")
                .contains("pill.setAttribute('aria-pressed', String(isActive))")
                .contains("fetch(")
                .contains("'X-Requested-With': 'XMLHttpRequest'")
                .contains("window.history.pushState")
                .contains("window.addEventListener('popstate'")
                .contains("new AbortController()")
                .contains("activeController.abort()")
                .contains("searchParams.set(KEYWORD_PARAMETER_NAME, keyword)")
                .contains("searchParams.delete(KEYWORD_PARAMETER_NAME)")
                .contains("url.searchParams.delete('page')")
                .contains("searchForm.addEventListener('submit'")
                .contains("searchInput.addEventListener('input'")
                .contains("searchInput.addEventListener('compositionstart'")
                .contains("searchInput.addEventListener('compositionupdate'")
                .contains("searchInput.addEventListener('compositionend'")
                .doesNotContain("isComposing")
                .contains("window.setTimeout", "SEARCH_DEBOUNCE_MS")
                .contains("if (input.value !== keyword)")
                .contains("syncSearchUi(url)")
                .contains("searchInput.value = ''")
                .contains(".travel-info-pagination a")
                .contains("replaceResults(await response.text())")
                .contains("CATEGORY_FILTER_TEMPLATE_SELECTOR")
                .contains("replaceWith(nextCategoryFilter)")
                .contains("window.location.assign(url.href)");
        assertThat(javascript).doesNotContain("document.addEventListener('change'");
    }

    @Test
    void hiddenContentTypeIsPreservedByScopeCategoryAndPaginationLinks() throws IOException {
        String template = resource("/templates/travel-info/list.html");
        String fragment = resource("/templates/travel-info/fragments/list-results.html");

        assertThat(template)
                .contains("sort=${sortParam}")
                .contains("categoryId=${categoryIds}");
        assertThat(fragment)
                .contains("keyword=${keyword},scope=${scope},contentType=${contentType},categoryId=${categoryIds}")
                .contains("sort=${sortParam}")
                .contains("page=${currentPage - 1}")
                .contains("page=${pageNumber}")
                .contains("page=${currentPage + 1}");
    }

    @Test
    void headerLinksTravelInfoMainAndPublicFilterShortcuts() throws IOException {
        String header = resource("/templates/fragments/header.html");
        var document = Jsoup.parse(header);

        assertThat(document.select("a[href='/travel-info']").stream())
                .anyMatch(link -> "#{nav.travelInfo}".equals(link.attr("th:text")));
        // 여행정보 하위 메뉴는 국내정보 / 해외정보 / 축제·행사 셋이다 ('전체'는 없앴다)
        assertThat(document.select("a[href='/travel-info?scope=DOMESTIC']")).hasSize(1);
        assertThat(document.select("a[href='/travel-info?scope=INTERNATIONAL']")).hasSize(1);
        assertThat(document.select("a[href='/travel-info?contentType=FESTIVAL']")).hasSize(1);
        assertThat(document.select("a[href='/travel-info?contentType=FESTIVAL']").text())
                .isEqualTo("축제·행사");
        assertThat(document.select("ul").stream()
                .filter(list -> "infoLinks".equals(list.attr("th:fragment")))
                .findFirst().orElseThrow().select("li")).hasSize(3);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
