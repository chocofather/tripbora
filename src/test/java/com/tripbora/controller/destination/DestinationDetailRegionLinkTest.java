package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 상세 페이지의 지역 링크(/destinations?type=..&region=..)가 목록의 필터 파라미터를 그대로 쓰는지 확인.
 * type 이 비어 있으면 목록이 지역 필터를 적용하지 못한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationDetailRegionLinkTest {

    @Mock
    private DestinationService destinationService;
    @Mock
    private DestinationImageService destinationImageService;
    @Mock
    private CountryCategoryService countryCategoryService;
    @Mock
    private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock
    private DestinationCommentService destinationCommentService;

    private DestinationController controller;

    // 국내: 대한민국(1) > 서울(10) > 종로구(101) / 해외: 아시아(2) > 일본(20) > 도쿄(201)
    private static final Long KOREA_ROOT_ID = 1L;
    private static final Long ASIA_ROOT_ID = 2L;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.KOREAN.getLocale());
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService, referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)), DestinationGalleryTestSupport.empty());
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(KOREA_ROOT_ID));
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void domesticDetailBuildsADistrictFilteredListLink() {
        givenRegionTree(
                region(101L, "종로구", 4, 10L),
                region(10L, "서울", 3, KOREA_ROOT_ID),
                region(KOREA_ROOT_ID, "대한민국", 1, null));

        Model model = renderDetail(101L);

        assertThat(model.getAttribute("type")).isEqualTo("domestic");
        assertThat(model.getAttribute("regionId")).isEqualTo(101L);
        assertThat(model.getAttribute("regionName")).isEqualTo("종로구");
        // 템플릿이 만드는 링크: 종로구가 선택된 국내 목록
        assertThat(listLink(model)).isEqualTo("/destinations?type=domestic&region=101");
    }

    @Test
    void heroBreadcrumbPointsAtTheParentRegionList() {
        givenRegionTree(
                region(101L, "종로구", 4, 10L),
                region(10L, "서울", 3, KOREA_ROOT_ID),
                region(KOREA_ROOT_ID, "대한민국", 1, null));

        Model model = renderDetail(101L);

        // 상단 breadcrumb "서울" → 서울이 선택된 국내 목록 (하위 지역 UI 노출)
        assertThat(model.getAttribute("regionPath")).isEqualTo("서울");
        assertThat(model.getAttribute("regionPathId")).isEqualTo(10L);
        assertThat("/destinations?type=" + model.getAttribute("type")
                + "&region=" + model.getAttribute("regionPathId"))
                .isEqualTo("/destinations?type=domestic&region=10");
    }

    @Test
    void englishDetailUsesLocalizedRegionAndCategoryDisplayNames() {
        LocaleContextHolder.setLocale(SupportedLanguage.ENGLISH.getLocale());
        givenRegionTree(
                region(101L, "종로구", 4, 10L),
                region(10L, "서울", 3, KOREA_ROOT_ID),
                region(KOREA_ROOT_ID, "대한민국", 1, null));
        when(referenceNameLocalizationService.localizeCountryCategories(
                any(), eq(SupportedLanguage.ENGLISH)))
                .thenReturn(Map.of(10L, "Seoul", 101L, "Jongno-gu"));
        when(referenceNameLocalizationService.localizeCategories(
                eq(List.of(5L)), eq(SupportedLanguage.ENGLISH)))
                .thenReturn(Map.of(5L, "랜드마크"));

        Model model = renderDetail(101L);

        assertThat(model.getAttribute("regionPath")).isEqualTo("Seoul");
        assertThat(model.getAttribute("regionName")).isEqualTo("Jongno-gu");
        assertThat(model.getAttribute("categoryName")).isEqualTo("랜드마크");
    }

    @Test
    void detailShowsOnlyTheMainCategoryAmongTheRegisteredOnes() {
        givenRegionTree(region(101L, "종로구", 4, KOREA_ROOT_ID), region(KOREA_ROOT_ID, "대한민국", 1, null));
        when(referenceNameLocalizationService.localizeCategories(any(), eq(SupportedLanguage.KOREAN)))
                .thenReturn(Map.of(3L, "랜드마크", 5L, "고궁", 8L, "사진명소"));

        // 경복궁: 등록 카테고리 랜드마크·고궁·사진명소, 대표 고궁 → 배지는 고궁 목록으로 간다.
        Model palace = renderDetail(101L, List.of(3L, 5L, 8L), 5L);
        assertThat(palace.getAttribute("categoryName")).isEqualTo("고궁");
        assertThat(palace.getAttribute("categoryId")).isEqualTo(5L);
        assertThat(palace.getAttribute("type")).isEqualTo("domestic");
        // 대표가 없는 기존 데이터는 가장 작은 ID(MIN(category_id))를 보여 주고 그 목록으로 간다.
        Model legacy = renderDetail(101L, List.of(8L, 3L, 5L), null);
        assertThat(legacy.getAttribute("categoryName")).isEqualTo("랜드마크");
        assertThat(legacy.getAttribute("categoryId")).isEqualTo(3L);
    }

    @Test
    void heroRegionLinksUseTheSameParameterSyntaxAsTheRecommendationLink() throws java.io.IOException {
        String hero = heroBlock();

        // 서울(regionPathId) / 종로구(regionId) 두 링크 모두 목록 필터 파라미터를 그대로 사용
        assertThat(hero)
                .contains("@{/destinations(type=${type},region=${regionPathId})}")
                .contains("@{/destinations(type=${type},region=${regionId})}")
                // 문자열 연결은 &reg; 엔티티로 깨지므로 남아 있으면 안 된다
                .doesNotContain("'&region='")
                .doesNotContain("href=\"#\"");
        // 대표 카테고리는 그 카테고리가 선택된 목록으로 간다. 하위 지역(region)으로 좁히지 않고 국내/해외 전체다.
        assertThat(hero)
                .contains("@{/destinations(type=${type},category=${categoryId})}")
                .doesNotContain("category=${categoryId},region")
                .doesNotContain("region=${regionId},category");
    }

    @Test
    void overseasDetailBuildsACityFilteredListLink() {
        givenRegionTree(
                region(201L, "도쿄", 3, 20L),
                region(20L, "일본", 2, ASIA_ROOT_ID),
                region(ASIA_ROOT_ID, "아시아", 1, null));

        Model model = renderDetail(201L);

        assertThat(model.getAttribute("type")).isEqualTo("overseas");
        assertThat(listLink(model)).isEqualTo("/destinations?type=overseas&region=201");
    }

    @Test
    void recommendationMoreLinkReusesTheListFilterParameters() throws java.io.IOException {
        String detail = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/destination/detail.html"),
                java.nio.charset.StandardCharsets.UTF_8);
        String moreLink = detail.substring(detail.indexOf("similar-destinations-more"));

        // 지역명 문자열 검색이 아니라 목록이 쓰는 type/region ID 파라미터를 그대로 사용한다.
        // 문자열 연결(&region=)은 &reg; 엔티티로 깨지므로 파라미터 문법이어야 한다.
        assertThat(moreLink)
                .contains("@{/destinations(type=${type},region=${regionId})}")
                .doesNotContain("'&region='");
    }

    @Test
    void destinationDetailAddsPlaceJsonLdWithCoordinates() throws Exception {
        givenRegionTree(
                region(101L, "종로구", 4, 10L),
                region(10L, "서울", 3, KOREA_ROOT_ID),
                region(KOREA_ROOT_ID, "대한민국", 1, null));

        Model model = renderDetail(101L);

        JsonNode place = new ObjectMapper().readTree(
                        (String) model.getAttribute("seoJsonLd"))
                .path("@graph").get(0);
        assertThat(place.path("@type").asText()).isEqualTo("Place");
        assertThat(place.path("name").asText()).isEqualTo("여행지");
        assertThat(place.path("geo").path("latitude").decimalValue())
                .isEqualByComparingTo("37.579617");
        assertThat(place.path("geo").path("longitude").decimalValue())
                .isEqualByComparingTo("126.977041");
        assertThat(place.path("url").asText())
                .isEqualTo("https://travel.example/destinations/7");
        assertThat(place.has("address")).isFalse();
    }

    private String heroBlock() throws java.io.IOException {
        String detail = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/templates/destination/detail.html"),
                java.nio.charset.StandardCharsets.UTF_8);
        int start = detail.indexOf("class=\"destination-hero\"");
        int end = detail.indexOf("</section>", start);
        assertThat(start).isGreaterThan(0);
        assertThat(end).isGreaterThan(start);
        return detail.substring(start, end);
    }

    /** detail.html 의 지역 링크 표현식과 같은 방식으로 URL 을 만든다. */
    private String listLink(Model model) {
        return "/destinations?type=" + model.getAttribute("type")
                + "&region=" + model.getAttribute("regionId");
    }

    private Model renderDetail(Long regionId) {
        return renderDetail(regionId, List.of(5L), null);
    }

    private Model renderDetail(Long regionId, List<Long> categoryIds, Long mainCategoryId) {
        Destination destination = new Destination();
        destination.setId(7L);
        destination.setName("여행지");
        destination.setDescription("<p>도심 속 역사 여행지입니다.</p>");
        destination.setThumbnailPath("/uploads/destinations/palace.webp");
        destination.setLatitude(new BigDecimal("37.579617"));
        destination.setLongitude(new BigDecimal("126.977041"));
        destination.setRegionId(regionId);

        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);
        dto.setCategoryIds(categoryIds);
        dto.setMainCategoryId(mainCategoryId);

        when(destinationService.getPublicDestinationDetailWithInfo(eq(7L), any(SupportedLanguage.class)))
                .thenReturn(dto);
        when(destinationService.getSimilarDestinations(7L, 4)).thenReturn(List.of());
        when(destinationService.convertToDtoWithBookmark(List.of(), null)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        model.addAttribute("seoSiteBaseUrl", "https://travel.example");
        controller.destinationDetail(7L, null, new org.springframework.mock.web.MockHttpSession(), model);
        return model;
    }

    private void givenRegionTree(CountryCategory... regions) {
        for (CountryCategory region : regions) {
            when(countryCategoryService.getById(region.getId())).thenReturn(region);
        }
    }

    private CountryCategory region(Long id, String name, int depth, Long parentId) {
        CountryCategory region = new CountryCategory();
        region.setId(id);
        region.setRegionName(name);
        region.setDepth(depth);
        region.setParentId(parentId);
        return region;
    }
}
