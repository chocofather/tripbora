package com.tripbora.controller.admin;

import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.OfficialCourseForm;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.course.OfficialCourseAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.http.HttpStatus;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminCourseControllerTest {
    private final OfficialCourseAdminService service = mock(OfficialCourseAdminService.class);
    private final CourseService courses = mock(CourseService.class);
    private final AdminCourseController controller = new AdminCourseController(service, courses);

    @Test
    void bindingOnlyAcceptsAuthoringFieldsAndKeepsNestedDayValues() {
        var form = new OfficialCourseForm();
        var binder = new WebDataBinder(form, "officialCourseForm");
        controller.configureBinding(binder);
        binder.bind(new MutablePropertyValues(Map.of(
                "title", "공식 일정", "status", "DRAFT", "courseKind", "TRAVELER", "userId", "999",
                "days[1].dayNumber", "2", "days[1].items[0].placeName", "숙소",
                "days[1].items[0].countryId", "8", "themeIds", new String[]{"3", "1"},
                "structuredContent", "{\"version\":1,\"blocks\":[]}")));
        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(binder.getBindingResult().getSuppressedFields()).contains("courseKind", "userId", "structuredContent");
        assertThat(form.getDays().get(1).getItems().get(0).getPlaceName()).isEqualTo("숙소");
        assertThat(form.getThemeIds()).containsExactly(3L, 1L);
    }

    @Test
    void createUsesAuthenticatedAdminAndRedirectsToProtectedPreview() {
        var form = new OfficialCourseForm();
        var admin = mock(CustomUserDetails.class);
        when(admin.getId()).thenReturn(7L);
        when(service.create(form, 7L)).thenReturn(40L);
        assertThat(controller.create(form, new BeanPropertyBindingResult(form, "officialCourseForm"),
                admin, new ExtendedModelMap())).isEqualTo("redirect:/admin/courses/40/preview");
        verify(service).create(form, 7L);
    }

    @Test
    void invalidScheduleRestoresAllSubmittedFieldsAndOriginalCover() {
        var form = new OfficialCourseForm();
        form.setTitle("수정 제목"); form.setSummary("요약"); form.setStatus("HIDDEN");
        form.setThemeIds(List.of(3L, 1L));
        form.setRemoveCoverImage(true);
        var admin = mock(CustomUserDetails.class); when(admin.getId()).thenReturn(7L);
        var current = new CourseEditDto(); current.setCoverImageUrl("/uploads/course/cover/4/old.jpg");
        when(service.getForEdit(40L)).thenReturn(current);
        when(courses.prepareFormForRetry("수정 제목", null, null, form.getDays())).thenReturn(new CourseEditDto());
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "일정을 확인해 주세요."))
                .when(service).update(40L, form, 7L);
        var model = new ExtendedModelMap();
        assertThat(controller.update(40L, form, new BeanPropertyBindingResult(form, "officialCourseForm"),
                admin, model)).isEqualTo("admin/courses/form");
        var retry = (CourseEditDto) model.get("course");
        assertThat(retry.getTitle()).isEqualTo("수정 제목");
        assertThat(retry.getSummary()).isEqualTo("요약");
        assertThat(retry.getStatus()).isEqualTo("HIDDEN");
        assertThat(retry.getThemeIds()).containsExactly(3L, 1L);
        assertThat(retry.isRemoveCoverImage()).isTrue();
        assertThat(retry.getCoverImageUrl()).isEqualTo(current.getCoverImageUrl());
        assertThat(model.get("formError")).isEqualTo("일정을 확인해 주세요.");
    }

    @Test
    void previewUsesSharedCourseDetailWithoutContentParser() {
        var admin = mock(CustomUserDetails.class); when(admin.getId()).thenReturn(7L);
        var detail = new CourseDetailDto(); detail.setCourseKind("OFFICIAL");
        when(service.preview(40L, 7L)).thenReturn(detail);
        var model = new ExtendedModelMap();
        assertThat(controller.preview(40L, admin, model)).isEqualTo("course/detail");
        assertThat(model.get("course")).isSameAs(detail);
        assertThat(model.get("adminPreview")).isEqualTo(true);
        assertThat(model.get("seoRobots")).isEqualTo("noindex, nofollow");
        assertThat(model).doesNotContainKey("structuredContent");
    }
}
