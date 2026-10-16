package com.tripbora.service.search;

import com.tripbora.dto.DestinationSearchResultDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.CountryCategoryTranslation;
import com.tripbora.repository.category.CategoryMapper;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.search.DestinationSearchMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.LocalizedReferenceNameResolver;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DestinationSearchLocationLabelTest {
    @Test
    void searchUsesTheSameHierarchyAndCurrentLocaleWithoutChangingRawRegion() {
        var searchMapper = mock(DestinationSearchMapper.class);
        var categories = mock(CountryCategoryService.class);
        var namesMapper = mock(CountryCategoryMapper.class);
        var localization = new ReferenceNameLocalizationService(namesMapper, mock(CategoryMapper.class),
                mock(InfoCategoryMapper.class), new LocalizedReferenceNameResolver());
        var service = new DestinationSearchServiceImpl(searchMapper, mock(DestinationImageService.class), categories, localization);
        var result = new DestinationSearchResultDto(); result.setId(9L); result.setRegionId(3L);
        result.setRegionName("파리"); result.setCountryId(1L); result.setCountryName("프랑스");
        when(searchMapper.searchDestinations("에펠", 20, 1L)).thenReturn(List.of(result));
        when(categories.getRegionPath(3L)).thenReturn(List.of(node(1L, "FR", "프랑스"), node(3L, "FR-PAR", "파리")));
        when(namesMapper.findTranslationsByCountryCategoryIds(any())).thenReturn(List.of(
                translation(1L, "France"), translation(3L, "Paris")));
        Locale previous = LocaleContextHolder.getLocale();
        try {
            LocaleContextHolder.setLocale(Locale.ENGLISH);
            assertThat(service.search("에펠", 20, 1L).get(0).getLocationLabel()).isEqualTo("France Paris");
            assertThat(result.getRegionName()).isEqualTo("파리");
            assertThat(result.getCountryName()).isEqualTo("프랑스");
        } finally {
            LocaleContextHolder.setLocale(previous);
        }
    }

    private CountryCategory node(Long id, String code, String name) {
        var node = new CountryCategory(); node.setId(id); node.setCode(code); node.setRegionName(name); return node;
    }

    private CountryCategoryTranslation translation(Long id, String name) {
        var translation = new CountryCategoryTranslation(); translation.setId(id);
        translation.setCountryCategoryId(id); translation.setLanguageCode("en"); translation.setName(name); return translation;
    }
}
