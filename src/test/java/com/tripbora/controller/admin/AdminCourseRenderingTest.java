package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.CourseDayDto;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.CourseThemeDto;
import com.tripbora.dto.OfficialCourseListDto;
import com.tripbora.model.CountryCategory;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.course.OfficialCourseAdminService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 Thymeleaf layout과 공용 fragment를 통과하는 관리자 코스 화면 검증. */
@WebMvcTest(AdminCourseController.class)
@Import(SecurityConfig.class)
class AdminCourseRenderingTest {
    private static final long ADMIN_ID = 7L;
    @Autowired MockMvc mvc;
    @MockitoBean OfficialCourseAdminService officialCourses;
    @MockitoBean CourseService courses;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean UserMapper users;

    @BeforeEach
    void masterData() {
        when(courses.getCourseThemes()).thenReturn(List.of(theme(3, "자연"), theme(1, "문화"), theme(2, "미식")));
        when(courses.getCourseCountries(SupportedLanguage.KOREAN)).thenReturn(List.of(
                country(10, "대한민국", "KR"), country(20, "일본", "JP")));
    }

    @Test
    void listRendersTimestampCountriesThemesAndKeepsFiltersAcrossPages() throws Exception {
        var course = new OfficialCourseListDto();
        course.setId(40L); course.setTitle("서울 <도보> 여행");
        course.setRepresentativeImageUrl("/uploads/course/cover/example.jpg");
        course.setCountryNames(List.of("대한민국", "일본"));
        course.setThemes(List.of(theme(3, "자연"), theme(2, "미식")));
        course.setDayCount(2); course.setStatus("HIDDEN");
        course.setCreatedAt(Timestamp.valueOf("2026-10-17 09:30:00"));
        when(officialCourses.count("서울", "HIDDEN")).thenReturn(45);
        when(officialCourses.list("서울", "HIDDEN", 2)).thenReturn(List.of(course));

        Document page = render("/admin/courses?keyword=서울&status=HIDDEN&page=2");
        assertThat(page.select(".admin-course-table tbody tr")).hasSize(1);
        assertThat(page.select(".admin-course-list-title a").text()).isEqualTo("서울 <도보> 여행");
        assertThat(page.select(".admin-course-table tbody").text())
                .contains("대한민국 · 일본", "2일", "숨김", "2026-10-17").doesNotContain("수정일");
        assertThat(page.select(".course-theme-badge").eachText()).containsExactly("자연", "미식");
        assertThat(page.select("input[name=keyword]").val()).isEqualTo("서울");
        assertThat(page.select("select[name=status] option[selected]").val()).isEqualTo("HIDDEN");
        assertThat(page.select(".admin-course-pagination a")).hasSize(2)
                .allSatisfy(link -> assertThat(link.attr("href")).contains("keyword=", "status=HIDDEN", "page="));
        assertThat(page.select(".admin-nav-link.is-active").attr("href")).isEqualTo("/admin/courses");
    }

    @Test
    void emptyListStillRendersTheCreateAction() throws Exception {
        when(officialCourses.count("", "")).thenReturn(0);
        when(officialCourses.list("", "", 1)).thenReturn(List.of());
        Document page = render("/admin/courses");
        assertThat(page.select(".admin-table-empty").text()).contains("검증 코스가 없습니다");
        assertThat(page.select("a[href='/admin/courses/new']")).hasSize(1);
    }

    @Test
    void createFormRendersThreeCourseSectionsWithCsrfAndNoIntroductionEditor() throws Exception {
        Document page = render("/admin/courses/new");
        var form = page.selectFirst("#course-form");
        assertThat(form).isNotNull();
        assertThat(form.attr("action")).isEqualTo("/admin/courses");
        assertThat(form.attr("method")).isEqualTo("post");
        assertThat(form.attr("enctype")).isEqualTo("multipart/form-data");
        assertThat(form.select("input[name=_csrf]").val()).isNotBlank();
        assertThat(form.select(".admin-course-section")).hasSize(3);
        assertThat(form.select(".admin-course-section > h2").eachText()).containsExactly("기본 정보", "공개 설정");
        assertThat(form.select("#course-title").attr("maxlength")).isEqualTo("255");
        assertThat(form.select("#course-summary").attr("maxlength")).isEqualTo("300");
        assertThat(form.select("#course-status option[selected]").val()).isEqualTo("DRAFT");
        assertThat(form.select("#course-status option[selected]").text()).isEqualTo("임시저장");
        assertThat(form.attr("data-content-editor")).isEqualTo("none");
        assertThat(form.hasAttr("data-image-upload-url")).isFalse();
        assertThat(form.select("[name=structuredContent], [data-structured-editor], .admin-structured-editor")).isEmpty();
        assertThat(page.select("script[src*='structured'], link[href*='structured']")).isEmpty();
        assertThat(form.select("#course-day-tabs, #course-search-panel, #destination-id-inputs")).hasSize(3);
        assertThat(form.select("[data-initial-day]")).isEmpty();
        assertThat(form.select("#course-country-options option").eachText()).containsExactly("대한민국", "일본");
        assertThat(form.select("[data-course-theme]").eachText()).containsExactly("자연", "문화", "미식");
        assertThat(form.select("input[name=content], #editor")).isEmpty();
    }

