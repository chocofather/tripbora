package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.destination.DestinationImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminDestinationImagePositionApiController.class)
@Import(SecurityConfig.class)
class AdminDestinationImagePositionApiControllerTest {
    private static final String URL = "/admin/api/destinations/3/images/8/card-position";
    @Autowired MockMvc mvc;
    @MockitoBean DestinationImageService images;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    @MockitoBean UserMapper users;

    @Test
    void onlyAdminWithCsrfCanSaveAndReceivesTheSavedPosition() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"x\":0,\"y\":100}")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.x").value(0))
                .andExpect(jsonPath("$.y").value(100));
        verify(images).saveCardPosition(3L, 8L, 0, 100);
    }

    @Test
    void userAnonymousAndMissingCsrfCannotWrite() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"x\":50,\"y\":50}")
                        .with(user("user").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"x\":50,\"y\":50}")
                        .with(user("admin").roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"x\":50,\"y\":50}")
                        .with(csrf())).andExpect(status().is3xxRedirection());
        verifyNoInteractions(images);
    }

    @Test
    void fractionsAndOversizedNumbersAreRejectedWithoutTruncation() throws Exception {
        for (String body : new String[]{"{\"x\":20.5,\"y\":50}", "{\"x\":99999999999999,\"y\":50}"}) {
            mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(user("admin").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(images);
    }

    @Test
    void staleImageOrInvalidRangeReturnsAnActionableError() throws Exception {
        doThrow(new IllegalArgumentException("대표 이미지가 변경되었습니다."))
                .when(images).saveCardPosition(3L, 8L, 20, 80);
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"x\":20,\"y\":80}")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("대표 이미지가 변경되었습니다."));
    }
}
