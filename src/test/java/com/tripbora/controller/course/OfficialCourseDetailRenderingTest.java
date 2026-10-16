package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.CourseDayDto;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
class OfficialCourseDetailRenderingTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourseService courses;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean UserMapper users;

    @Test
    void verifiedCourseUsesSharedSummaryHeroAndTimelineWithoutTheAdminProfile() throws Exception {
        var course = new CourseDetailDto();
        course.setId(40L); course.setUserId(1L); course.setCourseKind("OFFICIAL");
        course.setTitle("공식 여행"); course.setNickname("개인관리자닉네임");
        course.setSummary("핵심 일정 소개"); course.setContent("기존 본문은 노출하지 않음");
        course.setCreatedAt(Timestamp.valueOf("2026-10-17 00:00:00")); course.setViews(0);
        course.setRepresentativeImageUrl("/uploads/course/cover/40/example.jpg");
        var day = new CourseDayDto(); day.setDayNumber(1);
        course.setDays(List.of(day)); course.setStops(List.of());
        when(courses.getCourseDetail(eq(40L), isNull(), any())).thenReturn(course);
        var html = mvc.perform(get("/course/40")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(html);
        assertThat(document.select(".course-official-badge")).hasSize(1);
        assertThat(document.select(".course-official-badge").text()).isEqualTo("✓ TripBora 검증 코스");
        assertThat(document.select(".course-official-summary").text()).isEqualTo("핵심 일정 소개");
        assertThat(document.select("meta[name=description]").attr("content")).contains("핵심 일정 소개");
        assertThat(document.select(".course-meta").text()).contains("TripBora");
        assertThat(document.select(".course-hero-image").attr("src")).isEqualTo(course.getRepresentativeImageUrl());
        assertThat(document.select(".course-day-route")).hasSize(1);
        assertThat(document.select(".structured-content, [data-structured-gallery], .course-introduction")).isEmpty();
        assertThat(document.select("script[src*='structured'], link[href*='structured']")).isEmpty();
        assertThat(document.select(".course-breadcrumb").text()).doesNotContain("검증", "공식");
        assertThat(html).doesNotContain("개인관리자닉네임", "기존 본문은 노출하지 않음", "/users/1");
        assertThat(document.select("[data-bookmark-url='/bookmarks/courses/40']")).isNotEmpty();
        assertThat(document.select("#course-comments")).isNotEmpty();
    }

    @Test
    void travelerKeepsTheExistingIntroductionAndNeverReceivesTheVerifiedBadge() throws Exception {
        var course = new CourseDetailDto();
        course.setId(41L); course.setUserId(5L); course.setCourseKind("TRAVELER");
        course.setTitle("여행자 일정"); course.setNickname("여행자");
        course.setContent("<p>직접 작성한 여행 소개</p>");
        course.setCreatedAt(Timestamp.valueOf("2026-10-17 00:00:00")); course.setViews(0);
        course.setDays(List.of()); course.setStops(List.of());
        when(courses.getCourseDetail(eq(41L), isNull(), any())).thenReturn(course);
        var html = mvc.perform(get("/course/41")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var page = Jsoup.parse(html);
        assertThat(page.select(".course-official-badge")).isEmpty();
        assertThat(page.select(".course-introduction").text()).contains("직접 작성한 여행 소개");
        assertThat(page.select("a[href='/users/5']").text()).isEqualTo("여행자");
    }
}