    @Test
    void editFormRestoresDayAndDirectItemValuesWithoutChangingThemeOrder() throws Exception {
        var edit = new CourseEditDto();
        edit.setId(40L); edit.setTitle("공식 <일정>"); edit.setSummary("요약 & 안내");
        edit.setStatus("HIDDEN");
        edit.setCoverImageUrl("/uploads/course/cover/40/existing.jpg");
        edit.setThemeIds(List.of(2L, 3L)); edit.setDays(List.of(day()));
        when(officialCourses.getForEdit(40L)).thenReturn(edit);

        Document page = render("/admin/courses/40/edit");
        var form = page.selectFirst("#course-form");
        assertThat(form.attr("action")).isEqualTo("/admin/courses/40/edit");
        assertThat(form.select("input[name=_csrf]").val()).isNotBlank();
        assertThat(form.select("#course-title").val()).isEqualTo("공식 <일정>");
        assertThat(form.select("#course-summary").val()).isEqualTo("요약 & 안내");
        assertThat(form.select("#course-status option[selected]").val()).isEqualTo("HIDDEN");
        assertThat(form.select("[name=structuredContent], .admin-structured-editor")).isEmpty();
        assertThat(form.select("[data-course-cover]").attr("data-existing-cover")).isEqualTo(edit.getCoverImageUrl());
        assertThat(form.select("[data-course-theme]").eachText()).containsExactly("자연", "문화", "미식");
        assertThat(form.select("input[name=themeIds]").eachAttr("value")).containsExactly("2", "3");
        var day = form.selectFirst("[data-initial-day]");
        assertThat(day.attr("data-day-id")).isEqualTo("50");
        assertThat(day.attr("data-title")).isEqualTo("도착 <첫날>");
        var item = day.selectFirst("[data-initial-destination]");
        assertThat(item.attr("data-destination-id")).isEmpty();
        assertThat(item.attr("data-item-id")).isEqualTo("60");
        assertThat(item.attr("data-item-type")).isEqualTo("STAY");
        assertThat(item.attr("data-name")).isEqualTo("숙소 <예약>");
        assertThat(item.attr("data-country-id")).isEqualTo("20");
        assertThat(item.attr("data-latitude")).isEqualTo("35.68");
        assertThat(item.attr("data-longitude")).isEqualTo("139.76");
        assertThat(item.attr("data-start-time")).isEqualTo("15:00");
        assertThat(item.attr("data-memo")).isEqualTo("예약 & 체크인");
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "HIDDEN"})
    void previewRendersTheSharedCourseSummaryAndTimelineWithoutMemberActions(String status) throws Exception {
        var detail = new CourseDetailDto();
        detail.setId(40L); detail.setUserId(ADMIN_ID); detail.setCourseKind("OFFICIAL");
        detail.setTitle(status + " 공식 코스"); detail.setSummary("코스 요약");
        detail.setNickname("개인관리자닉네임"); detail.setMyCourse(true); detail.setTranslationAvailable(true);
        detail.setDays(List.of(day())); detail.setStops(List.of());
        detail.setRepresentativeImageUrl("/uploads/course/cover/40/example.jpg");
        detail.setCreatedAt(Timestamp.valueOf("2026-10-17 09:30:00")); detail.setViews(0);
        when(officialCourses.preview(40L, ADMIN_ID)).thenReturn(detail);

        Document page = render("/admin/courses/40/preview");
        assertThat(page.select(".course-admin-preview a").attr("href")).isEqualTo("/admin/courses/40/edit");
        assertThat(page.select(".course-official-badge")).hasSize(1);
        assertThat(page.select(".course-official-badge").text()).isEqualTo("TripBora 추천 코스");
        assertThat(page.select(".course-official-summary").text()).isEqualTo("코스 요약");
        assertThat(page.select(".structured-content, [data-structured-gallery], .course-introduction")).isEmpty();
        assertThat(page.select("script[src*='structured'], link[href*='structured']")).isEmpty();
        assertThat(page.select(".course-hero-image").attr("src")).isEqualTo(detail.getRepresentativeImageUrl());
        assertThat(page.select(".course-day-route .course-stop-heading").text()).contains("15:00", "숙소 <예약>");
        assertThat(page.select("#course-comments, [data-bookmark-url], .course-owner-menu, #course-hide-modal, [data-translation-toggle]")).isEmpty();
        assertThat(page.select("script[src='/js/course-comments.js'], script[src='/js/content-bookmarks.js']")).isEmpty();
        assertThat(page.text()).doesNotContain("개인관리자닉네임");
        assertThat(page.select("a[href='/users/7']")).isEmpty();
    }

    private Document render(String path) throws Exception {
        var user = new User(); user.setId(ADMIN_ID); user.setUserRole(UserRole.ADMIN);
        var principal = new CustomUserDetails(user);
        var token = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        return Jsoup.parse(mvc.perform(get(path).with(authentication(token))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static CourseThemeDto theme(long id, String name) {
        var theme = new CourseThemeDto(); theme.setId(id); theme.setName(name); return theme;
    }

    private static CountryCategory country(long id, String name, String code) {
        var country = new CountryCategory(); country.setId(id); country.setRegionName(name); country.setCode(code);
        if (!"KR".equals(code)) country.setParentId(100L);
        return country;
    }

    private static CourseDayDto day() {
        var stop = new CourseStopDto();
        stop.setItemId(60L); stop.setItemType("STAY"); stop.setName("숙소 <예약>");
        stop.setCountryId(20L); stop.setCountryName("일본"); stop.setCountryCode("JP");
        stop.setAddress("도쿄 숙소 주소"); stop.setLatitude(new BigDecimal("35.68"));
        stop.setLongitude(new BigDecimal("139.76")); stop.setStartTime("15:00");
        stop.setMemo("예약 & 체크인"); stop.setVisitOrder(1);
        var day = new CourseDayDto(); day.setId(50L); day.setDayNumber(1); day.setTitle("도착 <첫날>");
        day.setItems(List.of(stop)); return day;
    }
}
