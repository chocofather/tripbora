package com.tripbora.controller.admin;

import com.tripbora.config.*;
import com.tripbora.dto.kto.*;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.kto.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminKtoFestivalBulkController.class)
@Import(SecurityConfig.class)
class AdminKtoFestivalBulkControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean KtoFestivalBulkPreparationService preparation;
    @MockitoBean KtoFestivalBulkJobService jobs;
    @MockitoBean UserMapper users;
    @MockitoBean CustomLoginSuccessHandler login;
    @MockitoBean CustomLogoutSuccessHandler logout;
    private static final String BODY = "{\"contentId\":\"123\",\"eventYear\":2026,\"posterSelection\":\"MAIN\"}";

    @Test
    void previewRequiresAdminAndCsrfBeforePreparingData() throws Exception {
        mvc.perform(post("/admin/api/kto/festivals/bulk/preview").with(user("member").roles("USER")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/admin/api/kto/festivals/bulk/preview").with(user(admin()))
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        verifyNoInteractions(preparation, jobs);
    }

    @Test
    void adminCanPrepareAndStartOnlyValidatedInput() throws Exception {
        when(preparation.preview(any(), eq(7L))).thenReturn(new KtoFestivalBulkPreview("token", "123", 2026, "축제",
                null, null, null, null, null, "READY", List.of(), List.of()));
        mvc.perform(post("/admin/api/kto/festivals/bulk/preview").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/admin/api/kto/festivals/bulk/preview").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BODY.replace("2026", "1"))).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/api/kto/festivals/bulk/start").with(user(admin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + UUID.randomUUID() + "\",\"previewTokens\":[]}"))
                .andExpect(status().isBadRequest());
        verify(preparation, times(1)).preview(any(), eq(7L));
        verifyNoInteractions(jobs);
    }

    @Test
    void jobStatusUsesAuthenticatedOwnerAndPostCannotBypassCsrf() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobs.get(id, 7L)).thenReturn(new KtoFestivalBulkJob(id.toString(), "COMPLETE", 1, 1, 1, 0, 0, List.of()));
        mvc.perform(get("/admin/api/kto/festivals/bulk/jobs/" + id).with(user(admin())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processed").value(1));
        mvc.perform(post("/admin/api/kto/festivals/bulk/start").with(user(admin()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"requestId\":\"" + id + "\",\"previewTokens\":[\"token\"]}"))
                .andExpect(status().isForbidden());
        verify(jobs, never()).start(any(), any());
    }

    private CustomUserDetails admin() {
        User user = new User(); user.setId(7L); user.setUserRole(UserRole.ADMIN); user.setUserPassword("test");
        return new CustomUserDetails(user);
    }
}
