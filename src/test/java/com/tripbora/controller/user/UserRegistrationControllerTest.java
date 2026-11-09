package com.tripbora.controller.user;

import com.tripbora.security.AccountAbuseGuard;
import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.dto.RegistrationForm;
import com.tripbora.model.User;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.policy.SignupPolicyFixtures;
import com.tripbora.service.policy.SignupPolicyService;
import com.tripbora.service.user.RegistrationResult;
import com.tripbora.service.user.RegistrationValidationException;
import com.tripbora.service.user.PasswordPolicy;
import com.tripbora.service.user.UserService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserRegistrationControllerTest {
    @Test
    void renderedSnapshotIncludesUncheckedOptionalAndViewOnlyPolicies() throws Exception {
        var policies=SignupPolicyFixtures.activeSignupPolicies();
        when(signupPolicyService.loadSignupPolicies()).thenReturn(policies);
        String html=mockMvc.perform(get("/users/register")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document=org.jsoup.Jsoup.parse(html);
        assertThat(document.select("input[type=hidden][name=shownPolicyVersionIds]").eachAttr("value"))
                .containsExactlyElementsOf(policies.policies().stream().map(p->p.policyVersionId().toString()).toList());
        assertThat(document.select("input[name=agreedPolicyVersionIds][checked]")).isEmpty();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserController userController;
    // 요청 남용 제한은 이 화면 계약의 관심사가 아니라 통과시키는 가짜를 쓴다.
    @MockitoBean private AccountAbuseGuard accountAbuseGuard;
    @MockitoBean private UserService userService;
    @MockitoBean private SignupPolicyService signupPolicyService;
    @MockitoBean private UserMapper userMapper;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Test
    void successfulRegistrationStoresPendingSessionAndRedirectsToWaitingPage() throws Exception {
        when(userService.registerUser(any()))
                .thenReturn(new RegistrationResult("member@gmail.com", true));
        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(validRegistrationRequest("member@gmail.com").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/register/verify-waiting"))
                .andExpect(request().sessionAttribute(
                        EmailVerificationController.PENDING_EMAIL_SESSION_ATTRIBUTE,
                        "member@gmail.com"))
                .andExpect(flash().attribute("verificationMessageType", "success"));
    }

    @Test
    void mailFailureAfterSuccessfulInsertMovesToRecoverableWaitingPage() throws Exception {
        when(userService.registerUser(any()))
                .thenReturn(new RegistrationResult("member@gmail.com", false));

        mockMvc.perform(validRegistrationRequest("member@gmail.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/register/verify-waiting"))
                .andExpect(request().sessionAttribute(
                        EmailVerificationController.PENDING_EMAIL_SESSION_ATTRIBUTE,
                        "member@gmail.com"))
                .andExpect(flash().attribute("verificationMessageType", "error"))
                .andExpect(flash().attribute("verificationMessage",
                        "회원가입은 완료되었지만 인증메일 발송 중 문제가 발생했습니다. 잠시 후 다시 시도해주세요."));
    }

    @Test
    void invalidServerEmailReturnsFieldErrorWithoutCallingRegistration() throws Exception {
        mockMvc.perform(validRegistrationRequest("not-an-email"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors("registrationForm", "userEmail"));

        verify(userService, never()).registerUser(any());
    }

    @Test
    void failureBeforeRegistrationResultReturnsTheForm() throws Exception {
        when(userService.registerUser(any())).thenThrow(new IllegalStateException("save failed"));

        mockMvc.perform(validRegistrationRequest("member@gmail.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasErrors("registrationForm"));
    }

    @Test
    void postRegistrationSessionFailureNeverReturnsTheRegistrationForm() {
        RegistrationForm form = new RegistrationForm();
        // 연령 확인은 이 테스트들의 관심사가 아니므로 통과하는 값을 기본으로 둔다.
        form.setBirthDate("2000-01-01");
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(form, "registrationForm");
        HttpSession session = mock(HttpSession.class);
        RedirectAttributes redirectAttributes = mock(RedirectAttributes.class);
        when(userService.registerUser(form))
                .thenReturn(new RegistrationResult("member@gmail.com", true));
        doThrow(new IllegalStateException("session unavailable"))
                .when(session).setAttribute(
                        EmailVerificationController.PENDING_EMAIL_SESSION_ATTRIBUTE,
                        "member@gmail.com");

        String destination = userController.registerUser(
                form, bindingResult, null,
                new org.springframework.mock.web.MockHttpServletRequest(),
                new org.springframework.mock.web.MockHttpServletResponse(),
                session, redirectAttributes,
                new org.springframework.ui.ConcurrentModel());

        assertThat(destination).isEqualTo("redirect:/users/verification/resend");
    }

    /**
     * 필수 동의 판정은 화면이 아니라 서버가 현재 정책 세트로 한다.
     * 체크박스를 하나도 보내지 않아도 요청 자체는 서비스까지 가고, 거기에서 거절된다.
     */
    @Test
    void aMissingRequiredConsentIsRejectedByTheServerAndShownOnTheForm() throws Exception {
        when(userService.registerUser(any())).thenThrow(new RegistrationValidationException(
                "agreedPolicyVersionIds", "서비스 이용약관에 동의해주세요.",
                "signup.error.terms.service"));

        mockMvc.perform(multipart("/users/register")
                        .param("birthDate", "2000-01-01")
                        .param("userEmail", "member@gmail.com")
                        .param("userPassword", "Password!")
                        .param("passwordConfirm", "Password!")
                        .param("nickname", "여행자123"))
                .andExpect(status().isOk())
                .andExpect(view().name("register"))
                .andExpect(model().attributeHasFieldErrors(
                        "registrationForm", "agreedPolicyVersionIds"));
    }

    /** 화면을 그리는 모든 경로가 현재 정책 세트를 함께 담는다. */
    @Test
    void everyRegisterViewCarriesTheCurrentPolicySet() throws Exception {
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(SignupPolicyFixtures.activeSignupPolicies());

        mockMvc.perform(get("/users/register"))
                .andExpect(model().attributeExists("signupPolicies"));

        // 검증 실패로 되돌아오는 화면에도 약관 항목이 남아 있어야 한다.
        mockMvc.perform(validRegistrationRequest("not-an-email"))
                .andExpect(model().attributeExists("signupPolicies"));
    }

    @Test
    void legacyPersonalInformationParametersDoNotAffectRegistration() throws Exception {
        when(userService.registerUser(any()))
                .thenReturn(new RegistrationResult("member@gmail.com", true));

        mockMvc.perform(validRegistrationRequest("member@gmail.com")
                        .param("fullName", "김민준1")
                        .param("userPhone", "010-9999-9999")
                        .param("userBirth", "1995-05-10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/register/verify-waiting"));

        verify(userService).registerUser(any());
    }

    @Test
    void usernameRecoveryRouteIsRemoved() throws Exception {
        mockMvc.perform(get("/users/find-username"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/users/find-username").param("userEmail", "member@gmail.com"))
                .andExpect(status().isNotFound());
    }

    @Test
    void passwordRecoveryShowsTheGenericCompletionState() throws Exception {
        mockMvc.perform(post("/users/find-password")
                        .param("userEmail", "member@gmail.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/find-password"))
                .andExpect(flash().attribute("recoveryRequested", true));

        verify(userService).processResetPasswordRequest("member@gmail.com");
    }

    @Test
    void passwordRecoveryFailureUsesTheSameGenericCompletionState() throws Exception {
        doThrow(new IllegalStateException("recovery unavailable"))
                .when(userService).processResetPasswordRequest("member@gmail.com");

        mockMvc.perform(post("/users/find-password")
                        .param("userEmail", "member@gmail.com"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/find-password"))
                .andExpect(flash().attribute("recoveryRequested", true));
    }

    @Test
    void successfulPasswordResetRequiresConfirmationAndReturnsToLogin() throws Exception {
        mockMvc.perform(post("/users/reset-password")
                        .param("token", "safe-token")
                        .param("newPassword", "StrongPassword!")
                        .param("newPasswordConfirm", "StrongPassword!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?passwordChanged=true"));

        verify(userService).resetPassword(
                "safe-token", "StrongPassword!", "StrongPassword!");
    }

    @Test
    void resetPasswordPagePassesTheRawTokenThroughTheSharedValidationPath()
            throws Exception {
        when(userService.validateResetToken("safe-token")).thenReturn(new User());

        mockMvc.perform(get("/users/reset-password")
                        .param("token", "safe-token"))
                .andExpect(status().isOk())
                .andExpect(view().name("reset-password"))
                .andExpect(model().attribute("token", "safe-token"))
                .andExpect(model().attribute(
                        "passwordPolicyMessage", PasswordPolicy.INVALID_MESSAGE));

        verify(userService).validateResetToken("safe-token");
    }

    @Test
    void mismatchedPasswordResetReturnsToTheFormWithAnError() throws Exception {
        doThrow(new IllegalArgumentException(PasswordPolicy.MISMATCH_MESSAGE))
                .when(userService).resetPassword(
                        "safe-token", "StrongPassword!", "DifferentPassword!");

        mockMvc.perform(post("/users/reset-password")
                        .param("token", "safe-token")
                        .param("newPassword", "StrongPassword!")
                        .param("newPasswordConfirm", "DifferentPassword!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/users/reset-password?token=safe-token"))
                .andExpect(flash().attribute("error", PasswordPolicy.MISMATCH_MESSAGE));
    }

    @Test
    void invalidPasswordResetTokenKeepsTheExistingLoginErrorFlow() throws Exception {
        doThrow(new IllegalArgumentException(UserService.INVALID_RESET_TOKEN_MESSAGE))
                .when(userService).resetPassword(
                        "invalid-token", "StrongPassword!", "StrongPassword!");

        mockMvc.perform(post("/users/reset-password")
                        .param("token", "invalid-token")
                        .param("newPassword", "StrongPassword!")
                        .param("newPasswordConfirm", "StrongPassword!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=invalid_token"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
    validRegistrationRequest(String email) {
        return multipart("/users/register")
                .param("agreedPolicyVersionIds", "101")
                .param("agreedPolicyVersionIds", "103")
                .param("birthDate", "2000-01-01")
                .param("userEmail", email)
                .param("userPassword", "Password!")
                .param("passwordConfirm", "Password!")
                .param("nickname", "여행자123");
    }
}
