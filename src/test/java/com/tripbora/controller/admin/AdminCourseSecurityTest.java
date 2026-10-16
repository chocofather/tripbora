package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseEditDto;
import com.tripbora.dto.OfficialCourseForm;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.course.OfficialCourseAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.view.RedirectView;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminCourseController.class)
@Import({SecurityConfig.class, AdminCourseSecurityTest.SecurityViews.class})
class AdminCourseSecurityTest {

    private static final long ADMIN_ID = 1L;
    private static final List<String> READ_ROUTES = List.of(
            "/admin/courses", "/admin/courses/new", "/admin/courses/10/edit", "/admin/courses/10/preview");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private OfficialCourseAdminService officialCourseAdminService;
    @MockitoBean private CourseService courseService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void anonymousVisitorsAreRedirectedFromEveryAdminPageAndWrite() throws Exception {
        for (String route : READ_ROUTES) {
            mockMvc.perform(get(route)).andExpect(status().is3xxRedirection());
        }
        for (String route : List.of("/admin/courses", "/admin/courses/10/edit")) {
            mockMvc.perform(validForm(post(route)).with(csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        verifyNoInteractions(officialCourseAdminService);
    }

    @Test
    void ordinaryMembersCannotReadOrWriteAnyAdminCourseRoute() throws Exception {
        for (String route : READ_ROUTES) {
            mockMvc.perform(get(route).with(authentication(token(UserRole.USER))))
                    .andExpect(status().isForbidden());
        }
        for (String route : List.of("/admin/courses", "/admin/courses/10/edit")) {
            mockMvc.perform(validForm(post(route)).with(authentication(token(UserRole.USER))).with(csrf()))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(officialCourseAdminService);
    }

    @Test
    void administratorsCanOpenEveryAdminPageIncludingDraftPreview() throws Exception {
        CourseEditDto edit = new CourseEditDto();
        edit.setId(10L);
        edit.setTitle("공식 코스");
        when(officialCourseAdminService.getForEdit(10L)).thenReturn(edit);
        CourseDetailDto detail = new CourseDetailDto();
        detail.setId(10L);
        detail.setTitle("공식 코스");
        when(officialCourseAdminService.preview(10L, ADMIN_ID)).thenReturn(detail);

        for (String route : READ_ROUTES) {
            mockMvc.perform(get(route).with(authentication(token(UserRole.ADMIN))))
                    .andExpect(status().isOk());
        }
        verify(officialCourseAdminService).getForEdit(10L);
        verify(officialCourseAdminService).preview(10L, ADMIN_ID);
    }

    @Test
    void adminWritesRequireCsrfIncludingMultipartCoverUploads() throws Exception {
        for (String route : List.of("/admin/courses", "/admin/courses/10/edit")) {
            mockMvc.perform(validForm(post(route)).with(authentication(token(UserRole.ADMIN))))
                    .andExpect(status().isForbidden());
            mockMvc.perform(validForm(multipart(route).file(cover()))
                            .with(authentication(token(UserRole.ADMIN))))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(officialCourseAdminService);
    }

    @Test
    void officialCourseBodyImageUploadEndpointDoesNotExist() throws Exception {
        assertThat(ClassUtils.isPresent("com.tripbora.controller.admin.AdminCourseContentImageApiController",
                getClass().getClassLoader())).isFalse();
        mockMvc.perform(multipart("/admin/api/courses/content-images")
                        .file(new MockMultipartFile("image", "image.png", "image/png", new byte[]{1}))
                        .with(authentication(token(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(officialCourseAdminService);
    }

    @Test
    void adminCanCreateAndUpdateWithCsrfUsingTheAuthenticatedIdentity() throws Exception {
        when(officialCourseAdminService.create(any(OfficialCourseForm.class), eq(ADMIN_ID))).thenReturn(10L);

        mockMvc.perform(validForm(multipart("/admin/courses").file(cover()))
                        .param("userId", "999").param("courseKind", "TRAVELER")
                        .with(authentication(token(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/courses/10/preview"));
        mockMvc.perform(validForm(multipart("/admin/courses/10/edit").file(cover()))
                        .param("userId", "999").param("courseKind", "TRAVELER")
                        .with(authentication(token(UserRole.ADMIN))).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/courses/10/preview"));

        verify(officialCourseAdminService).create(any(OfficialCourseForm.class), eq(ADMIN_ID));
        verify(officialCourseAdminService).update(eq(10L), any(OfficialCourseForm.class), eq(ADMIN_ID));
    }

    private MockHttpServletRequestBuilder validForm(MockHttpServletRequestBuilder request) {
        return request.param("title", "공식 코스").param("summary", "요약").param("status", "DRAFT");
    }

    private MockMultipartFile cover() {
        return new MockMultipartFile("coverImage", "cover.png", "image/png", new byte[]{1});
    }

    private UsernamePasswordAuthenticationToken token(UserRole role) {
        User user = new User();
        user.setId(role == UserRole.ADMIN ? ADMIN_ID : 7L);
        user.setUserRole(role);
        CustomUserDetails principal = new CustomUserDetails(user);
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    /** URL/CSRF behavior is exercised without making security tests depend on full template rendering. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SecurityViews {
        @Bean ViewResolver securityTestViewResolver() {
            return new SecurityViewResolver();
        }
    }

    static class SecurityViewResolver implements ViewResolver, Ordered {
        @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
        @Override public View resolveViewName(String viewName, Locale locale) {
            if (viewName.startsWith("redirect:")) return new RedirectView(viewName.substring("redirect:".length()), true);
            return (model, request, response) -> { };
        }
    }
}
