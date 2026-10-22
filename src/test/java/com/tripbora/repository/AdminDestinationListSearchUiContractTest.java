package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 여행지 목록 검색 툴바 계약.
 * 검색 input / 검색·초기화 버튼 / 전체·국내·해외 필터가 한 폼 안에서 함께 동작해야 한다.
 */
class AdminDestinationListSearchUiContractTest {

    @Test
    void searchToolbarKeepsTheKeywordAndTheScopeFilterTogether() throws IOException {
        String list = resource("/templates/admin/destinations/list.html");

        assertThat(list)
                // 여행지명 검색 input (검색 후에도 입력값 유지)
                .contains("name=\"keyword\"")
                .contains("placeholder=\"여행지명 검색")
                .contains("aria-label=\"여행지명 검색\"")
                .contains("th:value=\"${keyword}\"")
                // 검색/조회/적용 버튼 없이 입력만으로 자동 검색한다
                .doesNotContain(">검색<")
                .doesNotContain(">조회<")
                .doesNotContain(">적용<")
                .contains(">초기화<")
                .contains("th:href=\"@{/admin/destinations}\"")
                // 전체 / 국내 / 해외 필터와 현재 상태 표시
                .contains(">전체<")
                .contains(">국내<")
                .contains(">해외<")
                .contains("' active' : ''")
                .contains("name=\"scope\"")
                // 분류·데이터 상태·정렬 select 와 조건을 유지하는 쪽 이동
                .contains("name=\"destinationType\"")
                .contains("name=\"dataStatus\"")
                .contains("name=\"sort\"")
                // 데이터 상태 바로가기와 문제가 있을 때만 붙는 행별 표시
                .contains("class=\"admin-data-status-bar\"")
                .contains("th:if=\"${dest.hasDataIssue()}\"")
                .contains("class=\"admin-pagination\"")
                .contains("@{${listUrl}(page=${currentPage + 1})}")
                // 번호는 DB ID 가 아니라 쪽을 넘겨도 이어지는 순번이다
                .contains("${pageOffset + stat.index + 1}")
                // 삭제 후에도 같은 조건으로 돌아온다
                .contains("'/delete' + ${listQuery}");
    }

    @Test
    void typeAndSortSelectsApplyImmediately() throws IOException {
        String script = resource("/static/js/admin-destination-filter.js");

        assertThat(script)
                .contains("destinationTypeSelect?.addEventListener(\"change\", submitFilters)")
                .contains("dataStatusSelect?.addEventListener(\"change\", submitFilters)")
                .contains("sortSelect?.addEventListener(\"change\", submitFilters)");
    }

    @Test
    void keywordSearchIsDebouncedAndFiltersApplyImmediately() throws IOException {
        String script = resource("/static/js/admin-destination-filter.js");

        assertThat(script)
                // 검색창만 debounce (약 500ms), timer 중첩 방지
                .contains("SEARCH_DEBOUNCE_MS = 500")
                .contains("clearTimeout")
                .contains("setTimeout")
                // IME 한글 조합 중에는 검색하지 않는다. 다음 음절 조합이 시작되면 앞 음절이 건 조회를 취소한다.
                .contains("compositionstart")
                .contains("compositionend")
                .containsPattern("\"compositionstart\", \\(\\) => \\{\\s+composing = true;[\\s\\S]*?cancelScheduledSearch\\(\\);")
                // Enter 는 이중 submit 없이 즉시 검색
                .contains("Enter")
                .contains("preventDefault")
                // 지역 select 는 변경 즉시 적용
                .contains("addEventListener(\"change\"")
                .contains("submitFilters");
    }

    @Test
    void changingAParentRegionDropsItsChildConditions() throws IOException {
        String script = resource("/static/js/admin-destination-filter.js");

        assertThat(script)
                // 대륙 변경 → 국가/도시 조건 제거, 국가 변경 → 도시 조건 제거
                .contains("resetSelect(countrySelect, \"- 국가 선택 -\")")
                .contains("resetSelect(citySelect, \"- 도시 선택 -\")")
                .contains("resetSelect(districtSelect, \"- 시/군/구 선택 -\")")
                // 전체/국내/해외 전환 시 다른 범위의 지역 조건도 비운다
                .contains("resetSelect(regionSelect, \"- 시/도 선택 -\")");
    }

    @Test
    void listActionsAndDeleteConfirmationStayUntouched() throws IOException {
        String list = resource("/templates/admin/destinations/list.html");

        assertThat(list)
                .contains("/images'}")
                .contains("/admin/destinations/edit/")
                .contains("'/delete' + ${listQuery}}")
                .contains("정말 삭제하시겠습니까?")
                // flash 메시지 영역 유지
                .contains("th:if=\"${error}\"");
    }

