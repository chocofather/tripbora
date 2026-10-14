package com.tripbora.controller.destination;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.DestinationDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.Destination;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DestinationListLocalizationTest {

    @Mock private DestinationService destinationService;
    @Mock private DestinationImageService destinationImageService;
    @Mock private CountryCategoryService countryCategoryService;
    @Mock private DestinationCommentService destinationCommentService;
    @Mock private ReferenceNameLocalizationService referenceNameLocalizationService;
    @Mock private HttpServletRequest request;

    private DestinationController controller;
    private Destination destination;
    private DestinationDto localizedCard;
    private CountryCategory seoul;
    private CountryCategory jongno;

    @BeforeEach
    void setUp() {
        LocaleContextHolder.setLocale(SupportedLanguage.ENGLISH.getLocale());
        controller = new DestinationController(destinationService, destinationImageService,
                countryCategoryService, destinationCommentService,
                referenceNameLocalizationService,
                new com.tripbora.service.file.DestinationCardThumbnailService("build/tmp/no-uploads",
                        org.mockito.Mockito.mock(DestinationImageService.class)));
        seoul = region(38L, "서울", 3, 7L);
        jongno = region(235L, "종로구", 4, 38L);
        destination = new Destination();
        destination.setId(15L);
        destination.setRegionId(235L);
        destination.setRegionName("종로구");
        destination.setName("경복궁");
        destination.setShortDescription("한국어 소개");
        localizedCard = new DestinationDto();
        localizedCard.setId(15L);
        localizedCard.setName("Gyeongbokgung Palace");
        localizedCard.setShortDescription("English summary");
        localizedCard.setRegionName("Jongno-gu");

        when(countryCategoryService.getDomesticRootIds()).thenReturn(List.of(7L));
        when(countryCategoryService.getAllRegionIdsUnder(7L)).thenReturn(List.of(7L, 38L, 235L));
        when(destinationService.getDestinationsByRegionIdsPaged(
                List.of(7L, 38L, 235L), List.of(), 0, 12, "default"))
                .thenReturn(List.of(destination));
        when(destinationService.countDestinationsByRegionIds(List.of(7L, 38L, 235L), List.of()))
                .thenReturn(1);
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                anyMap(), eq(SupportedLanguage.ENGLISH)))
                .thenReturn(Map.of(38L, "Seoul", 235L, "Jongno-gu"));
        when(destinationService.convertToLocalizedDtoWithBookmark(
                eq(List.of(destination)), eq(null), eq(SupportedLanguage.ENGLISH), anyMap()))
                .thenReturn(List.of(localizedCard));
    }

    @AfterEach
    void clearLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void initialListLocalizesRegionSelectorAndCurrentPageCards() {
        when(countryCategoryService.getSubregions(7L, 3)).thenReturn(List.of(seoul));

        Model model = new ExtendedModelMap();
        String view = controller.destinationList(
                "domestic", null, 1, 12, "default", null, null, request, model);

        assertThat(view).isEqualTo("destination/list");
        assertThat(model.getAttribute("regionDisplayNames"))
                .isEqualTo(Map.of(38L, "Seoul", 235L, "Jongno-gu"));
        assertThat(model.getAttribute("destinations")).isEqualTo(List.of(localizedCard));
        verify(destinationService).convertToLocalizedDtoWithBookmark(
                eq(List.of(destination)), eq(null), eq(SupportedLanguage.ENGLISH), anyMap());
    }

    @Test
    void asynchronousListFragmentUsesTheSameCookieLocaleForSubregionsAndHeading() {
        when(countryCategoryService.getById(38L)).thenReturn(seoul);
        when(countryCategoryService.getSubregions(38L, 4)).thenReturn(List.of(jongno));
        when(countryCategoryService.getAllRegionIdsUnder(38L)).thenReturn(List.of(38L, 235L));
        when(destinationService.getDestinationsByRegionIdsPaged(
                List.of(38L, 235L), List.of(), 0, 12, "default"))
                .thenReturn(List.of(destination));
        when(destinationService.countDestinationsByRegionIds(List.of(38L, 235L), List.of())).thenReturn(1);

        Map<Long, String> names = new LinkedHashMap<>();
        names.put(38L, "Seoul");
        names.put(235L, "Jongno-gu");
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                anyMap(), eq(SupportedLanguage.ENGLISH))).thenReturn(names);

        Model model = new ExtendedModelMap();
        String view = controller.destinationListFragment(
                "domestic", 38L, 1, 12, "default", null, null, model);

        assertThat(view).isEqualTo("destination/fragment :: destinationList");
        assertThat(model.getAttribute("selectedCityName")).isEqualTo("Seoul");
        assertThat(model.getAttribute("regionDisplayNames")).isEqualTo(names);
        assertThat(model.getAttribute("destinations")).isEqualTo(List.of(localizedCard));
    }

    @Test
    void titleUsesLocalizedSelectedRegionAcrossFullAndListAjaxResponses() {
        when(countryCategoryService.getById(38L)).thenReturn(seoul);
        when(countryCategoryService.getById(235L)).thenReturn(jongno);
        when(countryCategoryService.getSubregions(7L, 3)).thenReturn(List.of(seoul));
        when(countryCategoryService.getSubregions(38L, 4)).thenReturn(List.of(jongno));
        when(countryCategoryService.getAllRegionIdsUnder(235L)).thenReturn(List.of(235L));
        when(destinationService.getDestinationsByRegionIdsPaged(
                List.of(235L), List.of(), 0, 12, "default")).thenReturn(List.of(destination));
        when(destinationService.countDestinationsByRegionIds(List.of(235L), List.of())).thenReturn(1);
        Model full = new ExtendedModelMap();
        Model ajax = new ExtendedModelMap();
        Model region = new ExtendedModelMap();
        controller.destinationList("domestic", 235L, 1, 12, "default", null, null, request, full);
        controller.destinationListFragment("domestic", 235L, 1, 12, "default", null, null, ajax);
        controller.regionFragment("domestic", 235L, 1, 12, "default", null, null, region);
        for (Model model : List.of(full, ajax, region)) {
            assertThat(model.getAttribute("destinationTitleTrail")).isNull();
            assertThat(model.getAttribute("selectedCityName")).isEqualTo("Jongno-gu");
        }
    }

    /** 국내 카드는 부모 계층의 광역지역을 요청 언어 이름으로 앞에 붙인다(종로구 → Seoul Jongno-gu). */
    @Test
    void domesticCardsShowTheProvinceBeforeTheLowestRegion() {
        when(countryCategoryService.getById(235L)).thenReturn(jongno);
        when(countryCategoryService.getById(38L)).thenReturn(seoul);
        when(countryCategoryService.getSubregions(7L, 3)).thenReturn(List.of(seoul));

        Model model = new ExtendedModelMap();
        controller.destinationList("domestic", null, 1, 12, "default", null, null, request, model);

        assertThat(localizedCard.getRegionName()).isEqualTo("Seoul Jongno-gu");
    }

    /**
     * 해외 카드는 대륙 바로 아래 국가를 요청 언어 이름으로 앞에 붙인다(후쿠오카 → Japan Fukuoka).
     * 국가 자체에 등록된 해외 카드, 국가와 도시 이름이 같은 카드, 광역지역 자체에 등록된 국내 카드는 이름을 한 번만 둔다.
     */
    @Test
    void overseasCardsShowTheCountryOnceBeforeTheLowestRegion() {
        CountryCategory asia = region(2L, "아시아", 1, null);
        CountryCategory japan = region(50L, "일본", 2, 2L);
        CountryCategory fukuoka = region(51L, "후쿠오카", 3, 50L);
        CountryCategory singapore = region(60L, "싱가포르", 2, 2L);
        CountryCategory singaporeCity = region(61L, "싱가포르", 3, 60L);
        for (CountryCategory category : List.of(asia, japan, fukuoka, singapore, singaporeCity, seoul)) {
            when(countryCategoryService.getById(category.getId())).thenReturn(category);
        }
        when(countryCategoryService.getOverseasRootIds()).thenReturn(List.of(2L));
        when(countryCategoryService.getSubregions(7L, 3)).thenReturn(List.of(seoul));
        destination.setRegionId(51L);
        Destination countryLevel = destinationIn(17L, 60L);
        Destination sameNameCity = destinationIn(18L, 61L);
        Destination provinceLevel = destinationIn(16L, 38L);
        localizedCard.setRegionName("Fukuoka");
        DestinationDto countryCard = card(17L, "Singapore");
        DestinationDto sameNameCityCard = card(18L, "Singapore");
        DestinationDto provinceCard = card(16L, "Seoul");
        List<Destination> destinations = List.of(destination, countryLevel, sameNameCity, provinceLevel);
        when(destinationService.getDestinationsByRegionIdsPaged(
                List.of(7L, 38L, 235L), List.of(), 0, 12, "default"))
                .thenReturn(destinations);
        when(destinationService.convertToLocalizedDtoWithBookmark(
                eq(destinations), eq(null), eq(SupportedLanguage.ENGLISH), anyMap()))
                .thenReturn(List.of(localizedCard, countryCard, sameNameCityCard, provinceCard));
        when(referenceNameLocalizationService.localizeCountryCategoryNames(
                anyMap(), eq(SupportedLanguage.ENGLISH)))
                .thenReturn(Map.of(38L, "Seoul", 50L, "Japan", 51L, "Fukuoka",
                        60L, "Singapore", 61L, "Singapore"));

        Model model = new ExtendedModelMap();
        controller.destinationList("domestic", null, 1, 12, "default", null, null, request, model);

        assertThat(localizedCard.getRegionName()).isEqualTo("Japan Fukuoka");
        assertThat(countryCard.getRegionName()).isEqualTo("Singapore");
        assertThat(sameNameCityCard.getRegionName()).isEqualTo("Singapore");
        assertThat(provinceCard.getRegionName()).isEqualTo("Seoul");
    }

    /** 지역 이름이 비어 있는 카드는 접두 없이 그대로 둔다(예외 없음). */
    @Test
    void cardsWithoutRegionNameAreLeftAlone() {
        CountryCategory asia = region(2L, "아시아", 1, null);
        CountryCategory japan = region(50L, "일본", 2, 2L);
        CountryCategory fukuoka = region(51L, "후쿠오카", 3, 50L);
        for (CountryCategory category : List.of(asia, japan, fukuoka)) {
            when(countryCategoryService.getById(category.getId())).thenReturn(category);
        }
        when(countryCategoryService.getOverseasRootIds()).thenReturn(List.of(2L));
        destination.setRegionId(51L);
        localizedCard.setRegionName(null);

        Model model = new ExtendedModelMap();
        controller.destinationList("domestic", null, 1, 12, "default", null, null, request, model);

        assertThat(localizedCard.getRegionName()).isNull();
    }

    private Destination destinationIn(Long id, Long regionId) {
        Destination destination = new Destination();
        destination.setId(id);
        destination.setRegionId(regionId);
        return destination;
    }

    private DestinationDto card(Long id, String regionName) {
        DestinationDto card = new DestinationDto();
        card.setId(id);
        card.setRegionName(regionName);
        return card;
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
