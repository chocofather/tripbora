package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class AdminFestivalUiContractTest {

    @Test
    void festivalListUsesSeparateRouteAndFestivalColumns() throws IOException {
        String list = resource("/templates/admin/festivals/list.html");

        assertThat(list)
                .contains("layout(~{::body}, ~{::headFragment}, 'festivals')")
                .contains("th:action=\"@{/admin/festivals}\" method=\"get\"")
                .contains("name=\"scope\"", "name=\"categoryId\"")
                .contains("축제 분류", "행사기간")
                .contains("#temporals.format(festival.startDate, 'yyyy-MM-dd')")
                .contains("#temporals.format(festival.endDate, 'yyyy-MM-dd')")
                .contains("@{/admin/festivals/create}")
                .contains("@{/admin/festivals/{id}(id=${festival.id})}")
                .contains("@{/admin/festivals/{id}/edit(id=${festival.id})}")
                .contains("@{/admin/festivals/{id}/delete(id=${festival.id})}")
                .contains("method=\"post\"")
                .contains("이 축제·행사를 삭제할까요?", "등록된 행사 정보와 이미지가 함께 삭제됩니다.")
                .contains("admin-btn is-small is-danger")
                .doesNotContain("aria-disabled=\"true\"", "travel-info/create", "data-kto-festival-autofill");
    }

    @Test
    void festivalCreateFormKeepsTourApiDetailsOutsideTheOverviewEditor() throws IOException {
        String form = resource("/templates/admin/festivals/form.html");
        String autofill = resource("/static/js/admin-festival-autofill.js");

        assertThat(form)
                .contains("layout(~{::body}, ~{::headFragment}, 'festivals')")
                .contains("/js/admin-festival-autofill.js")
                .contains("data-admin-festival-autofill")
                .contains("data-festival-search-mode=\"keyword\"", "data-festival-search-mode=\"period\"")
                .contains("data-festival-search-panel=\"keyword\"", "data-festival-search-panel=\"period\"")
                .contains("data-festival-search-keyword")
                .contains("data-festival-period-year", "data-festival-month-grid", "data-festival-month=\"1\"",
                        "data-festival-month=\"12\"", "data-festival-direct-period-toggle",
                        "data-festival-direct-period-panel")
                .contains("data-festival-search-start-date", "data-festival-search-end-date",
                        "data-festival-keyword-search-button", "data-festival-period-search-button",
                        "data-festival-results", "data-festival-pagination")
                .contains("id=\"festival-title\"", "id=\"festival-category\"",
                        "id=\"festival-scope\"", "id=\"festival-start-date\"", "id=\"festival-end-date\"")
                .contains("id=\"festival-editor\"", "id=\"festival-content\"")
                .contains("id=\"festival-event-place\"", "id=\"festival-address\"",
                        "id=\"festival-play-time\"", "id=\"festival-use-time\"")
                .contains("id=\"festival-sponsor1\"", "id=\"festival-sponsor1-tel\"",
                        "id=\"festival-sponsor2\"", "id=\"festival-sponsor2-tel\"")
                .contains("id=\"festival-contact-tel\"", "id=\"festival-homepage-url\"")
                .contains("th:object=\"${festivalForm}\"", "method=\"post\"",
                        "th:action=\"${formAction}\"", "id=\"kto-festival-content-id\"")
                .doesNotContain("admin-travel-info-festival-autofill.js");

        assertThat(autofill)
                .contains("path: '/admin/api/kto/festivals/search-by-keyword'")
                .contains("path: '/admin/api/kto/festivals/search'")
                .contains("/admin/api/kto/festivals/detail?${params.toString()}")
                .contains("setSearchMode('keyword')")
                .contains("results.replaceChildren()")
                .contains("async function searchByKeyword()", "async function searchByPeriod()",
                        "function renderCandidates(payload)", "function createCandidate(item)")
                .contains("const PAGE_SIZE = 20", "pageNo: String(requestedPage)",
                        "numOfRows: String(PAGE_SIZE)", "renderPagination(pageNo, numOfRows, totalCount)")
                .contains("function searchByMonth(month, button)", "function monthDateRange(year, month)",
                        "new Date(year, month - 1, 1)", "new Date(year, month, 0)")
                .contains("function toggleDirectPeriod()", "directPeriodPanel.hidden = !expanded")
                .contains("await searchByPeriodRange(range.startDate, range.endDate, button)",
                        "await searchByPeriodRange(normalizedStartDate, normalizedEndDate, periodSearchButton)")
                .contains("setManagedField('scope', scope, 'DOMESTIC')")
                .contains("setManagedField('eventPlace', eventPlace, detail.eventPlace)")
                .contains("setManagedField('address', address, detail.address)")
                .contains("setManagedField('playTime', playTime, detail.playTime)")
                .contains("setManagedField('useTime', useTime, detail.useTimeFestival)")
                .contains("setManagedField('sponsor1', sponsor1, detail.sponsor1)")
                .contains("setManagedField('sponsor1Tel', sponsor1Tel, detail.sponsor1Tel)")
                .contains("setManagedField('sponsor2', sponsor2, detail.sponsor2)")
                .contains("setManagedField('sponsor2Tel', sponsor2Tel, detail.sponsor2Tel)")
                .contains("setManagedField('contactTel', contactTel, detail.tel)")
                .contains("setManagedField('homepageUrl', homepageUrl, detail.eventHomepage || detail.homepage)")
                .contains("setManagedEditor(detail.overview)")
                .doesNotContain("buildFestivalHtml", "행사 정보");
    }

    @Test
    void festivalCreateFormUsesNullSafeScopeSelectionInsteadOfEnumEquality() throws IOException {
        String form = resource("/templates/admin/festivals/form.html");

        assertThat(form)
                .contains("th:field=\"*{scope}\"")
                .doesNotContain("scope == T(com.tripbora.model.TravelInfoScope).DOMESTIC",
                        "T(com.tripbora.model.TravelInfoScope).DOMESTIC.equals(scope)");
    }

    @Test
    void sharedFestivalFormSeparatesEditFromTourApiAndUsesExistingImageThumbnailPicker()
            throws IOException {
        String form = resource("/templates/admin/festivals/form.html");
        String css = resource("/static/css/admin-travel-info.css");

        assertThat(form)
                .contains("th:if=\"${!editMode}\" th:src=\"@{/js/admin-festival-autofill.js}\"")
                .contains("th:if=\"${editMode and !#lists.isEmpty(festivalImages)}\"")
                .contains("th:each=\"image : ${festivalImages}\"")
                .contains("th:field=\"*{thumbnailImageId}\"")
                .contains("목록 썸네일 선택", "썸네일 해제", "대표이미지가 사용됩니다.")
                .contains("th:text=\"${editMode} ? '변경사항 저장' : '축제·행사 등록'\"");
        assertThat(css)
                .contains(".admin-festival-existing-image-picker")
                .contains("object-fit: contain");
    }

    @Test
    void festivalCreateFormProvidesACompactTourApiThumbnailPickerWithServerVerifiableKeys()
            throws IOException {
        String form = resource("/templates/admin/festivals/form.html");
        String autofill = resource("/static/js/admin-festival-autofill.js");
        String css = resource("/static/css/admin-travel-info.css");

        assertThat(form)
                .contains("id=\"kto-festival-thumbnail-selection\"")
                .contains("data-festival-image-picker", "data-festival-image-picker-items")
                .contains("목록 썸네일 선택", "목록 썸네일로 사용");
        assertThat(autofill)
                .contains("/admin/api/kto/festivals/images?${params.toString()}")
                .contains("item.selectionKey", "item.selectable", "item.unavailableReason")
                .contains("thumbnailSelection.value")
                .contains("radio.disabled")
                .doesNotContain("sourceImageUrl", "smallimageurl");
        assertThat(css)
                .contains(".admin-kto-festival-image-picker-grid")
                .contains("grid-template-columns: repeat(auto-fill, minmax(150px, 1fr))")
                .contains("@media (max-width: 560px)");
    }

    /**
     * 한국어 원문(기본 정보 → 행사 소개 → 행사 상세정보)을 모두 입력한 뒤 번역이 온다.
     * 번역 사이에 한국어 입력이 다시 끼어들면 안 된다.
     */
    @Test
    void festivalFormPutsEveryKoreanSectionBeforeTheCollapsibleTranslation() throws IOException {
        String form = resource("/templates/admin/festivals/form.html");

        int tourApi = form.indexOf("<h2>TourAPI에서 축제·행사 불러오기</h2>");
        int basic = form.indexOf("<h2>기본 정보</h2>");
        int overview = form.indexOf("<h2>행사 소개</h2>");
        int details = form.indexOf("<h2>행사 상세정보</h2>");
        int translation = form.indexOf("<h2>번역</h2>");
        int images = form.indexOf("<h2>목록 썸네일 선택</h2>");
        int actions = form.indexOf("admin-travel-info-submit-actions");

        assertThat(tourApi).isNotNegative();
        assertThat(basic).isGreaterThan(tourApi);
        assertThat(overview).isGreaterThan(basic);
        assertThat(details).isGreaterThan(overview);
        assertThat(translation).isGreaterThan(details);
        // 이미지(수정 화면)와 버튼은 번역 뒤에 온다
        assertThat(images).isGreaterThan(translation);
        assertThat(actions).isGreaterThan(images);
        // 한국어 원문 입력은 모두 번역 앞에 있다
        assertThat(form.indexOf("th:field=\"*{homepageUrl}\"")).isLessThan(translation);
        assertThat(form.indexOf("th:field=\"*{eventPlace}\"")).isLessThan(translation);

        // 번역은 공용 접기 스크립트로 기본 접힘 상태가 된다
        assertThat(form)
                .contains("data-translation-collapsible")
                .contains("/js/admin-translation-collapse.js")
                .contains(":: travelInfoTranslations(true)");
        assertThat(resource("/templates/fragments/admin/travel-info-translation-tabs.html"))
                .contains("data-translation-tabs-heading")
                .contains("data-translation-tabs-body");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