    @Test
    void listQueryFiltersByTheDisplayedKoreanNameWithBoundParameter() throws IOException {
        String mapper = resource("/mapper/DestinationMapper.xml");
        String select = between(mapper, "<sql id=\"adminListScopeFromWhere\"", "</sql>");
        String page = between(mapper, "<select id=\"findAdminDestinationPage\"", "</select>");

        assertThat(page)
                // 쪽 단위로만 가져오고, 데이터 상태 필터도 같은 조회 단계에서 건다
                .contains("<include refid=\"adminListFromWhere\"/>")
                .contains("<include refid=\"adminListDataStatusFilter\"/>")
                .contains("<include refid=\"adminListOrder\"/>")
                .contains("LIMIT #{size} OFFSET #{offset}")
                .doesNotContain("${sort}");
        assertThat(between(mapper, "<sql id=\"adminListOrder\"", "</sql>"))
                // 기본은 최신 등록순(id DESC)
                .contains("ORDER BY d.id DESC")
                .contains("ORDER BY d.id ASC")
                .contains("ORDER BY dt.name ASC, d.id ASC");
        assertThat(between(mapper, "<select id=\"countAdminDestinations\"", "</select>"))
                .contains("<include refid=\"adminListFromWhere\"/>")
                .contains("<include refid=\"adminListDataStatusFilter\"/>");

        assertThat(select)
                // 분류도 같은 조회 단계에서 bind parameter 로 건다
                .contains("AND d.type = #{destinationType}")
                // 목록에 보여주는 이름(dt.name) 그대로 부분 검색
                .contains("<if test=\"keyword != null and keyword != ''\">")
                .contains("AND dt.name LIKE CONCAT('%', #{keyword}, '%')")
                // 초성검색도 같은 목록 쿼리에서 bind parameter 로 처리한다
                .contains("<if test=\"chosungPattern != null and chosungPattern != ''\">")
                .contains("AND dt.name REGEXP #{chosungPattern}")
                // 지역 조건과 AND 로 함께 적용된다
                .contains("WHERE d.region_id IN")
                // 문자열 이어붙이기(${}) 금지
                .doesNotContain("${keyword}")
                .doesNotContain("${chosungPattern}");
    }

    /**
     * 데이터 상태는 상태 컬럼 없이 SQL 로 판정한다. 행별 표시는 한 쪽(30건)에만 붙이고,
     * 상단 건수는 상태 필터를 뺀 같은 조건으로 한 번만 집계한다.
     */
    @Test
    void dataStatusIsJudgedInSqlForThePageAndCountedInOneAggregate() throws IOException {
        String mapper = resource("/mapper/DestinationMapper.xml");

        assertThat(between(mapper, "<sql id=\"adminMissingImage\"", "</sql>"))
                .contains("NOT EXISTS (SELECT 1 FROM destination_images di WHERE di.destination_id = d.id)");
        assertThat(between(mapper, "<sql id=\"adminMissingMainImage\"", "</sql>"))
                .contains("EXISTS (SELECT 1 FROM destination_images di WHERE di.destination_id = d.id)")
                .contains("AND di.is_main = 1");
        assertThat(between(mapper, "<sql id=\"adminMissingCategory\"", "</sql>"))
                .contains("NOT EXISTS (SELECT 1 FROM destination_categories dc WHERE dc.destination_id = d.id)");
        // 번역 언어 목록은 등록폼 번역 슬롯과 같아야 하고, 한국어 원문은 대상이 아니다
        String languages = String.join(", ", com.tripbora.dto.DestinationForm.SUBTYPE_TRANSLATION_LANGUAGES
                .stream().map(language -> "'" + language + "'").toList());
        assertThat(between(mapper, "<sql id=\"adminCompleteTranslationRows\"", "</sql>"))
                .contains("tr.language_code IN (" + languages + ")")
                .contains("TRIM(tr.name) &lt;&gt; ''")
                .contains("COALESCE(TRIM(dt.short_description), '') = '' OR TRIM(tr.short_description) &lt;&gt; ''")
                .contains("COALESCE(TRIM(dt.description), '') = '' OR TRIM(tr.description) &lt;&gt; ''")
                .doesNotContain("'ko'");
        assertThat(between(mapper, "<sql id=\"adminMissingTranslation\"", "</sql>"))
                .contains("&lt; " + com.tripbora.dto.DestinationForm.SUBTYPE_TRANSLATION_LANGUAGES.size() + ")");

        // 데이터 상태 필터는 개별 누락 상태만 둔다(합집합 '문제 있음'은 쓰지 않는다)
        assertThat(between(mapper, "<sql id=\"adminListDataStatusFilter\"", "</sql>"))
                .contains("dataStatus == 'missing_image'")
                .contains("dataStatus == 'missing_main_image'")
                .contains("dataStatus == 'missing_category'")
                .contains("dataStatus == 'missing_translation'")
                .contains("dataStatus == 'missing_description'")
                .doesNotContain("has_issue");

        // 행별 상태는 안쪽 쿼리로 고른 한 쪽에만 바깥 쿼리에서 붙인다(행마다 따로 조회하지 않는다)
        String page = between(mapper, "<select id=\"findAdminDestinationPage\"", "</select>");
        assertThat(page)
                .contains("<include refid=\"adminMissingImage\"/> AS missingImage")
                .contains("AS completeTranslationLanguages")
                .contains(") paged");
        assertThat(page.indexOf("LIMIT #{size} OFFSET #{offset}")).isLessThan(page.indexOf(") paged"));

        String aggregate = between(mapper, "<select id=\"countAdminDestinationDataStatus\"", "</select>");
        assertThat(aggregate)
                .contains("<include refid=\"adminListScopeFromWhere\"/>")
                .doesNotContain("adminListDataStatusFilter")
                .contains("SUM(s.matchesVisibility), 0) AS total")
                .contains("AS missingDescription")
                .doesNotContain("hasIssue");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
