package com.tripbora.controller.course;

import com.tripbora.config.*;
import com.tripbora.dto.*;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
class CourseCoverControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CourseService service;
    @MockitoBean CountryCategoryService countries;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;

    @Test
    void multipartCreateAndEditStillRequireLoginAndCsrf() throws Exception {
        for (String url : List.of("/courses/new", "/courses/10/edit")) {
            mvc.perform(multipart(url).file(image()).with(csrf()))
                    .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login?redirect=" + url));
            mvc.perform(multipart(url).file(image()).with(member()))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void multipartBindingAcceptsFileAndStartTimeAndSuppressesClientUrlAndEndTime() throws Exception {
        when(service.createCourse(any(), eq(5L))).thenReturn(10L);
        mvc.perform(multipart("/courses/new").file(image()).with(member()).with(csrf())
                        .param("title", "서울 여행").param("days[0].dayNumber", "1")
                        .param("days[0].items[0].destinationId", "12")
                        .param("days[0].items[0].startTime", "09:00")
                        .param("days[0].items[0].endTime", "bad-time")
                        .param("coverImageUrl", "/uploads/course/cover/6/forged.png"))
                .andExpect(redirectedUrl("/courses/10"));
        var request = org.mockito.ArgumentCaptor.forClass(CourseCreateRequest.class);
        verify(service).createCourse(request.capture(), eq(5L));
        assertThat(request.getValue().getCoverImage().getBytes()).containsExactly((byte) 1, (byte) 2);
        assertThat(request.getValue().getDays().get(0).getItems().get(0).getStartTime()).isEqualTo("09:00");
        assertThat(request.getValue().getDays().get(0).getItems().get(0).getEndTime()).isNull();
        mvc.perform(multipart("/courses/10/edit").with(member()).with(csrf()).param("removeCoverImage", "true"))
                .andExpect(redirectedUrl("/courses/10"));
        verify(service).updateCourse(eq(10L), eq(5L), argThat(CourseUpdateRequest::isRemoveCoverImage));
    }

    @Test
    void createAndEditRenderCompactCoverControlsInsideTheExistingForm() throws Exception {
        CourseEditDto edit = edit();
        when(service.getCourseForEdit(eq(10L), eq(5L), any())).thenReturn(edit);
        for (String url : List.of("/courses/new", "/courses/10/edit")) {
            var doc = Jsoup.parse(mvc.perform(get(url).with(member())).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertThat(doc.selectFirst("#course-form").attr("enctype")).isEqualTo("multipart/form-data");
            assertThat(doc.selectFirst("[data-course-cover]").parents().select("#course-form")).isNotEmpty();
            assertThat(doc.select("[data-cover-preview][hidden]")).hasSize(1);
            assertThat(doc.select("button[data-cover-select]").text()).isEqualTo("이미지 선택");
            assertThat(doc.select("input[name=coverImage][accept='image/jpeg,image/png,image/webp']")).hasSize(1);
            assertThat(doc.select("script[src^='/js/course-cover-'][src$='.js']")).hasSize(1);
            if (url.endsWith("edit")) assertThat(doc.selectFirst("[data-course-cover]").attr("data-existing-cover"))
                    .isEqualTo(edit.getCoverImageUrl());
        }
    }

    @Test
    void validationRetryKeepsTheCurrentOwnedCoverAndDeletionIntent() throws Exception {
        when(service.getCourseForEdit(eq(10L), eq(5L), any())).thenReturn(edit());
        when(service.prepareFormForRetry(any(), any(), any(), any())).thenReturn(edit());
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "입력 확인"))
                .when(service).updateCourse(eq(10L), eq(5L), any());
        var doc = Jsoup.parse(mvc.perform(multipart("/courses/10/edit").with(member()).with(csrf())
                        .param("title", "서울 여행").param("removeCoverImage", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(doc.selectFirst("[data-course-cover]").attr("data-existing-cover"))
                .isEqualTo("/uploads/course/cover/5/current.png");
        assertThat(doc.selectFirst("input[name=removeCoverImage]").val()).isEqualTo("true");
    }

    private CourseEditDto edit() {
        CourseEditDto edit = new CourseEditDto();
        edit.setId(10L); edit.setTitle("서울 여행"); edit.setContent("<p>소개</p>");
        edit.setCoverImageUrl("/uploads/course/cover/5/current.png"); edit.setDays(List.of());
        return edit;
    }

    private RequestPostProcessor member() {
        CustomUserDetails principal = mock(CustomUserDetails.class);
        when(principal.getId()).thenReturn(5L);
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("coverImage", "test.png", "image/png", new byte[]{1, 2});
    }
}
