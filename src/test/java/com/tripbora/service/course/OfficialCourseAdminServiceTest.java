package com.tripbora.service.course;

import com.tripbora.dto.*;
import com.tripbora.model.Course;
import com.tripbora.repository.course.CourseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OfficialCourseAdminServiceTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseServiceImpl courses = mock(CourseServiceImpl.class);
    private final CourseStructureService structure = mock(CourseStructureService.class);
    private final CourseThemeService themes = mock(CourseThemeService.class);
    private final CourseCoverStorageService covers = mock(CourseCoverStorageService.class);
    private OfficialCourseAdminService service;

    @BeforeEach void setUp() {
        service = new OfficialCourseAdminService(mapper, courses, structure, themes, covers);
    }

    @Test void createFixesKindOwnerAndDefaultsToDraftWithSummaryOnly() {
        OfficialCourseForm form = form();
        when(courses.validateTitle(form.getTitle())).thenReturn("서울 공식 코스");
        when(courses.validateScheduleAndCountries(form.getDays())).thenReturn(7L);
        when(mapper.insertCourse(any())).thenAnswer(call -> {
            ((Course) call.getArgument(0)).setId(10L); return 1;
        });

        assertThat(service.create(form, 5L)).isEqualTo(10L);

        ArgumentCaptor<Course> saved = ArgumentCaptor.forClass(Course.class);
        verify(mapper).insertCourse(saved.capture());
        assertThat(saved.getValue().getCourseKind()).isEqualTo("OFFICIAL");
        assertThat(saved.getValue().getStatus()).isEqualTo("DRAFT");
        assertThat(saved.getValue().getUserId()).isEqualTo(5L);
        assertThat(saved.getValue().getSummary()).isEqualTo("요약");
        assertThat(saved.getValue().getContent()).isEqualTo("<p>요약</p>");
        verify(structure).replaceSchedule(10L, 7L, form.getDays());
        verify(themes).replaceThemes(10L, form.getThemeIds());
    }

    @ParameterizedTest @ValueSource(strings = {"DRAFT", "PUBLISHED", "HIDDEN"})
    void createAndEditPersistEveryAllowedStatusAndFinalFormValues(String status) {
        OfficialCourseForm form = form(); form.setStatus(status); form.setThemeIds(List.of(2L, 1L));
        form.setSummary("  최종 요약  "); form.setRemoveCoverImage(true);
        when(courses.validateTitle(form.getTitle())).thenReturn(form.getTitle());
        when(courses.validateScheduleAndCountries(form.getDays())).thenReturn(7L);
        when(mapper.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        Course current = official(); current.setCoverImageUrl("/uploads/course/cover/5/old.png");
        when(mapper.findOfficialCourseForUpdate(10L)).thenReturn(current);
        when(mapper.updateOfficialCourse(any())).thenReturn(1);
        service.create(form, 5L);
        service.update(10L, form, 99L);
        var saved = ArgumentCaptor.forClass(Course.class);
        verify(mapper).insertCourse(saved.capture()); verify(mapper).updateOfficialCourse(saved.capture());
        for (Course row : saved.getAllValues()) {
            assertThat(row.getStatus()).isEqualTo(status);
            assertThat(row.getTitle()).isEqualTo(form.getTitle());
            assertThat(row.getSummary()).isEqualTo("최종 요약");
            assertThat(row.getContent()).isEqualTo("<p>최종 요약</p>");
            assertThat(row.getCoverImageUrl()).isNull();
        }
        verify(structure, times(2)).replaceSchedule(10L, 7L, form.getDays());
        verify(themes, times(2)).replaceThemes(10L, List.of(2L, 1L));
        verify(covers).removeAfterCommit(current.getCoverImageUrl(), 5L);
    }

    @Test void invalidStatusAndOversizedSummaryFailBeforeWrites() {
        OfficialCourseForm form = form(); form.setStatus("TRAVELER");
        assertThatThrownBy(() -> service.create(form, 5L)).isInstanceOf(ResponseStatusException.class);
        form.setStatus("DRAFT"); form.setSummary("가".repeat(301));
        assertThatThrownBy(() -> service.create(form, 5L)).isInstanceOf(ResponseStatusException.class);
        verify(mapper, never()).insertCourse(any());
    }

    @Test void summaryFallbackEscapesHtmlForSearchAndSeo() {
        OfficialCourseForm form = form();
        form.setSummary("<script>alert(1)</script> & 소개");
        when(mapper.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        service.create(form, 5L);
        var saved = ArgumentCaptor.forClass(Course.class); verify(mapper).insertCourse(saved.capture());
        assertThat(saved.getValue().getContent()).isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; 소개</p>");
    }

    @Test void missingOrBlankSummaryIsOptional() {
        when(mapper.insertCourse(any())).thenAnswer(call -> { ((Course) call.getArgument(0)).setId(10L); return 1; });
        for (String summary : new String[]{null, "", "   "}) {
            OfficialCourseForm form = form(); form.setSummary(summary);
            service.create(form, 5L);
        }
        var saved = ArgumentCaptor.forClass(Course.class); verify(mapper, times(3)).insertCourse(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(row -> {
            assertThat(row.getSummary()).isEmpty();
            assertThat(row.getContent()).isEmpty();
        });
    }

    @Test void updateByAnotherAdminUsesOriginalOwnerForCoverLifecycle() {
        OfficialCourseForm form = form();
        form.setCoverImage(new MockMultipartFile("coverImage", "cover.png", "image/png", new byte[]{1}));
        Course current = official(); current.setCoverImageUrl("/uploads/course/cover/5/old.png");
        when(mapper.findOfficialCourseForUpdate(10L)).thenReturn(current);
        when(courses.validateTitle(form.getTitle())).thenReturn(form.getTitle());
        when(courses.validateScheduleAndCountries(form.getDays())).thenReturn(7L);
        when(covers.save(form.getCoverImage(), 5L)).thenReturn("/uploads/course/cover/5/new.png");
        when(mapper.updateOfficialCourse(any())).thenReturn(1);

        service.update(10L, form, 99L);

        verify(covers).save(form.getCoverImage(), 5L);
        verify(covers).removeAfterCommit(current.getCoverImageUrl(), 5L);
    }

    @Test void travelerAndDeletedRowsCannotBeReadOrEditedThroughAdminService() {
        assertThatThrownBy(() -> service.getForEdit(10L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.update(10L, form(), 99L)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.preview(10L, 99L)).isInstanceOf(ResponseStatusException.class);
        verify(mapper, never()).updateOfficialCourse(any());
    }

    @Test void normalEditKeepsOriginalOwnerAndCover() {
        Course current = official(); current.setCoverImageUrl("/uploads/course/cover/5/old.png");
        when(mapper.findOfficialCourseForUpdate(10L)).thenReturn(current);
        when(mapper.updateOfficialCourse(any())).thenReturn(1);
        service.update(10L, form(), 99L);
        var saved = ArgumentCaptor.forClass(Course.class); verify(mapper).updateOfficialCourse(saved.capture());
        assertThat(saved.getValue().getUserId()).isEqualTo(5L);
        assertThat(saved.getValue().getCoverImageUrl()).isEqualTo(current.getCoverImageUrl());
        verifyNoInteractions(covers);
    }

    @Test void scheduleFailureRollsBackWholeOfficialCreation() {
        when(mapper.insertCourse(any())).thenAnswer(call -> {
            ((Course) call.getArgument(0)).setId(10L); return 1;
        });
        doThrow(new IllegalStateException("schedule failed")).when(structure).replaceSchedule(any(), any(), any());
        var transactions = new CourseTransitionTest.RecordingTransactionManager();
        var factory = new org.springframework.aop.framework.ProxyFactory(service);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(transactions,
                new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()));
        OfficialCourseAdminService proxy = (OfficialCourseAdminService) factory.getProxy();
        assertThatThrownBy(() -> proxy.create(form(), 5L)).isInstanceOf(IllegalStateException.class);
        assertThat(transactions.rollbacks).isEqualTo(1);
        assertThat(transactions.commits).isZero();
        verify(themes, never()).replaceThemes(any(), any());
    }

    private Course official() {
        Course c = new Course(); c.setId(10L); c.setUserId(5L); c.setCourseKind("OFFICIAL"); return c;
    }
    private OfficialCourseForm form() {
        OfficialCourseForm f = new OfficialCourseForm(); f.setTitle("서울 공식 코스"); f.setSummary("요약");
        CourseDayForm day = new CourseDayForm(); day.setDayNumber(1);
        CourseItemForm item = new CourseItemForm(); item.setDisplayOrder(1); item.setDestinationId(12L);
        day.setItems(List.of(item)); f.setDays(List.of(day)); return f;
    }
}
