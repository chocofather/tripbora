package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDetailDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.comment.DestinationCommentService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 해외 상세 지도는 Maps Embed API iframe 으로 표시한다. 키는 환경변수로만 주입한다. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OverseasMapEmbedTest {

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

    /** 테스트용 더미 Place ID */
    private static final String PLACE_ID = "ChIJTestPlaceIdForUnitTest";

    private DestinationController controller;

    @BeforeEach
    void setUp() {
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService, referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)), DestinationGalleryTestSupport.empty());
        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(1L));
    }

    @Test
    void placeIdSelectsTheExactPlaceInPlaceModeSoAMarkerShowsOnLoad() {
        givenApiKey("test-key");

        Model model = renderDetail(overseasCityRegion("후쿠오카"), "후쿠오카 타워", PLACE_ID,
                new BigDecimal("33.5932"), new BigDecimal("130.3514"));

        // 텍스트 검색이 아니라 place_id 로 장소를 확정한다
        assertThat(model.getAttribute("overseasMapEmbedUrl"))
                .isEqualTo("https://www.google.com/maps/embed/v1/place"
                        + "?key=test-key"
                        + "&q=" + URLEncoder.encode("place_id:" + PLACE_ID, StandardCharsets.UTF_8)
                        + "&center=33.5932%2C130.3514&zoom=15");
    }

    @Test
    void withoutAPlaceIdItFallsBackToCoordinateViewModeAndNeverGuessesAPlace() {
        givenApiKey("test-key");

        Model model = renderDetail(overseasCityRegion("후쿠오카"), "후쿠오카 타워", "  ",
                new BigDecimal("35.6586"), new BigDecimal("139.7454"));

        assertThat(model.getAttribute("overseasMapEmbedUrl"))
                .isEqualTo("https://www.google.com/maps/embed/v1/view"
                        + "?key=test-key&center=35.6586%2C139.7454&zoom=15");
        // 여행지명 텍스트 검색은 더 이상 쓰지 않는다
        assertThat((String) model.getAttribute("overseasMapEmbedUrl")).doesNotContain("%ED%9B%84");
    }

    @Test
    void bigViewLinkKeepsCoordinatesAndPointsAtThePlaceWhenAPlaceIdExists() {
        givenApiKey("");

        // Place ID 없음 → 기존 좌표 링크 그대로
        assertThat(renderDetail(overseasRegion(), new BigDecimal("35.6586"), new BigDecimal("139.7454"))
                .getAttribute("overseasMapLinkUrl"))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=35.6586%2C139.7454");

        // Place ID 있음 → 같은 장소로 열리도록 query_place_id 추가 (query 는 필수라 유지)
        Model model = renderDetail(overseasRegion(), "여행지", PLACE_ID,
                new BigDecimal("35.6586"), new BigDecimal("139.7454"));
        assertThat(model.getAttribute("overseasMapLinkUrl"))
                .isEqualTo("https://www.google.com/maps/search/?api=1&query=35.6586%2C139.7454"
                        + "&query_place_id=" + PLACE_ID);
    }

    @Test
    void withoutAnApiKeyOrAnyLocationDataTheMapAreaStaysEmpty() {
        // 환경변수 미설정 → iframe 자체를 만들지 않는다
        givenApiKey("");
        assertThat(renderDetail(overseasRegion(), new BigDecimal("35.6"), new BigDecimal("139.7"))
                .getAttribute("overseasMapEmbedUrl")).isNull();

        // Place ID 도 좌표도 없으면 만들 수 있는 URL 이 없다 → 지도 미표시
        givenApiKey("test-key");
        assertThat(renderDetail(overseasRegion(), "여행지", null, null, null)
                .getAttribute("overseasMapEmbedUrl")).isNull();
    }

    @Test
    void placeIdStillMarksTheSpotWhenCoordinatesAreMissing() {
        givenApiKey("test-key");

        Model model = renderDetail(overseasCityRegion("후쿠오카"), "후쿠오카 타워", PLACE_ID, null, null);

        // 좌표가 없어도 place_id 로 마커는 표시된다 (center/zoom 만 생략)
        assertThat(model.getAttribute("overseasMapEmbedUrl"))
                .isEqualTo("https://www.google.com/maps/embed/v1/place"
                        + "?key=test-key"
                        + "&q=" + URLEncoder.encode("place_id:" + PLACE_ID, StandardCharsets.UTF_8));
        // 크게 보기 링크는 좌표 기반이라 이때는 없다
        assertThat(model.getAttribute("overseasMapLinkUrl")).isNull();
    }

    @Test
    void domesticDestinationKeepsKakaoAndGetsNoEmbedUrl() {
        givenApiKey("test-key");

        CountryCategory jongno = region(101L, "종로구", 4, 10L, "KR-11-110");
        when(countryCategoryService.getById(10L)).thenReturn(region(10L, "서울", 3, 1L, "KR-11"));
        when(countryCategoryService.getById(1L)).thenReturn(region(1L, "대한민국", 1, null, "KR"));

        Model model = renderDetail(jongno, new BigDecimal("37.5"), new BigDecimal("126.9"));

        assertThat(model.getAttribute("countryCode")).isEqualTo("KR");
        assertThat(model.getAttribute("overseasMapEmbedUrl")).isNull();
        assertThat(model.getAttribute("overseasMapLinkUrl")).isNull();
    }

    @Test
    void mapMarkupSplitsKakaoAndEmbedAndNeverHardcodesAKey() throws IOException {
        String detail = readFile("src/main/resources/templates/destination/detail.html");
        String mapSection = detail.substring(detail.indexOf("<section class=\"map\">"),
                detail.indexOf("</section>", detail.indexOf("<section class=\"map\">")));

        assertThat(mapSection)
                // 국내는 기존 Kakao 컨테이너 유지
                .contains("th:if=\"${countryCode == 'KR'}\"")
                .contains("id=\"map\"")
                // 해외는 Embed iframe
                .contains("th:src=\"${overseasMapEmbedUrl}\"")
                .contains("allowfullscreen")
                .contains("referrerpolicy=\"no-referrer-when-downgrade\"")
                // URL 이 없으면 iframe 을 만들지 않는다
                .contains("overseasMapEmbedUrl == null")
                // 지도 아래 새 탭 링크
                .contains("th:href=\"${overseasMapLinkUrl}\"")
                .contains("#{destination.detail.map.openGoogleMaps}")
                .contains("target=\"_blank\" rel=\"noopener\"");

        // 키 문자열은 소스 어디에도 없어야 한다
        assertThat(detail).doesNotContain("AIza");
        assertThat(readFile("src/main/resources/static/js/map-init.js"))
                .doesNotContain("AIza")
                .doesNotContain("maps.googleapis.com")
                .doesNotContain("google.maps");
        assertThat(readFile("src/main/java/com/tripbora/controller/destination/"
                + "DestinationController.java"))
                .doesNotContain("AIza")
                .contains("${GOOGLE_MAPS_API_KEY:}");
    }

    private void givenApiKey(String key) {
        ReflectionTestUtils.setField(controller, "googleMapsApiKey", key);
    }

    private CountryCategory overseasRegion() {
        return overseasCityRegion("도쿄");
    }

    /** 해외 도시(depth 3) → 국가(depth 2) → 대륙(depth 1) 트리 */
    private CountryCategory overseasCityRegion(String cityName) {
        when(countryCategoryService.getById(20L)).thenReturn(region(20L, "일본", 2, 2L, "JP"));
        when(countryCategoryService.getById(2L)).thenReturn(region(2L, "아시아", 1, null, "AS"));
        return region(201L, cityName, 3, 20L, "JP-13");
    }

    private Model renderDetail(CountryCategory region, BigDecimal latitude, BigDecimal longitude) {
        return renderDetail(region, "여행지", null, latitude, longitude);
    }

    private Model renderDetail(CountryCategory region, String name, String googlePlaceId,
                               BigDecimal latitude, BigDecimal longitude) {
        when(countryCategoryService.getById(region.getId())).thenReturn(region);

        Destination destination = new Destination();
        destination.setId(7L);
        destination.setName(name);
        destination.setGooglePlaceId(googlePlaceId);
        destination.setRegionId(region.getId());
        destination.setLatitude(latitude);
        destination.setLongitude(longitude);

        DestinationDetailDto dto = new DestinationDetailDto();
        dto.setDestination(destination);

        when(destinationService.getPublicDestinationDetailWithInfo(eq(7L), any(SupportedLanguage.class)))
                .thenReturn(dto);
        when(destinationService.getSimilarDestinations(7L, 4)).thenReturn(List.of());
        when(destinationService.convertToDtoWithBookmark(List.of(), null)).thenReturn(List.of());

        Model model = new ExtendedModelMap();
        controller.destinationDetail(7L, null, new org.springframework.mock.web.MockHttpSession(), model);
        return model;
    }

    private CountryCategory region(Long id, String name, int depth, Long parentId, String code) {
        CountryCategory region = new CountryCategory();
        region.setId(id);
        region.setRegionName(name);
        region.setDepth(depth);
        region.setParentId(parentId);
        region.setCode(code);
        return region;
    }

    private String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(Path.of(path)), StandardCharsets.UTF_8);
    }
}
