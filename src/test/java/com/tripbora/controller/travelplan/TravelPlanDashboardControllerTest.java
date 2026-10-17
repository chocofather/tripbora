package com.tripbora.controller.travelplan;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.TravelPlanDashboardDto;
import com.tripbora.model.TravelPlanDashboard;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanDashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TravelPlanDashboardController.class)
@Import(SecurityConfig.class)
class TravelPlanDashboardControllerTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private TravelPlanDashboardService service;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void unauthenticatedReadRequiresLogin() throws Exception {
        mvc.perform(get("/travel-plans/42/dashboard")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(service);
    }

    @Test
    void mutationsRequireCsrfBeforeServiceIsCalled() throws Exception {
        mvc.perform(post("/travel-plans/42/dashboard/checklist").with(user(member()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"여권\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void mutationsReturnTheRefreshedDashboardAndUseAuthenticatedUser() throws Exception {
        when(service.addExpense(eq(7L), eq(42L), eq("무료"), eq(BigDecimal.ZERO), eq("")))
                .thenReturn(new TravelPlanDashboardDto(List.of(), new TravelPlanDashboard.Notice(),
                        List.of(), BigDecimal.ZERO, "KRW"));
        mvc.perform(post("/travel-plans/42/dashboard/expenses").with(user(member())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"무료\",\"amount\":\"0\",\"memo\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.notice.version").value(0));
        verify(service).addExpense(7L, 42L, "무료", BigDecimal.ZERO, "");
    }

    @Test
    void conflictsAndValidationErrorsAreJson() throws Exception {
        when(service.saveNotice(7L, 42L, "공지", 0L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "다른 참여자가 변경했습니다."));
        mvc.perform(post("/travel-plans/42/dashboard/notice").with(user(member())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"공지\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").exists());
        mvc.perform(post("/travel-plans/42/dashboard/expenses").with(user(member())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").exists());
        verify(service, never()).addExpense(any(), any(), any(), any(), any());
    }

    @Test
    void moneyIsSerializedWithoutJavascriptNumberPrecisionLoss() throws Exception {
        var expense = new TravelPlanDashboard.Expense();
        expense.setId(1L);
        expense.setAmount(new BigDecimal("9999999999999.99"));
        when(service.read(7L, 42L)).thenReturn(new TravelPlanDashboardDto(List.of(),
                new TravelPlanDashboard.Notice(), List.of(expense), new BigDecimal("123456789012345678.90"), "KRW"));
        mvc.perform(get("/travel-plans/42/dashboard").with(user(member())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value("123456789012345678.90"))
                .andExpect(jsonPath("$.expenses[0].amount").value("9999999999999.99"));
    }

    private CustomUserDetails member() {
        var member = new User();
        member.setId(7L);
        member.setUserRole(UserRole.USER);
        return new CustomUserDetails(member);
    }
}
