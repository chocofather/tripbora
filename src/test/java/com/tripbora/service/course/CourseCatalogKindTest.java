package com.tripbora.service.course;

import com.tripbora.repository.course.CourseCatalogMapper;
import com.tripbora.service.destination.DestinationImageService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CourseCatalogKindTest {
    private final CourseCatalogMapper mapper = mock(CourseCatalogMapper.class);
    private final CourseCatalogService catalog = new CourseCatalogService(mapper,
            mock(CourseThemeService.class), mock(DestinationImageService.class), new StaticMessageSource());

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"ALL", "all", "recommended", "OFFICIAL", "invalid", " "})
    void defaultAndLegacyAllOnlyQueryRecommendedCourses(String kind) {
        var page = catalog.browse(kind, null, null, "latest", 1);
        assertThat(page.getKind()).isEqualTo("OFFICIAL");
        verify(mapper).countCourses("OFFICIAL", null, null);
        verifyNoMoreInteractions(mapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"traveler", "TRAVELER", " Traveler "})
    void travelerSelectionOnlyQueriesTravelerCourses(String kind) {
        assertThat(catalog.browse(kind, 2L, 3L, "views", 1).getKind()).isEqualTo("TRAVELER");
        verify(mapper).countCourses("TRAVELER", 2L, 3L);
        verifyNoMoreInteractions(mapper);
    }
}
