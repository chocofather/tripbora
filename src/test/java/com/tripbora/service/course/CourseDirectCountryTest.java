package com.tripbora.service.course;

import com.tripbora.dto.*;
import com.tripbora.model.Course;
import com.tripbora.model.CountryCategory;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseDirectCountryTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CountryCategoryService countries = mock(CountryCategoryService.class);
    private final CourseStructureService structure = mock(CourseStructureService.class);

    @Test
    void directOnlyCreateValidatesItemCountryAndIgnoresLegacyCountryWithoutEmptyDestinationQueries() {
        CourseServiceImpl service = service();
        CountryCategory country = new CountryCategory(); country.setId(7L);
        when(countries.getCourseCountries()).thenReturn(List.of(country));
        when(mapper.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        CourseCreateRequest request = request();
        request.setCountryId(999L);
        assertThat(service.createCourse(request, 5L)).isEqualTo(10L);
        verify(mapper, never()).countExistingDestinations(anyList());
        verify(mapper, never()).findDestinationCountries(anyList());
        verify(structure).replaceSchedule(10L, 7L, request.getDays());
    }

    @Test
    void directOnlyRejectsRegionOrUnknownCountryBeforeWriting() {
        CourseServiceImpl service = service();
        when(countries.getCourseCountries()).thenReturn(List.of());
        assertThatThrownBy(() -> service.createCourse(request(), 5L)).isInstanceOf(ResponseStatusException.class);
        verify(mapper, never()).insertCourse(any());
        verifyNoInteractions(structure);
    }

    @Test
    void rejectsUnknownCountryOnAnyDirectItemEvenWhenFirstCountryIsValid() {
        CourseServiceImpl service = service();
        CountryCategory country = new CountryCategory(); country.setId(7L);
        when(countries.getCourseCountries()).thenReturn(List.of(country));
        CourseCreateRequest request = request();
        CourseItemForm invalid = CourseDirectItemTest.direct("FOOD", 2); invalid.setCountryId(8L);
        request.getDays().get(0).setItems(List.of(request.getDays().get(0).getItems().get(0), invalid));
        assertThatThrownBy(() -> service.createCourse(request, 5L)).isInstanceOf(ResponseStatusException.class);
        verify(mapper, never()).insertCourse(any());
        verifyNoInteractions(structure);
    }

    @Test
    void directOnlyUpdateStillEnforcesOwnershipBeforeCountryOrScheduleWrites() {
        CourseServiceImpl service = service();
        Course ownedByOther = new Course(); ownedByOther.setUserId(9L);
        when(mapper.findActiveCourseForUpdate(10L)).thenReturn(ownedByOther);
        CourseCreateRequest create = request();
        CourseUpdateRequest update = new CourseUpdateRequest(); update.setTitle(create.getTitle());
        update.setContent(create.getContent()); update.setCountryId(7L); update.setDays(create.getDays());
        assertThatThrownBy(() -> service.updateCourse(10L, 5L, update))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("403");
        verifyNoInteractions(countries, structure);
    }

    @Test
    void ownedDirectOnlyCourseCanReplaceScheduleWithoutDestinationLookups() {
        CourseServiceImpl service = service();
        Course current = new Course(); current.setId(10L); current.setUserId(5L);
        when(mapper.findActiveCourseForUpdate(10L)).thenReturn(current);
        CountryCategory country = new CountryCategory(); country.setId(7L);
        when(countries.getCourseCountries()).thenReturn(List.of(country));
        when(mapper.updateCourse(eq(10L), eq(5L), eq(7L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        CourseCreateRequest create = request();
        CourseUpdateRequest update = new CourseUpdateRequest(); update.setTitle(create.getTitle());
        update.setContent(create.getContent()); update.setCountryId(7L); update.setDays(create.getDays());
        service.updateCourse(10L, 5L, update);
        verify(structure).replaceSchedule(10L, 7L, update.getDays());
        verify(mapper, never()).countExistingDestinations(anyList());
        verify(mapper, never()).findDestinationCountries(anyList());
    }

    private CourseServiceImpl service() {
        CourseServiceImpl service = new CourseServiceImpl(mapper, new PostContentSanitizer(), null, null,
                new LocalContentLanguageDetector(), null, structure, mock(CourseCoverStorageService.class), countries, mock(CourseThemeService.class));
        return service;
    }

    private CourseCreateRequest request() {
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1);
        day.setItems(List.of(CourseDirectItemTest.direct("STAY", 1)));
        CourseCreateRequest request = new CourseCreateRequest(); request.setTitle("직접 코스");
        request.setContent("<p>소개</p>"); request.setDays(List.of(day));
        return request;
    }
}
