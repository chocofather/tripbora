package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.translation.TitleContentTranslationResponse;
import com.tripbora.service.translation.TitleContentTranslationService;
import com.tripbora.service.translation.TranslatableContentType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CourseTranslationController.class)
@Import(SecurityConfig.class)
class CourseTranslationSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TitleContentTranslationService service;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void guestCanTranslateAVisibleCourse() throws Exception {
        when(service.translate(eq(TranslatableContentType.COURSE), eq(9L),
                eq("ko"), any(), eq(null)))
                .thenReturn(TitleContentTranslationResponse.ready(
                        "번역 코스", "<p>번역 설명</p>", true));

        mockMvc.perform(get("/courses/9/translation"))
                .andExpect(status().isOk());
    }
}
