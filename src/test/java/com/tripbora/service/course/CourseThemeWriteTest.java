package com.tripbora.service.course;

import com.tripbora.dto.*;
import com.tripbora.model.Course;
import com.tripbora.repository.course.CourseMapper;
import com.tripbora.repository.course.CourseThemeMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationLocalizationService;
import com.tripbora.service.post.PostContentSanitizer;
import com.tripbora.service.translation.LocalContentLanguageDetector;
import com.tripbora.config.i18n.SupportedLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CourseThemeWriteTest {
    private final CourseMapper courses = mock(CourseMapper.class);
    private final CourseThemeMapper themes = mock(CourseThemeMapper.class);
    private final CourseStructureService schedule = mock(CourseStructureService.class);
    private final CourseTransitionTest.RecordingTransactionManager transactions = new CourseTransitionTest.RecordingTransactionManager();
    private final CourseServiceImpl service = new CourseServiceImpl(courses, new PostContentSanitizer(),
            mock(DestinationLocalizationService.class), mock(ReferenceNameLocalizationService.class),
            new LocalContentLanguageDetector(), mock(DestinationImageService.class), schedule,
            mock(CourseCoverStorageService.class), mock(CountryCategoryService.class), new CourseThemeService(themes));

    @BeforeEach
    void setup() {
        var country = new CourseDestinationCountryDto(); country.setDestinationId(2L); country.setCountryId(70L);
        when(courses.countExistingDestinations(List.of(2L))).thenReturn(1);
        when(courses.findDestinationCountries(List.of(2L))).thenReturn(List.of(country));
        when(themes.findActiveThemes()).thenReturn(List.of(theme(1L), theme(2L), theme(3L)));
    }

    @Test
    void createStoresThemesAfterScheduleInTheSameTransactionIncludingZeroAndThree() {
        when(courses.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        when(themes.insertMapping(anyLong(), anyLong(), anyInt())).thenReturn(1);
        for (List<Long> selected : List.of(List.<Long>of(), List.of(2L), List.of(3L, 1L, 2L))) {
            var request = create(selected);
            assertThat(proxy().createCourse(request, 5L)).isEqualTo(10L);
        }
        var order = inOrder(courses, schedule, themes);
        order.verify(courses).insertCourse(any());
        order.verify(schedule).replaceSchedule(eq(10L), eq(70L), anyList());
        order.verify(themes).deleteMappings(10L);
        verify(themes).insertMapping(10L, 3L, 1);
        verify(themes).insertMapping(10L, 1L, 2);
        verify(themes).insertMapping(10L, 2L, 3);
        assertThat(transactions.commits).isEqualTo(3);
    }

    @Test
    void invalidSelectionIsRejectedBeforeCourseOrScheduleWrites() {
        for (List<Long> invalid : List.of(List.of(1L, 2L, 3L, 4L), List.of(1L, 1L), List.of(99L))) {
            assertThatThrownBy(() -> proxy().createCourse(create(invalid), 5L))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        }
        verify(courses, never()).insertCourse(any()); verifyNoInteractions(schedule);
        verify(themes, never()).deleteMappings(anyLong());
    }

    @Test
    void editReplacesFinalSelectionAndThemeFailureRollsBackTheOuterCourseTransaction() {
        Course owned = new Course(); owned.setId(10L); owned.setUserId(5L); owned.setTitle("기존");
        when(courses.findActiveCourseForUpdate(10L)).thenReturn(owned);
        when(courses.updateCourse(eq(10L), eq(5L), eq(70L), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(themes.insertMapping(anyLong(), anyLong(), anyInt())).thenReturn(1);
        var request = new CourseUpdateRequest(); request.setTitle("수정"); request.setContent("<p>소개</p>");
        request.setDays(create(List.of()).getDays()); request.setThemeIds(List.of(2L, 3L));
        proxy().updateCourse(10L, 5L, request);
        var order = inOrder(themes);
        order.verify(themes, times(2)).findActiveThemes();
        order.verify(themes).deleteMappings(10L);
        order.verify(themes).insertMapping(10L, 2L, 1); order.verify(themes).insertMapping(10L, 3L, 2);
        when(themes.insertMapping(10L, 3L, 2)).thenReturn(0);
        assertThatThrownBy(() -> proxy().updateCourse(10L, 5L, request)).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.commits).isEqualTo(1); assertThat(transactions.rollbacks).isEqualTo(1);
    }

    @Test
    void editReadRestoresMappingOrderAndLegacyCourseWithoutMappingsIsEmpty() {
        Course owned = new Course(); owned.setId(10L); owned.setUserId(5L);
        when(courses.findActiveCourse(10L)).thenReturn(owned);
        CourseThemeDto first = theme(3L); first.setCourseId(10L);
        CourseThemeDto second = theme(1L); second.setCourseId(10L);
        when(themes.findByCourseIds(List.of(10L))).thenReturn(List.of(first, second));
        assertThat(service.getCourseForEdit(10L, 5L, SupportedLanguage.KOREAN).getThemeIds()).containsExactly(3L, 1L);
        when(themes.findByCourseIds(List.of(10L))).thenReturn(List.of());
        assertThat(service.getCourseForEdit(10L, 5L, SupportedLanguage.KOREAN).getThemeIds()).isEmpty();
    }

    private CourseService proxy() {
        var advice = new TransactionInterceptor(); advice.setTransactionManager(transactions);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        var factory = new ProxyFactory(service); factory.addAdvice(advice); return (CourseService) factory.getProxy();
    }
    private CourseCreateRequest create(List<Long> ids) {
        var item = new CourseItemForm(); item.setDestinationId(2L); item.setDisplayOrder(1);
        var day = new CourseDayForm(); day.setDayNumber(1); day.setItems(List.of(item));
        var request = new CourseCreateRequest(); request.setTitle("테마 코스"); request.setContent("<p>소개</p>");
        request.setDays(List.of(day)); request.setThemeIds(ids); return request;
    }
    private CourseThemeDto theme(Long id) { var value = new CourseThemeDto(); value.setId(id); return value; }
}
