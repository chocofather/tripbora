package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseLocationLabel;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.CountryCategoryTranslation;
import com.tripbora.repository.category.CategoryMapper;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.LocalizedReferenceNameResolver;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseLocationLabelsTest {
    private final CountryCategoryService categories = mock(CountryCategoryService.class);
    private final CountryCategoryMapper mapper = mock(CountryCategoryMapper.class);
    private final ReferenceNameLocalizationService localization = new ReferenceNameLocalizationService(
            mapper, mock(CategoryMapper.class), mock(InfoCategoryMapper.class), new LocalizedReferenceNameResolver());

    @ParameterizedTest
    @CsvSource({"서울,강서구", "서울,종로구", "부산,해운대구", "제주,제주시", "충청남도,공주시"})
    void domesticUsesActualProvinceAndDistrictWithoutInventingAbbreviations(String province, String district) {
        var stop = linked(3L, district);
        when(categories.getRegionPath(3L)).thenReturn(List.of(
                node(1L, "KR", "대한민국"), node(2L, "KR-11", province), node(3L, "KR-11-1", district)));
        String mapUrl = stop.getGoogleMapsUrl();
        apply(List.of(stop), SupportedLanguage.KOREAN);
        assertThat(stop.getLocationLabel()).isEqualTo(province + " " + district);
        assertThat(stop.getRegionName()).isEqualTo(district);
        assertThat(stop.getGoogleMapsUrl()).isEqualTo(mapUrl);
    }

    @ParameterizedTest
    @CsvSource({"FR,프랑스,파리", "JP,일본,오사카", "JP,일본,도쿄", "US,미국,뉴욕", "IT,이탈리아,로마"})
    void overseasUsesCountryAndCityFromDestinationPath(String code, String country, String city) {
        var stop = linked(3L, city);
        when(categories.getRegionPath(3L)).thenReturn(List.of(
                node(8L, "EU", "대륙"), node(1L, code, country), node(3L, code + "-CITY", city)));
        apply(List.of(stop), SupportedLanguage.KOREAN);
        assertThat(stop.getLocationLabel()).isEqualTo(country + " " + city);
    }

    @Test
    void translationsUseExistingFallbackAndRepeatedRegionsAreReadOnce() {
        var first = linked(3L, "파리");
        var second = linked(3L, "파리");
        when(categories.getRegionPath(3L)).thenReturn(List.of(node(1L, "FR", "프랑스"), node(3L, "FR-PAR", "파리")));
        when(mapper.findTranslationsByCountryCategoryIds(any())).thenReturn(List.of(
                translation(1L, "en", "France"), translation(3L, "en", "Paris")));
        apply(List.of(first, second), SupportedLanguage.ENGLISH);
        assertThat(first.getLocationLabel()).isEqualTo("France Paris");
        assertThat(second.getLocationLabel()).isEqualTo("France Paris");
        verify(categories, times(1)).getRegionPath(3L);
        verify(mapper, times(1)).findTranslationsByCountryCategoryIds(any());
    }

    @Test
    void countryOnlyAndBrokenLegacyPathsHaveSafeFallbacks() {
        var country = linked(1L, "프랑스");
        when(categories.getRegionPath(1L)).thenReturn(List.of(node(1L, "FR", "프랑스")));
        var city = linked(3L, "파리");
        when(categories.getRegionPath(3L)).thenReturn(List.of());
        apply(List.of(country, city), SupportedLanguage.KOREAN);
        assertThat(country.getLocationLabel()).isEqualTo("프랑스");
        assertThat(city.getLocationLabel()).isEqualTo("파리");
    }

    @Test
    void directRetainsAddressWithoutParsingAndUsesCountryIfAddressMissing() {
        var address = new CourseStopDto();
        address.setCountryId(1L); address.setCountryName("일본"); address.setAddress("Osaka 1-2-3");
        var countryOnly = new CourseStopDto(); countryOnly.setCountryId(1L); countryOnly.setCountryName("일본");
        var empty = new CourseStopDto();
        apply(List.of(address, countryOnly, empty), SupportedLanguage.KOREAN);
        assertThat(address.getLocationLabel()).isEqualTo("Osaka 1-2-3");
        assertThat(countryOnly.getLocationLabel()).isEqualTo("일본");
        assertThat(empty.getLocationLabel()).isNull();
        verify(categories, never()).getRegionPath(any());
    }

    @Test
    void directStructuredRegionUsesTheSameHierarchyAndCountryIdCanResolveMissingName() {
        var structured = linked(3L, "종로구"); structured.setDestinationId(null);
        when(categories.getRegionPath(3L)).thenReturn(List.of(
                node(1L, "KR", "대한민국"), node(2L, "KR-11", "서울"), node(3L, "KR-11-1", "종로구")));
        var countryOnly = new CourseStopDto(); countryOnly.setCountryId(10L);
        when(categories.getById(10L)).thenReturn(node(10L, "JP", "일본"));
        apply(List.of(structured, countryOnly), SupportedLanguage.KOREAN);
        assertThat(structured.getLocationLabel()).isEqualTo("서울 종로구");
        assertThat(countryOnly.getLocationLabel()).isEqualTo("일본");
    }

    @Test
    void repeatedAndAlreadyPrefixedNamesAreNotPrintedTwice() {
        assertThat(CourseLocationLabel.join("서울", "서울 강서구")).isEqualTo("서울 강서구");
        assertThat(CourseLocationLabel.join("제주", "제주", "제주시")).isEqualTo("제주 제주시");
        assertThat(CourseLocationLabel.join("일본", "일본", "도쿄")).isEqualTo("일본 도쿄");
        assertThat(CourseLocationLabel.join("서울", " ", null, "종로구")).isEqualTo("서울 종로구");
    }

    private void apply(List<CourseStopDto> stops, SupportedLanguage language) {
        CourseLocationLabels.localize(stops, language, categories, localization);
    }

    private CourseStopDto linked(Long regionId, String region) {
        var stop = new CourseStopDto(); stop.setDestinationId(77L); stop.setName("장소");
        stop.setRegionId(regionId); stop.setRegionName(region); return stop;
    }

    private CountryCategory node(Long id, String code, String name) {
        var node = new CountryCategory(); node.setId(id); node.setCode(code); node.setRegionName(name); return node;
    }

    private CountryCategoryTranslation translation(Long id, String language, String name) {
        var translation = new CountryCategoryTranslation(); translation.setId(id); translation.setCountryCategoryId(id);
        translation.setLanguageCode(language); translation.setName(name); return translation;
    }
}
