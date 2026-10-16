package com.tripbora.service.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.CountryCategoryTranslation;
import com.tripbora.model.Course;
import com.tripbora.repository.category.CategoryMapper;
import com.tripbora.repository.category.CountryCategoryMapper;
import com.tripbora.repository.category.InfoCategoryMapper;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.destination.DestinationMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.LocalizedReferenceNameResolver;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CourseCountryReadTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseStructureService structure = mock(CourseStructureService.class);
    private final CountryCategoryMapper namesMapper = mock(CountryCategoryMapper.class);
    private final CountryCategoryService categories = mock(CountryCategoryService.class);
    private final CourseServiceImpl service = new CourseServiceImpl(mapper, new PostContentSanitizer(),
            new DestinationLocalizationService(mock(DestinationMapper.class)),
            new ReferenceNameLocalizationService(namesMapper, mock(CategoryMapper.class), mock(InfoCategoryMapper.class),
                    new LocalizedReferenceNameResolver()), new LocalContentLanguageDetector(),
            mock(DestinationImageService.class), structure, mock(CourseCoverStorageService.class), categories);

    @Test
    void detailAndEditReadOrderedMembershipInsteadOfLegacyRepresentativeCountry() {
        when(mapper.incrementViews(7L)).thenReturn(1);
        var detail = new CourseDetailDto(); detail.setTitle("코스"); detail.setContent("<p>소개</p>");
        when(mapper.findCourseDetail(7L, null)).thenReturn(detail);
        var legacy = new Course(); legacy.setId(7L); legacy.setUserId(5L); legacy.setCountryId(99L);
        legacy.setCountryName("legacy country"); legacy.setContent("<p>소개</p>");
        when(mapper.findActiveCourse(7L)).thenReturn(legacy);
        when(structure.findCountries(7L)).thenReturn(List.of(country(30L, "FR", "프랑스"), country(40L, "CH", "스위스")));
        when(namesMapper.findTranslationsByCountryCategoryIds(any())).thenReturn(List.of(
                translation(30L, "France"), translation(40L, "Switzerland")));
        assertThat(service.getCourseDetail(7L, null, SupportedLanguage.ENGLISH).getCountryLabel())
                .isEqualTo("France · Switzerland");
        assertThat(service.getCourseForEdit(7L, 5L, SupportedLanguage.ENGLISH).getCountryLabel())
                .isEqualTo("France · Switzerland");
    }

    @Test
    void localizedCountryOptionsDoNotMutateCachedMasterObjects() {
        var master = country(30L, "FR", "프랑스");
        when(categories.getCourseCountries()).thenReturn(List.of(master));
        when(namesMapper.findTranslationsByCountryCategoryIds(any())).thenReturn(List.of(translation(30L, "France")));
        var option = service.getCourseCountries(SupportedLanguage.ENGLISH).get(0);
        assertThat(option.getRegionName()).isEqualTo("France");
        assertThat(option.getId()).isEqualTo(30L);
        assertThat(option.getCode()).isEqualTo("FR");
        assertThat(master.getRegionName()).isEqualTo("프랑스");
        assertThat(option).isNotSameAs(master);
    }

    private CountryCategory country(Long id, String code, String name) {
        var country = new CountryCategory(); country.setId(id); country.setCode(code); country.setRegionName(name); return country;
    }

    private CountryCategoryTranslation translation(Long id, String name) {
        var row = new CountryCategoryTranslation(); row.setId(id); row.setCountryCategoryId(id);
        row.setLanguageCode("en"); row.setName(name); return row;
    }
}
