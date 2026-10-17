package com.tripbora.controller.travelplan;

import com.tripbora.config.*;
import com.tripbora.model.TravelPlanDay;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TravelPlanController.class)
@Import(SecurityConfig.class)
class TravelPlanDayTitleControllerTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean TravelPlanService travelPlanService;
    @MockitoBean TravelPlanInvitationService travelPlanInvitationService;
    @MockitoBean TravelPlanFinalReadService travelPlanFinalReadService;
    @MockitoBean TravelPlanFinalDeleteService travelPlanFinalDeleteService;
    @MockitoBean TravelPlanDeleteService travelPlanDeleteService;
    @MockitoBean CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean UserMapper userMapper;

    private CustomUserDetails member() {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        return new CustomUserDetails(user);
    }

    @Test void savedTitleAndVersionAreReturnedWithoutReplacingTheDay() throws Exception {
        TravelPlanDay day = new TravelPlanDay();
        day.setTitle("Paris");
        day.setTitleVersion(3);
        when(travelPlanService.updateDayTitle(7L, 42L, 100L, "Paris", 2)).thenReturn(day);
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(user(member())).with(csrf())
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"title\":\"Paris\",\"titleVersion\":3}"));
    }

    @Test void csrfAndAuthenticationAreRequired() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(user(member()))
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(csrf())
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().is3xxRedirection());
        verifyNoInteractions(travelPlanService);
    }

    @Test void staleVersionReturnsConflict() throws Exception {
        when(travelPlanService.updateDayTitle(7L, 42L, 100L, "Paris", 2))
                .thenThrow(new TravelPlanConflictException());
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(user(member())).with(csrf())
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test void invalidTitleReturnsBadRequest() throws Exception {
        when(travelPlanService.updateDayTitle(7L, 42L, 100L, "Paris", 2))
                .thenThrow(new TravelPlanValidationException("title", "invalid title"));
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(user(member())).with(csrf())
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("invalid title"));
    }

    @Test void foreignDayOrNonMemberFailureIsPreserved() throws Exception {
        when(travelPlanService.updateDayTitle(anyLong(), anyLong(), anyLong(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockMvc.perform(post("/travel-plans/42/days/999/title").with(user(member())).with(csrf())
                        .param("title", "Paris").param("titleVersion", "2"))
                .andExpect(status().isNotFound());
    }

    @Test void malformedVersionCannotReachService() throws Exception {
        mockMvc.perform(post("/travel-plans/42/days/100/title").with(user(member())).with(csrf())
                        .param("title", "Paris").param("titleVersion", "invalid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(travelPlanService);
    }
}
