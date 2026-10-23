package com.tripbora.controller.admin;

import com.tripbora.dto.AdminDestinationDataStatusCounts;
import com.tripbora.service.amenity.AmenityService;
import com.tripbora.service.category.CategoryService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.destination.DestinationSaveOrchestrationService;
import com.tripbora.service.info.AccommodationInfoService;
import com.tripbora.service.info.ShopInfoService;
import com.tripbora.service.info.ActivityInfoService;
import com.tripbora.service.info.AttractionInfoService;
import com.tripbora.service.info.RestaurantInfoService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.kto.KtoSelectedPhotoRequestParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 관리자 여행지 목록 검색 계약.
 * 여행지명 검색과 전체/국내/해외 필터는 서로 덮어쓰지 않고 함께 적용된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminDestinationListSearchTest {

    private static final Long KOREA_ID = 1L;
    private static final Long ASIA_ID = 2L;

    @Mock
    private DestinationService destinationService;
    @Mock
    private CategoryService categoryService;
    @Mock
    private AmenityService amenityService;
    @Mock
    private CountryCategoryService countryCategoryService;
    @Mock
    private DestinationSaveOrchestrationService destinationSaveOrchestrationService;
    @Mock
    private RestaurantInfoService restaurantInfoService;
    @Mock
    private AttractionInfoService attractionInfoService;
    @Mock
    private AccommodationInfoService accommodationInfoService;
    @Mock
    private ActivityInfoService activityInfoService;
    @Mock
    private ShopInfoService shopInfoService;

    private AdminDestinationController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminDestinationController(
                destinationService,
                categoryService,
                amenityService,
                countryCategoryService,
                new KtoSelectedPhotoRequestParser(
                        new ObjectMapper(),
                        Validation.buildDefaultValidatorFactory().getValidator()),
                destinationSaveOrchestrationService,
                restaurantInfoService,
                attractionInfoService,
                accommodationInfoService,
                activityInfoService,
                shopInfoService);

        when(countryCategoryService.getKoreaRootId()).thenReturn(KOREA_ID);
        when(countryCategoryService.getAllRegionIdsUnder(KOREA_ID)).thenReturn(List.of(KOREA_ID, 10L));
        when(countryCategoryService.getAllRegionIdsUnder(ASIA_ID)).thenReturn(List.of(ASIA_ID, 20L));
        when(countryCategoryService.getOverseasRootIds()).thenReturn(List.of(ASIA_ID));
        when(countryCategoryService.getRegionsByDepth(1)).thenReturn(List.of());
        when(destinationService.getAdminDestinationDataStatusCounts(any(), any(), any()))
                .thenReturn(new AdminDestinationDataStatusCounts());
        when(destinationService.getAdminDestinationDataStatusCounts(any(), any(), any(), any()))
                .thenReturn(new AdminDestinationDataStatusCounts());
    }

    private static final List<Long> ALL_REGION_IDS = List.of(KOREA_ID, 10L, ASIA_ID, 20L);

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "missing_image", "missing_main_image", "missing_category", "missing_translation", "missing_description"})
    void dataStatusDefaultsToPublicAndHiddenShortcutTogglesBack(String status) {
        Model initial = list(null, null, null, status, null, "4");
        assertThat(initial.getAttribute("visibility")).isEqualTo("public");
        assertThat((String) initial.getAttribute("listUrl"))
                .contains("visibility=public", "dataStatus=" + status);
        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, status, null, "latest", 0, 30, true);

        Model hidden = followShortcut(initial, "hidden");
        assertThat(hidden.getAttribute("visibility")).isEqualTo("hidden");
        assertThat(hidden.getAttribute("dataStatus")).isEqualTo(status);
        Model restored = followShortcut(hidden, "hidden");
        assertThat(restored.getAttribute("visibility")).isEqualTo("public");
        assertThat(restored.getAttribute("dataStatus")).isEqualTo(status);
        assertThat(restored.getAttribute("currentPage")).isEqualTo(1);
        assertThat(restored.getAttribute("listUrl")).isEqualTo(initial.getAttribute("listUrl"));
    }

    @Test
    void hiddenWithoutDataStatusTogglesBackToDefaultAllList() {
        Model initial = list(null, null);
        Model hidden = followShortcut(initial, "hidden");
        assertThat(hidden.getAttribute("visibility")).isEqualTo("hidden");
        Model restored = followShortcut(hidden, "hidden");
        assertThat(restored.getAttribute("visibility")).isNull();
        assertThat(restored.getAttribute("listUrl")).isEqualTo("/admin/destinations");
    }

    @Test
    void selectingAnotherDataStatusWhileHiddenKeepsHidden() {
        Model hidden = followShortcut(list(null, null, null, "missing_image", null, null), "hidden");
        Model changed = followShortcut(hidden, "missing_translation");
        assertThat(changed.getAttribute("visibility")).isEqualTo("hidden");
        assertThat(changed.getAttribute("dataStatus")).isEqualTo("missing_translation");
        assertThat(changed.getAttribute("currentPage")).isEqualTo(1);
    }

    @Test
    void explicitAllSurvivesDataStatusChangesAndPagination() {
        when(destinationService.countAdminDestinations(ALL_REGION_IDS, null, "missing_image", null)).thenReturn(35);
        Model model = new ExtendedModelMap();
        controller.showDestinationList(null, null, "missing_image", null, null, null, null, null,
                null, null, "2", model, "all");
        assertThat(model.getAttribute("visibility")).isEqualTo("all");
        assertThat((String) model.getAttribute("listUrl")).contains("visibility=all", "dataStatus=missing_image");
        assertThat(model.getAttribute("currentPage")).isEqualTo(2);
        assertThat((String) model.getAttribute("listQuery")).contains("visibility=all", "page=2");
        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, "missing_image", null, "latest", 30, 30);
        Model changed = followShortcut(model, "missing_translation");
        assertThat(changed.getAttribute("visibility")).isEqualTo("all");
    }

    @Test
    void defaultListUsesPublicDataStatusCountsButKeepsAllTotalAndHiddenCount() {
        var all = new AdminDestinationDataStatusCounts();
        all.setTotal(100);
        all.setMissingImage(30);
        all.setHidden(20);
        var visible = new AdminDestinationDataStatusCounts();
        visible.setTotal(80);
        visible.setMissingImage(12);
        when(destinationService.getAdminDestinationDataStatusCounts(ALL_REGION_IDS, null, null)).thenReturn(all);
        when(destinationService.getAdminDestinationDataStatusCounts(ALL_REGION_IDS, null, null, true)).thenReturn(visible);
        Model model = list(null, null);
        assertThat(shortcut(model, null).count()).isEqualTo(100);
        assertThat(shortcut(model, "missing_image").count()).isEqualTo(12);
        assertThat(shortcut(model, "hidden").count()).isEqualTo(20);
    }

    @SuppressWarnings("unchecked")
    private AdminDestinationController.DataStatusShortcut shortcut(Model model, String status) {
        return ((List<AdminDestinationController.DataStatusShortcut>) model.getAttribute("statusShortcuts")).stream()
                .filter(shortcut -> java.util.Objects.equals(shortcut.status(), status)).findFirst().orElseThrow();
    }

    private Model followShortcut(Model model, String status) {
        var params = org.springframework.web.util.UriComponentsBuilder.fromUriString(shortcut(model, status).url())
                .build().getQueryParams();
        Model result = new ExtendedModelMap();
        controller.showDestinationList(null, null, params.getFirst("dataStatus"), null, null, null, null, null,
                null, null, params.getFirst("page"), result, params.getFirst("visibility"));
        return result;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"public,true", "hidden,false"})
    void visibilityCombinesWithScopeDataStatusAndSearchAndPreservesPagination(String visibility, boolean visible) {
        var ids = List.of(KOREA_ID, 10L);
        when(destinationService.countAdminDestinations(ids, "ATTRACTION", "missing_image", "궁", visible))
                .thenReturn(35);
        when(destinationService.getAdminDestinationDataStatusCounts(ids, "ATTRACTION", "궁", visible))
                .thenReturn(new AdminDestinationDataStatusCounts());
        Model model = new ExtendedModelMap();
        controller.showDestinationList("domestic", "ATTRACTION", "missing_image", null, null, null,
                null, null, " 궁 ", "name", "9", model, visibility);

        verify(destinationService).getAdminDestinationPage(ids, "ATTRACTION", "missing_image", "궁", "name", 30, 30, visible);
        assertThat(model.getAttribute("totalCount")).isEqualTo(35);
        assertThat(model.getAttribute("totalPages")).isEqualTo(2);
        assertThat(model.getAttribute("currentPage")).isEqualTo(2);
        assertThat(model.getAttribute("visibility")).isEqualTo(visibility);
        assertThat((String) model.getAttribute("listUrl")).contains("visibility=" + visibility,
                "scope=domestic", "dataStatus=missing_image", "sort=name");
        assertThat((String) model.getAttribute("listQuery")).contains("visibility=" + visibility, "page=2");
        @SuppressWarnings("unchecked")
        var shortcuts = (List<AdminDestinationController.DataStatusShortcut>) model.getAttribute("statusShortcuts");
        assertThat(shortcuts).filteredOn(shortcut -> !"hidden".equals(shortcut.status()))
                .allSatisfy(shortcut -> assertThat(shortcut.url()).contains("visibility=" + visibility));
    }

    @Test
    void hiddenShortcutCountsTheSelectedDataStatusAndPreservesOtherFilters() {
        var counts = new AdminDestinationDataStatusCounts();
        counts.setHidden(24);
        when(destinationService.getAdminDestinationDataStatusCounts(List.of(KOREA_ID, 10L), "ATTRACTION", "궁", true))
                .thenReturn(counts);
        when(destinationService.countAdminDestinations(List.of(KOREA_ID, 10L), "ATTRACTION", "missing_image", "궁", false))
                .thenReturn(6);
        Model model = new ExtendedModelMap();
        controller.showDestinationList("domestic", "ATTRACTION", "missing_image", null, null, null,
                null, null, "궁", "name", "3", model, "public");

        @SuppressWarnings("unchecked")
        var shortcuts = (List<AdminDestinationController.DataStatusShortcut>) model.getAttribute("statusShortcuts");
        var hidden = shortcuts.stream().filter(shortcut -> "hidden".equals(shortcut.status())).findFirst().orElseThrow();
        assertThat(hidden.label()).isEqualTo("숨김");
        assertThat(hidden.count()).isEqualTo(6);
        assertThat(hidden.url()).contains("visibility=hidden", "scope=domestic", "destinationType=ATTRACTION",
                "dataStatus=missing_image", "keyword=%EA%B6%81", "sort=name")
                .doesNotContain("page=", "visibility=public");
        verify(destinationService).getAdminDestinationDataStatusCounts(List.of(KOREA_ID, 10L), "ATTRACTION", "궁", true);
        verify(destinationService).countAdminDestinations(List.of(KOREA_ID, 10L), "ATTRACTION", "missing_image", "궁", true);
        verify(destinationService).countAdminDestinations(List.of(KOREA_ID, 10L), "ATTRACTION", "missing_image", "궁", false);
        verify(destinationService).getAdminDestinationPage(List.of(KOREA_ID, 10L), "ATTRACTION", "missing_image", "궁", "name", 0, 30, true);
        org.mockito.Mockito.verifyNoMoreInteractions(destinationService);
    }

    @Test
    void invalidVisibilityFallsBackToAll() {
        Model model = new ExtendedModelMap();
        controller.showDestinationList(null, null, null, null, null, null, null, null,
                null, null, null, model, "invalid");
        assertThat(model.getAttribute("visibility")).isNull();
        assertThat((String) model.getAttribute("listUrl")).doesNotContain("visibility");
        verify(destinationService).countAdminDestinations(ALL_REGION_IDS, null, null, null);
    }

    @Test
    void keywordIsTrimmedAndPassedToTheListQuery() {
        Model model = list("all", "  경복  ");

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, "경복", "latest", 0, 30);
        assertThat(model.getAttribute("keyword")).isEqualTo("경복");
    }

    @Test
    void blankKeywordMeansNoSearchCondition() {
        list("all", "   ");

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, null, "latest", 0, 30);
    }

    @Test
    void missingKeywordMeansNoSearchCondition() {
        Model model = list("all", null);

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, null, "latest", 0, 30);
        assertThat(model.getAttribute("keyword")).isNull();
    }

    @Test
    void domesticFilterAndKeywordApplyTogether() {
        Model model = list("domestic", "테스트");

        verify(destinationService).getAdminDestinationPage(List.of(KOREA_ID, 10L), null, null, "테스트", "latest", 0, 30);
        assertThat(model.getAttribute("type")).isEqualTo("domestic");
        assertThat(model.getAttribute("keyword")).isEqualTo("테스트");
    }

    @Test
    void overseasFilterAndKeywordApplyTogether() {
        Model model = list("overseas", "타워");

        verify(destinationService).getAdminDestinationPage(List.of(ASIA_ID, 20L), null, null, "타워", "latest", 0, 30);
        assertThat(model.getAttribute("type")).isEqualTo("overseas");
        assertThat(model.getAttribute("keyword")).isEqualTo("타워");
    }

    /** 기본은 최신 등록순이고, 알 수 없는 분류·데이터 상태·정렬 값은 조건 없음으로 본다. */
    @Test
    void defaultSortIsLatestAndUnknownTypeOrSortIsIgnored() {
        Model model = list(null, null, "UNKNOWN", "broken", "random", null);

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, null, "latest", 0, 30);
        assertThat(model.getAttribute("sort")).isEqualTo("latest");
        assertThat(model.getAttribute("destinationType")).isNull();
        assertThat(model.getAttribute("dataStatus")).isNull();
        assertThat(model.getAttribute("listUrl")).isEqualTo("/admin/destinations");
    }

    /** 범위·분류·검색·정렬은 함께 걸리고, 쪽을 넘겨도 조건이 쪽 이동 주소에 그대로 남는다. */
    @Test
    void scopeTypeKeywordSortAndPageApplyTogetherAndStayInPageLinks() {
        when(destinationService.countAdminDestinations(List.of(KOREA_ID, 10L), "ACCOMMODATION", null, "호텔"))
                .thenReturn(95);

        Model model = list("domestic", "호텔", "ACCOMMODATION", null, "name", "3");

        verify(destinationService).getAdminDestinationPage(
                List.of(KOREA_ID, 10L), "ACCOMMODATION", null, "호텔", "name", 60, 30);
        assertThat(model.getAttribute("currentPage")).isEqualTo(3);
        assertThat(model.getAttribute("totalPages")).isEqualTo(4);
        assertThat(model.getAttribute("pageOffset")).isEqualTo(60L);
        assertThat((String) model.getAttribute("listUrl"))
                .startsWith("/admin/destinations?")
                .contains("scope=domestic", "destinationType=ACCOMMODATION", "keyword=%ED%98%B8%ED%85%94", "sort=name")
                .doesNotContain("page=");
        assertThat((String) model.getAttribute("listQuery")).contains("page=3");
    }

    /**
     * 데이터 상태는 범위·분류와 함께 목록·건수 조회에 걸리고 쪽 이동 주소에 남는다.
     * 상단 상태별 건수는 데이터 상태만 뺀 같은 조건으로 한 번 세고, 바로가기는 다른 조건을 유지한 채 상태만 바꾼다.
     */
    @Test
    void dataStatusCombinesWithOtherFiltersAndShortcutsKeepTheRest() {
        AdminDestinationDataStatusCounts counts = new AdminDestinationDataStatusCounts();
        counts.setTotal(120);
        counts.setMissingImage(12);
        counts.setMissingMainImage(4);
        counts.setMissingCategory(3);
        counts.setMissingTranslation(19);
        counts.setMissingDescription(7);
        when(destinationService.getAdminDestinationDataStatusCounts(List.of(KOREA_ID, 10L), "ATTRACTION", null, true))
                .thenReturn(counts);
        when(destinationService.countAdminDestinations(List.of(KOREA_ID, 10L), "ATTRACTION", "missing_translation", null, true))
                .thenReturn(19);

        Model model = list("domestic", null, "ATTRACTION", "missing_translation", null, null);

        verify(destinationService).getAdminDestinationPage(
                List.of(KOREA_ID, 10L), "ATTRACTION", "missing_translation", null, "latest", 0, 30, true);
        assertThat(model.getAttribute("dataStatus")).isEqualTo("missing_translation");
        assertThat(model.getAttribute("totalCount")).isEqualTo(19);
        assertThat((String) model.getAttribute("listUrl")).contains("dataStatus=missing_translation");

        @SuppressWarnings("unchecked")
        List<AdminDestinationController.DataStatusShortcut> shortcuts =
                (List<AdminDestinationController.DataStatusShortcut>) model.getAttribute("statusShortcuts");
        assertThat(shortcuts).extracting(AdminDestinationController.DataStatusShortcut::status)
                .containsExactly(null, "missing_image", "missing_main_image", "missing_category",
                        "missing_translation", "missing_description", "hidden");
        assertThat(shortcuts).extracting(AdminDestinationController.DataStatusShortcut::count)
                .containsExactly(120, 12, 4, 3, 19, 7, 0);
        assertThat(shortcuts.get(0).url())
                .contains("scope=domestic", "destinationType=ATTRACTION")
                .doesNotContain("dataStatus");
        assertThat(shortcuts.get(1).url())
                .contains("scope=domestic", "destinationType=ATTRACTION", "dataStatus=missing_image")
                .doesNotContain("page=");
    }

    /** 범위 밖 쪽 번호는 마지막 쪽으로 맞춘다(삭제 후 마지막 쪽이 비는 경우 포함). */
    @Test
    void pageBeyondTheLastIsClampedToTheLastPage() {
        when(destinationService.countAdminDestinations(ALL_REGION_IDS, null, null, null)).thenReturn(31);

        Model model = list(null, null, null, null, null, "9");

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, null, "latest", 30, 30);
        assertThat(model.getAttribute("currentPage")).isEqualTo(2);
    }

    @Test
    void deleteReturnsToTheSameFilteredListPage() {
        String redirect = controller.deleteDestination(7L, "domestic", "CAFE", "missing_image",
                null, null, null, null, null, " 카페 ", "oldest", "2", "hidden");

        verify(destinationService).deleteById(7L);
        assertThat(redirect)
                .startsWith("redirect:/admin/destinations?")
                .contains("scope=domestic", "destinationType=CAFE", "dataStatus=missing_image",
                        "sort=oldest", "page=2", "visibility=hidden")
                .contains("keyword=%EC%B9%B4%ED%8E%98");
    }

    /** 합집합 상태(문제 있음)는 쓰지 않는다. 예전 주소로 들어와도 조건 없음으로 본다. */
    @Test
    void removedHasIssueStatusIsIgnored() {
        Model model = list(null, null, null, "has_issue", null, null);

        verify(destinationService).getAdminDestinationPage(ALL_REGION_IDS, null, null, null, "latest", 0, 30);
        assertThat(model.getAttribute("dataStatus")).isNull();
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> labels = (java.util.Map<String, String>) model.getAttribute("dataStatusLabels");
        assertThat(labels.keySet()).containsExactly("missing_image", "missing_main_image", "missing_category",
                "missing_translation", "missing_description");
    }

    private Model list(String type, String keyword) {
        return list(type, keyword, null, null, null, null);
    }

    private Model list(String scope, String keyword, String destinationType, String dataStatus,
                       String sort, String page) {
        Model model = new ExtendedModelMap();
        controller.showDestinationList(scope, destinationType, dataStatus, null, null, null, null, null,
                keyword, sort, page, model);
        return model;
    }
}
