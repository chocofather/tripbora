package com.tripbora.controller.course;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseCardDto;
import com.tripbora.dto.CourseCatalogPage;
import com.tripbora.dto.CourseThemeDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.service.course.CourseCatalogService;
import com.tripbora.service.course.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseCatalogControllerTest {
    @Test
    void exposesNormalizedBrowseStateAndLocalizedExistingFilterOptions() {
        var catalog = mock(CourseCatalogService.class);
        var courses = mock(CourseService.class);
        var controller = new CourseCatalogController(catalog, courses);
        var card = new CourseCardDto(); card.setId(7L);
        var countries = List.of(new CountryCategory());
        var themes = List.of(new CourseThemeDto());
        when(catalog.browse("invalid", 2L, 3L, "invalid", 999)).thenReturn(
                new CourseCatalogPage(List.of(card), 1, 1, 1, "OFFICIAL", 2L, 3L, "latest"));
        when(courses.getCourseCountries(SupportedLanguage.ENGLISH)).thenReturn(countries);
        when(courses.getCourseThemes()).thenReturn(themes);
        var model = new ExtendedModelMap();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        try {
            assertThat(controller.list("invalid", 2L, 3L, "invalid", 999, model)).isEqualTo("course/list");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
        assertThat(model.get("courses")).isEqualTo(List.of(card));
        assertThat(model.get("courseCountries")).isSameAs(countries);
        assertThat(model.get("courseThemes")).isSameAs(themes);
        assertThat(model.get("kind")).isEqualTo("OFFICIAL");
        assertThat(model.get("catalogKind")).isNull();
        assertThat(model.get("sort")).isEqualTo("latest");
        assertThat(model.get("countryId")).isEqualTo(2L);
        assertThat(model.get("themeId")).isEqualTo(3L);
        assertThat(model.get("currentPage")).isEqualTo(1);
        assertThat(model.get("totalPages")).isEqualTo(1);
        assertThat(model.get("totalCount")).isEqualTo(1);
    }
}
