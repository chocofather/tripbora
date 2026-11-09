package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.CountryCategory;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 하위 지역 pill 의 현재 선택 표시를 본다.
 *
 * <p>국내 구/군과 해외 도시가 같은 규칙을 따라야 한다 — 표시 이름이 아니라 지역 id 로 고른다.
 * 그래서 언어를 바꿔도 같은 pill 이 선택된 채로 남는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationSubregionSelectionTest {

    @Mock private DestinationService destinationService;
    @Mock private DestinationImageService destinationImageService;
    @Mock private CountryCategoryService countryCategoryService;
    @Mock private DestinationCommentService destinationCommentService;
    @Mock private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock private HttpServletRequest request;

    private DestinationController controller;

    /* 국내: 서울(3) > 종로구(4) */
    private final CountryCategory seoul = region(38L, "서울", 3, 7L);
    private final CountryCategory jongno = region(235L, "종로구", 4, 38L);
    /* 해외: 아시아(1) > 일본·태국(2) > 후쿠오카·사가(3) */
    private final CountryCategory asia = region(1L, "아시아", 1, null);
    private final CountryCategory japan = region(10L, "일본", 2, 1L);
    private final CountryCategory thailand = region(11L, "태국", 2, 1L);
    private final CountryCategory fukuoka = region(101L, "후쿠오카", 3, 10L);
    private final CountryCategory saga = region(102L, "사가", 3, 10L);

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.KOREAN.getLocale());
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService,
                referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)), DestinationGalleryTestSupport.empty());

        when(countryCategoryService.getById(38L)).thenReturn(seoul);
        when(countryCategoryService.getById(235L)).thenReturn(jongno);
        when(countryCategoryService.getById(1L)).thenReturn(asia);
        when(countryCategoryService.getById(10L)).thenReturn(japan);
        when(countryCategoryService.getById(11L)).thenReturn(thailand);
        when(countryCategoryService.getById(101L)).thenReturn(fukuoka);
        when(countryCategoryService.getById(102L)).thenReturn(saga);

        when(countryCategoryService.getSubregions(7L, 3)).thenReturn(List.of(seoul));
        when(countryCategoryService.getSubregions(38L, 4)).thenReturn(List.of(jongno));
        when(countryCategoryService.getSubregions(1L, 2)).thenReturn(List.of(japan, thailand));
        when(countryCategoryService.getSubregions(10L, 3)).thenReturn(List.of(fukuoka, saga));
        when(countryCategoryService.getSubregions(11L, 3)).thenReturn(List.of());
        when(countryCategoryService.getOverseasRootIds()).thenReturn(List.of(1L));
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(7L));
        when(countryCategoryService.getAllRegionIdsUnder(anyLongId())).thenReturn(List.of(1L));

        when(destinationService.getDestinationsByRegionIdsPaged(
                anyList(), anyList(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(List.of());
        when(destinationService.countDestinationsByRegionIds(anyList(), anyList())).thenReturn(0);
        when(destinationService.convertToLocalizedDtoWithBookmark(
                anyList(), eq(null), org.mockito.ArgumentMatchers.any(), anyMap()))
                .thenReturn(List.of());
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                anyMap(), org.mockito.ArgumentMatchers.any())).thenReturn(Map.of());
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    /* === 국내 (기존 정상 동작) === */

    @Test
    void aDomesticDistrictStaysHighlightedOnTheRegionFragment() {
        Model model = fragment("domestic", 235L);

        assertThat(model.getAttribute("selectedSubregionId")).isEqualTo(235L);
        assertThat(model.getAttribute("selectedCityId")).isEqualTo(38L);
        assertThat(model.getAttribute("subregions")).isEqualTo(List.of(jongno));
    }

    @Test
    void pickingOnlyAProvinceHighlightsNoDistrict() {
        Model model = fragment("domestic", 38L);

        // 서울만 골랐을 때는 구/군 pill 이 하나도 눌린 상태가 아니다.
        assertThat(model.getAttribute("selectedSubregionId")).isNull();
        assertThat(model.getAttribute("subregions")).isEqualTo(List.of(jongno));
    }

    /* === 해외 (이번 수정 대상) === */

    @Test
    void anOverseasCityIsHighlightedOnTheRegionFragment() {
        Model model = fragment("overseas", 101L);

        // 국내 구/군과 같은 값이 내려와야 pill 이 같은 selected 스타일을 받는다.
        assertThat(model.getAttribute("selectedSubregionId")).isEqualTo(101L);
        assertThat(model.getAttribute("subregions")).isEqualTo(List.of(fukuoka, saga));
    }

    @Test
    void movingToAnotherCityMovesTheHighlight() {
        assertThat(fragment("overseas", 101L).getAttribute("selectedSubregionId"))
                .isEqualTo(101L);
        assertThat(fragment("overseas", 102L).getAttribute("selectedSubregionId"))
                .isEqualTo(102L);
    }

    @Test
    void pickingOnlyACountryLeavesEveryCityUnhighlighted() {
        Model japanOnly = fragment("overseas", 10L);

        assertThat(japanOnly.getAttribute("selectedSubregionId")).isNull();
        assertThat(japanOnly.getAttribute("subregions")).isEqualTo(List.of(fukuoka, saga));
    }

    @Test
    void switchingCountryDropsThePreviousCityHighlight() {
        assertThat(fragment("overseas", 101L).getAttribute("selectedSubregionId"))
                .isEqualTo(101L);

        // 일본 → 태국으로 옮기면 후쿠오카 표시가 남지 않는다.
        Model thailandOnly = fragment("overseas", 11L);
        assertThat(thailandOnly.getAttribute("selectedSubregionId")).isNull();
    }

    @Test
    void sortingOrPagingKeepsTheOverseasCityHighlighted() {
        Model model = new ExtendedModelMap();
        controller.destinationListFragment("overseas", 101L, 2, 12, "views", null, null, model);

        assertThat(model.getAttribute("selectedSubregionId")).isEqualTo(101L);
        assertThat(model.getAttribute("selectedCityId")).isEqualTo(101L);
    }

    @Test
    void aFullPageLoadAlsoHighlightsTheOverseasCity() {
        Model model = new ExtendedModelMap();
        controller.destinationList("overseas", 101L, 1, 12, "default", null, null, request, model);

        assertThat(model.getAttribute("selectedSubregionId")).isEqualTo(101L);
    }

    /* === 언어와 무관 === */

    @ParameterizedTest
    @CsvSource({"ko", "en", "ja", "zh-CN", "zh-TW"})
    void theHighlightSurvivesEveryLanguage(String languageTag) {
        LocaleContextHolder.setLocale(
                SupportedLanguage.fromLanguageTag(languageTag).orElseThrow().getLocale());
        // 표시 이름은 언어마다 다르지만 선택 기준은 지역 id 다.
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(101L, "Fukuoka-" + languageTag, 102L, "Saga-" + languageTag));

        Model model = fragment("overseas", 101L);

        assertThat(model.getAttribute("selectedSubregionId")).isEqualTo(101L);
        assertThat(model.getAttribute("regionDisplayNames"))
                .isEqualTo(Map.of(101L, "Fukuoka-" + languageTag, 102L, "Saga-" + languageTag));
    }

    @Test
    void theTemplateComparesRegionIdsRatherThanTranslatedNames() throws IOException {
        String fragment = resource("/templates/destination/fragment.html");

        // 국내·해외가 같은 한 줄을 함께 쓴다. 이름 비교로 바뀌면 번역 때문에 선택이 풀린다.
        assertThat(fragment)
                .contains("th:classappend=\"${selectedSubregionId} == ${r.id} ? ' selected' : ''\"")
                .contains("th:attr=\"data-city-id=${r.id}\"")
                .doesNotContain("selectedCityName} == ${regionDisplayNames");
    }

    /* === helpers === */

    private Model fragment(String type, Long regionId) {
        Model model = new ExtendedModelMap();
        controller.regionFragment(type, regionId, 1, 12, "default", null, null, model);
        return model;
    }

    private long anyLongId() {
        return org.mockito.ArgumentMatchers.anyLong();
    }

    private CountryCategory region(Long id, String name, int depth, Long parentId) {
        CountryCategory region = new CountryCategory();
        region.setId(id);
        region.setRegionName(name);
        region.setDepth(depth);
        region.setParentId(parentId);
        return region;
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
