package com.tripbora.config;

import com.tripbora.controller.course.CourseController;
import com.tripbora.controller.travelplan.TravelPlanController;
import com.tripbora.controller.user.LoginController;
import com.tripbora.dto.*;
import com.tripbora.model.*;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.travelplan.*;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.DigestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real controllers, templates, URL encoding filter and resource handlers; no database. */
@WebMvcTest({TravelPlanController.class, CourseController.class, LoginController.class})
@Import(SecurityConfig.class)
class StaticResourceRenderingTest {
    @Autowired MockMvc mvc;
    @MockitoBean TravelPlanService plans;
    @MockitoBean TravelPlanInvitationService invitations;
    @MockitoBean TravelPlanFinalReadService finalRead;
    @MockitoBean TravelPlanFinalDeleteService finalDelete;
    @MockitoBean TravelPlanDeleteService delete;
    @MockitoBean CourseService courses;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean com.tripbora.security.LoginThrottle loginThrottle;

    @Test
    void independentLoginPageRetiresManualDateVersions() throws Exception {
        when(loginThrottle.ipStatus(anyString())).thenReturn(new com.tripbora.security.LoginThrottleStatus(0, null));
        var html = mvc.perform(get("/login")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertResources(html, "login.css", "login.js");
        assertThat(Jsoup.parse(html).select("link[href^=/css/]").eachAttr("href"))
                .allSatisfy(url -> assertThat(url).doesNotContain("?v="));
    }

    @Test
    void planListAndPlannerRequestCurrentContentThroughHashedPaths() throws Exception {
        var start = LocalDate.of(2026, 10, 20);
        var item = new TravelPlanListItemDto();
        item.setTravelPlanId(42L); item.setTitle("테스트 여행");
        item.setStartDate(start); item.setEndDate(start.plusDays(1));
        when(plans.getActivePlans(7L)).thenReturn(List.of(item));
        var plan = new TravelPlan();
        plan.setId(42L); plan.setTitle("테스트 여행");
        plan.setStartDate(start); plan.setEndDate(start.plusDays(1));
        when(plans.getActivePlanDetail(7L, 42L)).thenReturn(new TravelPlanDetailDto(
                plan, new TravelPlanMember(), List.of(), Map.of(), Map.of(), List.of(), List.of(), 8));
        var list = render("/travel-plans");
        assertThat(Jsoup.parse(list).select(".tp-trip-icon")).hasSize(1);
        assertResources(list, "travel-plan-list.css", "travel-plan-list.js");
        assertResources(render("/travel-plans/42"), "travel-plan-planner.css", "travel-plan-item-autosave.js");
    }

    @Test
    void courseIncludesImportMarkupAndVersionedInitializationForGuestsAndMembers() throws Exception {
        var course = new CourseDetailDto();
        course.setId(9L); course.setUserId(8L); course.setTitle("테스트 코스");
        course.setCourseKind("TRAVELER"); course.setContent("<p>코스 소개</p>");
        course.setCreatedAt(Timestamp.valueOf("2026-10-20 00:00:00"));
        var day = new CourseDayDto(); day.setDayNumber(1);
        course.setDays(List.of(day)); course.setStops(List.of());
        when(courses.getCourseDetail(eq(9L), nullable(Long.class), any())).thenReturn(course);
        for (var request : List.of(get("/courses/9"), get("/courses/9").with(user(member())))) {
            var html = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(html).select("[data-course-plan-open], #course-plan-dialog")).hasSize(2);
            assertResources(html, "course-plan-create.css", "course-plan-create.js");
            Files.createDirectories(Path.of("build/reports/static-resources"));
            Files.writeString(Path.of("build/reports/static-resources/course.html"), html);
        }
    }

    private String render(String url) throws Exception {
        var html = mvc.perform(get(url).with(user(member()))).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsString();
        // Browser fixtures use these actual rendered pages with the actual HTTP resource URLs.
        var directory = Path.of("build/reports/static-resources");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(url.replace('/', '_') + ".html"), html);
        return html;
    }

    private void assertResources(String html, String css, String js) throws Exception {
        var assets = Jsoup.parse(html).select("link[href^=/css/], script[src^=/js/]");
        assertThat(assets).isNotEmpty();
        assertThat(assets.toString()).contains(css.replace(".css", "-"), js.replace(".js", "-"));
        for (var asset : assets) {
            var url = asset.hasAttr("href") ? asset.attr("href") : asset.attr("src");
            assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
            var original = url.replaceFirst("-[a-f0-9]{32}(?=\\.(css|js)$)", "");
            var bytes = new ClassPathResource("static" + original).getContentAsByteArray();
            assertThat(url).contains("-" + DigestUtils.md5DigestAsHex(bytes) + ".");
            var response = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            assertThat(response.getHeader("Cache-Control")).contains("public", "max-age=");
            if (url.endsWith(".js")) assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
            assertThat(response.getContentAsString()).doesNotContain("<!DOCTYPE html>");
            var file = Path.of("build/reports/static-resources/assets" + url);
            Files.createDirectories(file.getParent());
            Files.write(file, response.getContentAsByteArray());
        }
    }

    private CustomUserDetails member() {
        var member = new User(); member.setId(7L); member.setUserRole(UserRole.USER);
        return new CustomUserDetails(member);
    }
}
