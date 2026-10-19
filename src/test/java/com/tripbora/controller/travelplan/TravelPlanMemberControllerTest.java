package com.tripbora.controller.travelplan;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.travelplan.TravelPlanMemberService;
import com.tripbora.service.travelplan.TravelPlanValidationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 나가기 / 내보내기 엔드포인트.
 * 권한 판단은 Service 가 하고 Controller 는 돌아갈 자리만 정한다.
 */
@WebMvcTest(TravelPlanMemberController.class)
@Import(SecurityConfig.class)
class TravelPlanMemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TravelPlanMemberService travelPlanMemberService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void leavingIgnoresForgedUserAndMemberIdentifiers() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/leave").with(user(member())).with(csrf())
                        .param("userId", "999").param("memberId", "888").param("targetMemberId", "777"))
                .andExpect(redirectedUrl("/travel-plans"));
        verify(travelPlanMemberService).leave(7L, 42L);
    }

    @Test
    void leavingSendsTheUserBackToTheRoomList() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/leave")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 더 이상 참여자가 아니라 그 방에 남아 있을 수 없다
                .andExpect(redirectedUrl("/travel-plans"))
                .andExpect(flash().attribute("travelPlanMessage", "여행 계획에서 나왔어요."));

        verify(travelPlanMemberService).leave(7L, 42L);
    }

    @Test
    void anOwnerTryingToLeaveStaysInTheRoomWithAnExplanation() throws Exception {
        doThrow(new TravelPlanValidationException("role",
                "방장은 바로 나갈 수 없습니다. 먼저 다른 참여자에게 방장을 넘겨주세요."))
                .when(travelPlanMemberService).leave(anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/members/leave")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42"))
                .andExpect(flash().attribute("travelPlanError",
                        "방장은 바로 나갈 수 없습니다. 먼저 다른 참여자에게 방장을 넘겨주세요."));
    }

    @Test
    void removingPassesOnlyTheTargetMemberIdAndComesBackToThePlanner() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/13/remove")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42"))
                .andExpect(flash().attribute("travelPlanMessage", "참여자를 내보냈어요."));

        // 요청자는 로그인 정보에서, 대상은 URL 의 memberId 에서 온다
        verify(travelPlanMemberService).removeMember(7L, 42L, 13L);
    }

    @Test
    void aRefusedRemovalLooksLikeTheRoomDoesNotExist() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."))
                .when(travelPlanMemberService).removeMember(anyLong(), anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/members/13/remove")
                        .with(user(member())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void handingOverKeepsTheFormerOwnerOnTheSamePlanner() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/13/transfer-owner")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                // 넘긴 사람도 방에 그대로 남는다
                .andExpect(redirectedUrl("/travel-plans/42"))
                .andExpect(flash().attribute("travelPlanMessage", "방장을 넘겼어요."));

        // 요청자는 로그인 정보에서, 대상은 URL 의 memberId 에서 온다
        verify(travelPlanMemberService).transferOwnership(7L, 42L, 13L);
    }

    @Test
    void aRefusedHandoverLooksLikeTheRoomDoesNotExist() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."))
                .when(travelPlanMemberService).transferOwnership(anyLong(), anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/members/13/transfer-owner")
                        .with(user(member())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void allowingRejoinSaysItIsNotAnImmediateReturn() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/13/allow-rejoin")
                        .with(user(member())).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/travel-plans/42"))
                .andExpect(flash().attribute("travelPlanMessage",
                        "다시 참여할 수 있게 했어요. 본인이 초대 링크로 들어오면 참여자가 됩니다."));

        // 대상은 URL 의 memberId 뿐이고 요청자는 로그인 정보에서 온다
        verify(travelPlanMemberService).allowRejoin(7L, 42L, 13L);
    }

    @Test
    void aRefusedAllowLooksLikeTheRoomDoesNotExist() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "여행계획을 찾을 수 없습니다."))
                .when(travelPlanMemberService).allowRejoin(anyLong(), anyLong(), anyLong());

        mockMvc.perform(post("/travel-plans/42/members/13/allow-rejoin")
                        .with(user(member())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void everyMemberActionIsCsrfProtected() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/leave").with(user(member())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/members/13/remove").with(user(member())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/members/13/transfer-owner").with(user(member())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/travel-plans/42/members/13/allow-rejoin").with(user(member())))
                .andExpect(status().isForbidden());

        verifyNothingHappened();
    }

    @Test
    void anonymousRequestsNeverReachTheService() throws Exception {
        mockMvc.perform(post("/travel-plans/42/members/leave").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/travel-plans/42/members/13/remove").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/travel-plans/42/members/13/transfer-owner").with(csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/travel-plans/42/members/13/allow-rejoin").with(csrf()))
                .andExpect(status().is3xxRedirection());

        verifyNothingHappened();
    }

    private void verifyNothingHappened() {
        verify(travelPlanMemberService, never()).leave(anyLong(), anyLong());
        verify(travelPlanMemberService, never()).removeMember(anyLong(), anyLong(), anyLong());
        verify(travelPlanMemberService, never())
                .transferOwnership(anyLong(), anyLong(), anyLong());
        verify(travelPlanMemberService, never()).allowRejoin(anyLong(), anyLong(), anyLong());
    }

    private CustomUserDetails member() {
        User user = new User();
        user.setId(7L);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        return new CustomUserDetails(user);
    }
}
