package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CourseController.class, LegacyCourseController.class})
@Import(SecurityConfig.class)
class CourseRoutesTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourseService courses;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean UserMapper users;

    @Test void legacyDetailRedirectsWithoutReadingOrIncreasingViewsAndKeepsDeepLinks() throws Exception {
        mvc.perform(get("/course/12").queryParam("commentId", "34").queryParam("lang", "en"))
                .andExpect(status().isMovedPermanently())
                .andExpect(redirectedUrl("/courses/12?commentId=34&lang=en"));
        mvc.perform(get("/course/12/translation"))
                .andExpect(status().isMovedPermanently()).andExpect(redirectedUrl("/courses/12/translation"));
        verifyNoInteractions(courses);
    }

    @Test void legacyAuthoringRedirectsAndPreservesPostMethods() throws Exception {
        mvc.perform(get("/course/write").with(user("member")))
                .andExpect(status().isMovedPermanently()).andExpect(redirectedUrl("/courses/new"));
        mvc.perform(get("/course/12/edit").with(user("member")))
                .andExpect(status().isMovedPermanently()).andExpect(redirectedUrl("/courses/12/edit"));
        mvc.perform(post("/course/write").with(user("member")).with(csrf()))
                .andExpect(status().isTemporaryRedirect()).andExpect(redirectedUrl("/courses/new"));
        mvc.perform(post("/course/12/edit").with(user("member")).with(csrf()))
                .andExpect(status().isTemporaryRedirect()).andExpect(redirectedUrl("/courses/12/edit"));
        mvc.perform(post("/course/12/delete").with(user("member")).with(csrf()))
                .andExpect(status().isTemporaryRedirect()).andExpect(redirectedUrl("/courses/12/delete"));
        verifyNoInteractions(courses);
    }

    @Test void bothAuthoringPathsRemainAuthenticatedAndCsrfProtected() throws Exception {
        for (String route : new String[]{"/courses/new", "/courses/12/edit", "/course/write", "/course/12/edit"}) {
            mvc.perform(get(route)).andExpect(status().is3xxRedirection());
            mvc.perform(post(route).with(user("member"))).andExpect(status().isForbidden());
            mvc.perform(post(route).with(csrf())).andExpect(status().is3xxRedirection());
        }
        verifyNoInteractions(courses);
    }
}
