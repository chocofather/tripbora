package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.destination.DestinationNotFoundException;
import com.tripbora.service.destination.DestinationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDestinationVisibilityController.class)
@Import(SecurityConfig.class)
class AdminDestinationVisibilitySecurityTest {

    private static final String URL = "/admin/destinations/10/visibility";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DestinationService destinationService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void anonymousCannotChangeVisibility() throws Exception {
        mockMvc.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicVisible\":false}"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(destinationService);
    }

    @Test
    void ordinaryUserCannotChangeVisibility() throws Exception {
        mockMvc.perform(post(URL).with(user("user").roles("USER")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicVisible\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(destinationService);
    }

    @Test
    void administratorStillNeedsCsrf() throws Exception {
        mockMvc.perform(post(URL).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicVisible\":false}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(destinationService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void administratorCanSetEitherState(boolean publicVisible) throws Exception {
        when(destinationService.setPublicVisible(10L, publicVisible)).thenReturn(publicVisible);
        mockMvc.perform(post(URL).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicVisible\":" + publicVisible + "}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.publicVisible").value(publicVisible));
        verify(destinationService).setPublicVisible(10L, publicVisible);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"publicVisible\":null}", "{\"publicVisible\":\"false\"}",
            "{\"publicVisible\":0}", "{\"publicVisible\":1}", "{\"publicVisible\":[]}",
            "{\"publicVisible\":{}}", "null"})
    void requestRequiresAnActualJsonBoolean(String body) throws Exception {
        mockMvc.perform(post(URL).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(destinationService);
    }

    @Test
    void missingDestinationReturnsNotFound() throws Exception {
        when(destinationService.setPublicVisible(10L, false)).thenThrow(new DestinationNotFoundException());
        mockMvc.perform(post(URL).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicVisible\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void persistenceFailureReturnsGenericErrorWithoutDatabaseDetails() throws Exception {
        when(destinationService.setPublicVisible(10L, false))
                .thenThrow(new DataAccessResourceFailureException("internal-database-detail"));
        mockMvc.perform(post(URL).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicVisible\":false}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("internal-database-detail"))));
    }
}
