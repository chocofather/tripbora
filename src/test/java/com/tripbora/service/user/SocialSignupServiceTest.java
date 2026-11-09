package com.tripbora.service.user;

import com.tripbora.dto.SocialSignupForm;
import com.tripbora.model.PendingSocialSignup;
import com.tripbora.model.SocialAccount;
import com.tripbora.model.SocialProvider;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.user.SocialAccountMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.email.EmailVerificationService;
import com.tripbora.service.policy.PolicyConsentDecision;
import com.tripbora.service.policy.PolicyConsentRecorder;
import com.tripbora.service.policy.SignupPolicyFixtures;
import com.tripbora.service.policy.SignupPolicyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialSignupServiceTest {

    private static final String VERIFICATION_TOKEN = "verification-token";

    @Mock
    private UserMapper userMapper;
    @Mock
    private SocialAccountMapper socialAccountMapper;
    @Mock
    private WithdrawalGraceService withdrawalGraceService;
    @Mock
    private EmailVerificationService emailVerificationService;
    @Mock
    private SignupPolicyService signupPolicyService;
    @Mock
    private PolicyConsentRecorder policyConsentRecorder;

    private SocialSignupService service;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @SuppressWarnings("unchecked")
    void galleryChoiceUsesTheShownVersionAndSocialSignupSource(boolean agreed) {
        var policies = new java.util.ArrayList<>(SignupPolicyFixtures.activeSignupPolicies().policies());
        policies.add(SignupPolicyFixtures.policy(105L, com.tripbora.model.PolicyType.REVIEW_GALLERY,
                true, false));
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(new com.tripbora.service.policy.SignupPolicySet(policies));
        SocialSignupForm form = acceptedForm("여행자123");
        form.setShownPolicyVersionIds(policies.stream()
                .map(com.tripbora.service.policy.SignupPolicy::policyVersionId).toList());
        if (agreed) {
            form.setAgreedPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID,
                    SignupPolicyFixtures.PRIVACY_COLLECTION_ID, 105L));
        }
        arrangeGeneratedUserId(41L);

        service.complete(pending("google-sub", "new@example.com", true), form);

        ArgumentCaptor<List<PolicyConsentDecision>> decisions = ArgumentCaptor.forClass(List.class);
        verify(policyConsentRecorder).record(any(), decisions.capture(),
                org.mockito.ArgumentMatchers.eq(com.tripbora.model.PolicyConsentSource.SOCIAL_SIGNUP), any());
        assertThat(decisions.getValue()).contains(new PolicyConsentDecision(105L,
                com.tripbora.model.PolicyType.REVIEW_GALLERY, agreed));
    }

    @Test
    void anOptionalVersionChangeRejectsBeforeAnyUserSocialOrConsentSave() {
        SocialSignupForm form = acceptedForm("여행자123");
        form.setShownPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID,
                SignupPolicyFixtures.PRIVACY_COLLECTION_ID, SignupPolicyFixtures.PRIVACY_POLICY_ID, 999L));

        assertThatThrownBy(() -> service.complete(pending("google-sub", "new@example.com", true), form))
                .isInstanceOf(SocialSignupValidationException.class)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.changed");

        assertThat(form.getAgreedPolicyVersionIds()).isEmpty();
        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
        verifyNoInteractions(policyConsentRecorder, emailVerificationService);
    }

    @Test
    void missingCorePoliciesRejectBeforeAnySave() {
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(com.tripbora.service.policy.SignupPolicySet.empty());

        assertThatThrownBy(() -> service.complete(pending("google-sub", "new@example.com", true),
                acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupValidationException.class)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.unavailable");

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
        verifyNoInteractions(policyConsentRecorder, emailVerificationService);
    }

    @BeforeEach
    void setUp() {
        // 정책이 활성화된 뒤의 상태로 둔다. 일반 회원가입과 같은 세트를 쓴다.
        lenient().when(signupPolicyService.loadSignupPolicies())
                .thenReturn(SignupPolicyFixtures.activeSignupPolicies());
        // 이메일 상태 판정은 mock 이 아니라 실제 resolver 를 태운다. 계정 유무는 userMapper stub 이 정한다.
        service = new SocialSignupService(
                userMapper, socialAccountMapper,
                new SocialEmailAccountResolver(userMapper, withdrawalGraceService),
                emailVerificationService, signupPolicyService, policyConsentRecorder);
    }

    @Test
    void completionIsOneTransactionalBoundary() throws Exception {
        assertThat(SocialSignupService.class
                .getMethod("complete", PendingSocialSignup.class, SocialSignupForm.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void createsActiveUserAndGoogleConnectionFromServerPendingOnly() {
        arrangeGeneratedUserId(41L);
        SocialSignupForm form = acceptedForm(" 여행자123 ");

        SocialSignupOutcome outcome =
                service.complete(pending("google-sub", "new@example.com", true), form);

        assertThat(outcome.userId()).isEqualTo(41L);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        User user = userCaptor.getValue();
        assertThat(user.getUserPassword()).isNull();
        assertThat(user.getFullName()).isNull();
        assertThat(user.getUserBirth()).isNull();
        // Google 은 email_verified 를 함께 주므로 그 이메일이 곧 공식 이메일이다.
        assertThat(user.getUserEmail()).isEqualTo("new@example.com");
        assertThat(user.getNickname()).isEqualTo("여행자123");
        assertThat(user.getUserRole()).isEqualTo(UserRole.USER);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isNotNull();

        ArgumentCaptor<SocialAccount> accountCaptor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountMapper).insert(accountCaptor.capture());
        SocialAccount account = accountCaptor.getValue();
        assertThat(account.getUserId()).isEqualTo(41L);
        assertThat(account.getProvider()).isEqualTo(SocialProvider.GOOGLE);
        assertThat(account.getProviderUserId()).isEqualTo("google-sub");
        assertThat(account.getProviderEmail()).isEqualTo("new@example.com");
        assertThat(account.getProviderEmailVerified()).isTrue();
    }

    @Test
    void createsInactiveKakaoUserFromTheEnteredEmailAndIssuesAVerificationToken() {
        arrangeGeneratedUserId(52L);
        PendingSocialSignup pending = pending(
                SocialProvider.KAKAO, "kakao-sub", null, null);

        SocialSignupOutcome outcome = service.complete(
                pending, acceptedForm("카카오여행자", " Kakao@Example.com "));

        assertThat(outcome.userId()).isEqualTo(52L);
        assertThat(outcome.userEmail()).isEqualTo("kakao@example.com");
        assertThat(outcome.requiresEmailVerification()).isTrue();
        assertThat(outcome.verificationToken()).isEqualTo(VERIFICATION_TOKEN);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        User user = userCaptor.getValue();
        // provider 가 인증하지 않은 이메일이라 일반 회원가입과 같이 인증 대기로 만든다.
        assertThat(user.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(user.getUserEmail()).isEqualTo("kakao@example.com");
        assertThat(user.getUserPassword()).isNull();
        assertThat(user.getVerificationToken()).isEqualTo(VERIFICATION_TOKEN);

        ArgumentCaptor<SocialAccount> accountCaptor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountMapper).insert(accountCaptor.capture());
        SocialAccount account = accountCaptor.getValue();
        assertThat(account.getUserId()).isEqualTo(52L);
        assertThat(account.getProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(account.getProviderUserId()).isEqualTo("kakao-sub");
        assertThat(account.getProviderEmail()).isNull();
        assertThat(account.getProviderEmailVerified()).isNull();
        verify(socialAccountMapper).findByProviderAndProviderUserId(
                SocialProvider.KAKAO, "kakao-sub");
    }

    /** provider 이메일은 참조로만 남고, users 에는 사용자가 입력한 이메일이 들어간다. */
    @Test
    void keepsTheProviderEmailAsAReferenceEvenWhenTheMemberEntersADifferentAddress() {
        arrangeGeneratedUserId(61L);
        PendingSocialSignup pending = pending(
                SocialProvider.NAVER, "naver-id", "naver@example.com", null);

        service.complete(pending, acceptedForm("네이버여행자", "chosen@example.com"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        assertThat(userCaptor.getValue().getUserEmail()).isEqualTo("chosen@example.com");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.INACTIVE);
        ArgumentCaptor<SocialAccount> accountCaptor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountMapper).insert(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProviderEmail()).isEqualTo("naver@example.com");
        assertThat(accountCaptor.getValue().getProviderEmailVerified()).isNull();
    }

    /** Kakao 가 앞으로 verified 이메일을 주더라도 이번 단계에서는 신뢰하지 않는다. */
    @Test
    void aVerifiedKakaoProviderEmailIsStillNotTrustedAsTheOfficialEmail() {
        arrangeGeneratedUserId(53L);
        PendingSocialSignup pending = pending(
                SocialProvider.KAKAO, "kakao-sub-with-email", "kakao@example.com", true);

        SocialSignupOutcome outcome = service.complete(
                pending, acceptedForm("카카오여행자", "typed@example.com"));

        assertThat(outcome.requiresEmailVerification()).isTrue();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        assertThat(userCaptor.getValue().getUserEmail()).isEqualTo("typed@example.com");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.INACTIVE);
    }

    /** users 에는 정규화한 값을, social_accounts 에는 provider 가 준 표기를 그대로 남긴다. */
    @Test
    void storesTheVerifiedGoogleEmailAsTheOfficialUserEmailAndKeepsTheProviderReference() {
        arrangeGeneratedUserId(41L);

        SocialSignupOutcome outcome = service.complete(
                pending("sub", " New@Example.com ", true), acceptedForm("여행자123"));

        // Google 은 provider 가 이미 인증했으므로 추가 인증메일이 없다.
        assertThat(outcome.requiresEmailVerification()).isFalse();
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        assertThat(userCaptor.getValue().getUserEmail()).isEqualTo("new@example.com");
        assertThat(userCaptor.getValue().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(userCaptor.getValue().getVerificationToken()).isNull();
        ArgumentCaptor<SocialAccount> accountCaptor = ArgumentCaptor.forClass(SocialAccount.class);
        verify(socialAccountMapper).insert(accountCaptor.capture());
        assertThat(accountCaptor.getValue().getProviderEmail()).isEqualTo("New@Example.com");
        assertThat(accountCaptor.getValue().getProviderEmailVerified()).isTrue();
        verify(userMapper).findByEmail("new@example.com");
        verifyNoInteractions(emailVerificationService);
    }

    /** Kakao/Naver 는 이메일 없이 가입할 수 없다. */
    @ParameterizedTest
    @MethodSource("providersRequiringOwnEmailVerification")
    void refusesKakaoAndNaverSignupWithoutAnEnteredEmail(PendingSocialSignup pending) {
        assertValidation("userEmail", pending, acceptedForm("여행자123", "  "));
        assertValidation("userEmail", pending, acceptedForm("여행자123", null));

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    /** 형식이 우리 정책을 통과하지 못하면 가입시키지 않는다. */
    @ParameterizedTest
    @MethodSource("providersRequiringOwnEmailVerification")
    void refusesKakaoAndNaverSignupWithAMalformedEmail(PendingSocialSignup pending) {
        assertValidation("userEmail", pending, acceptedForm("여행자123", "not-an-email"));

        verify(userMapper, never()).insertUser(any());
    }

    /** 이미 가입된 이메일이면 새 users 도, social_accounts 연결도 만들지 않는다. */
    @ParameterizedTest
    @MethodSource("providersRequiringOwnEmailVerification")
    void refusesKakaoAndNaverSignupOnAnAlreadyRegisteredEmail(PendingSocialSignup pending) {
        when(userMapper.findByEmail("taken@example.com"))
                .thenReturn(userWithStatus(UserStatus.ACTIVE));

        assertValidation("userEmail", pending, acceptedForm("여행자123", "taken@example.com"));

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
        verifyNoInteractions(emailVerificationService);
    }

    /** 인증 대기·탈퇴 유예·휴면·제재 계정의 이메일로 우회 가입할 수 없다. */
    @ParameterizedTest
    @EnumSource(value = UserStatus.class,
            names = {"INACTIVE", "WITHDRAWAL_PENDING", "SUSPENDED", "RESTRICTED", "DEACTIVATED"})
    void refusesKakaoSignupOnAnEmailHeldByAnUnusableAccount(UserStatus status) {
        when(userMapper.findByEmail("held@example.com")).thenReturn(userWithStatus(status));

        assertValidation("userEmail",
                pending(SocialProvider.KAKAO, "kakao-sub", null, null),
                acceptedForm("여행자123", "held@example.com"));

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    static Stream<PendingSocialSignup> providersRequiringOwnEmailVerification() {
        return Stream.of(
                pending(SocialProvider.KAKAO, "kakao-sub", null, null),
                pending(SocialProvider.NAVER, "naver-id", "naver@example.com", null));
    }

    /** Google 인데 인증된 이메일이 없으면 이메일 없는 계정을 만들지 않고 흐름을 끝낸다. */
    @ParameterizedTest
    @MethodSource("googleStatesWithoutVerifiedEmail")
    void refusesGoogleSignupWithoutAVerifiedProviderEmail(PendingSocialSignup pending) {
        assertThatThrownBy(() -> service.complete(pending, acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupFlowException.class);

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    static Stream<PendingSocialSignup> googleStatesWithoutVerifiedEmail() {
        return Stream.of(
                pending("sub-false", "existing@example.com", false),
                pending("sub-unknown", "existing@example.com", null),
                pending("sub-missing", null, true),
                pending("sub-invalid", "not-an-email", true));
    }

    /** 같은 이메일의 users 가 이미 있으면 두 번째 계정을 만들지 않는다. */
    @Test
    void refusesToCreateASecondUserWhenTheGoogleEmailIsAlreadyTaken() {
        when(userMapper.findByEmail("existing@example.com")).thenReturn(new User());

        assertThatThrownBy(() -> service.complete(
                pending("different-google-sub", "existing@example.com", true),
                acceptedForm("새여행자")))
                .isInstanceOf(SocialSignupFlowException.class);

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    /** 사전 조회를 통과한 뒤 경합으로 UNIQUE 가 터져도 닉네임 오류로 오인하지 않는다. */
    @Test
    void emailUniqueRaceAtInsertIsReportedAsAFlowFailureNotANicknameError() {
        when(userMapper.findByEmail("new@example.com"))
                .thenReturn(null)
                .thenReturn(new User());
        doThrow(new DuplicateKeyException("user_email_UNIQUE"))
                .when(userMapper).insertUser(any());

        assertThatThrownBy(() -> service.complete(
                pending("sub", "new@example.com", true), acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupFlowException.class)
                .hasMessageNotContaining("user_email_UNIQUE");

        verify(socialAccountMapper, never()).insert(any());
    }

    @Test
    void rejectsMissingOrInvalidNicknameAndRequiredConsents() {
        assertValidation("nickname", pending("sub", null, null), acceptedForm(" "));
        assertValidation("nickname", pending("sub", null, null), acceptedForm("닉네임 공백"));

        SocialSignupForm noTerms = acceptedForm("여행자123");
        noTerms.setAgreedPolicyVersionIds(
                List.of(SignupPolicyFixtures.PRIVACY_COLLECTION_ID));
        assertValidation("agreedPolicyVersionIds", pending("sub", null, null), noTerms);

        SocialSignupForm noPrivacy = acceptedForm("여행자123");
        noPrivacy.setAgreedPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID));
        assertValidation("agreedPolicyVersionIds", pending("sub", null, null), noPrivacy);
    }

    @Test
    void rejectsNicknameAlreadyFoundBeforeInsert() {
        when(userMapper.countByNickname("여행자123")).thenReturn(1);

        assertValidation("nickname", pending("sub", null, null), acceptedForm("여행자123"));

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    @Test
    void rejectsExpiredInvalidOrAlreadyConnectedFlowBeforeCreatingUser() {
        PendingSocialSignup expired = new PendingSocialSignup(
                "flow", SocialProvider.GOOGLE, "sub", null, null,
                Instant.now().minusSeconds(700), Instant.now().minusSeconds(100));
        assertThatThrownBy(() -> service.complete(expired, acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupFlowException.class);

        SocialAccount connected = new SocialAccount();
        connected.setUserId(7L);
        when(socialAccountMapper.findByProviderAndProviderUserId(
                SocialProvider.GOOGLE, "connected-sub")).thenReturn(connected);
        assertThatThrownBy(() -> service.complete(
                pending("connected-sub", null, null), acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupFlowException.class);

        verify(userMapper, never()).insertUser(any());
    }

    @Test
    void nicknameUniqueRacePropagatesRuntimeFailureBeforeSocialInsert() {
        doThrow(new DuplicateKeyException("duplicate user"))
                .when(userMapper).insertUser(any());

        assertThatThrownBy(() -> service.complete(
                pending("sub", "new@example.com", true), acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupValidationException.class)
                .hasMessageNotContaining("duplicate user");

        verify(socialAccountMapper, never()).insert(any());
    }

    @Test
    void providerUniqueRaceThrowsAfterUserInsertSoTransactionRollsBackBothWrites() {
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(41L);
            return null;
        }).when(userMapper).insertUser(any());
        doThrow(new DuplicateKeyException("provider unique"))
                .when(socialAccountMapper).insert(any());

        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        SocialSignupService transactionalService = transactionalProxy(transactionManager);

        assertThatThrownBy(() -> transactionalService.complete(
                pending("sub", "new@example.com", true), acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupFlowException.class)
                .hasMessageNotContaining("provider unique");

        verify(userMapper).insertUser(any());
        assertThat(transactionManager.rolledBack).isTrue();
        assertThat(transactionManager.committed).isFalse();
    }


    /* ---------- 연령 확인 (만 14세) ---------- */

    /** Google/Kakao/Naver 어느 provider 든 연령 확인 전에는 users 가 만들어지지 않는다. */
    @ParameterizedTest
    @MethodSource("everySignupProvider")
    void noProviderCreatesAUserBeforeTheAgeCheckPasses(PendingSocialSignup pending) {
        SocialSignupForm underage = acceptedForm("여행자123", "new@example.com");
        underage.setBirthDate(
                java.time.LocalDate.now().minusYears(14).plusDays(1).toString());

        assertThatThrownBy(() -> service.complete(pending, underage))
                .isInstanceOfSatisfying(SocialSignupValidationException.class, exception -> {
                    assertThat(exception.getField()).isEqualTo("birthDate");
                    assertThat(exception.getMessageCode())
                            .isEqualTo("signup.error.birthDate.underage");
                });

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    /** 생년월일 누락·형식 오류·미래 날짜도 INSERT 전에 막힌다. */
    @ParameterizedTest
    @MethodSource("everySignupProvider")
    void aMissingMalformedOrFutureBirthDateBlocksTheSignup(PendingSocialSignup pending) {
        for (String raw : new String[]{null, "", "not-a-date",
                java.time.LocalDate.now().plusDays(1).toString()}) {
            SocialSignupForm form = acceptedForm("여행자123", "new@example.com");
            form.setBirthDate(raw);
            assertThatThrownBy(() -> service.complete(pending, form))
                    .isInstanceOf(SocialSignupValidationException.class);
        }

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
    }

    /** 14번째 생일 당일은 통과하고, 생년월일은 users 에 담기지 않는다. */
    @Test
    void theFourteenthBirthdayPassesAndTheBirthDateIsNeverStored() {
        arrangeGeneratedUserId(41L);
        SocialSignupForm form = acceptedForm("여행자123");
        form.setBirthDate(java.time.LocalDate.now().minusYears(14).toString());

        service.complete(pending("google-sub", "new@example.com", true), form);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        assertThat(userCaptor.getValue().getUserBirth()).isNull();
    }

    /** 7) 소셜 신규가입의 출처는 SOCIAL_SIGNUP 이고, 화면의 현재 locale 이 함께 남는다. */
    @Test
    void theSocialSignupSourceAndTheScreenLocaleAreStored() {
        arrangeGeneratedUserId(41L);
        SocialSignupForm form = acceptedForm("여행자123");

        org.springframework.context.i18n.LocaleContextHolder.setLocale(
                java.util.Locale.forLanguageTag("ja"));
        try {
            service.complete(pending("google-sub", "new@example.com", true), form);
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }

        verify(policyConsentRecorder).record(
                org.mockito.ArgumentMatchers.eq(41L), any(),
                org.mockito.ArgumentMatchers.eq(
                        com.tripbora.model.PolicyConsentSource.SOCIAL_SIGNUP),
                org.mockito.ArgumentMatchers.eq("ja"));
    }

    /**
     * 8) 실제 사용된 policy_version_id 가 그대로 남고, 선택 항목 거절도 결정으로 기록된다.
     * 5) 열람 전용 개인정보처리방침은 동의행을 만들지 않는다.
     */
    @Test
    @SuppressWarnings("unchecked")
    void everyConsentDecisionKeepsItsVersionIdAndTheViewOnlyPolicyIsSkipped() {
        arrangeGeneratedUserId(41L);

        service.complete(pending("google-sub", "new@example.com", true),
                acceptedForm("여행자123"));

        ArgumentCaptor<List<PolicyConsentDecision>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(policyConsentRecorder).record(any(), captor.capture(), any(), any());
        assertThat(captor.getValue())
                .extracting(PolicyConsentDecision::policyVersionId,
                        PolicyConsentDecision::agreed)
                .containsExactlyInAnyOrder(
                        org.assertj.core.api.Assertions.tuple(
                                SignupPolicyFixtures.TERMS_ID, true),
                        org.assertj.core.api.Assertions.tuple(
                                SignupPolicyFixtures.PRIVACY_COLLECTION_ID, true),
                        // 체크하지 않은 선택 항목도 거절 이력으로 남는다.
                        org.assertj.core.api.Assertions.tuple(
                                SignupPolicyFixtures.MARKETING_ID, false));
        assertThat(captor.getValue()).extracting(PolicyConsentDecision::policyVersionId)
                .doesNotContain(SignupPolicyFixtures.PRIVACY_POLICY_ID);
    }

    /** 9) 임의의 policy version id 로는 필수 동의를 대신할 수 없다. */
    @Test
    void aForgedPolicyVersionIdDoesNotSatisfyTheSocialSignupConsent() {
        SocialSignupForm form = acceptedForm("여행자123");
        form.setAgreedPolicyVersionIds(List.of(999_999L));

        assertThatThrownBy(() -> service.complete(
                pending("google-sub", "new@example.com", true), form))
                .isInstanceOf(SocialSignupValidationException.class);

        verify(userMapper, never()).insertUser(any());
        verify(socialAccountMapper, never()).insert(any());
        verifyNoInteractions(policyConsentRecorder);
    }

    /** 10) 동의 이력 저장이 실패하면 가입 저장 실패로 다뤄져 같은 트랜잭션이 되돌아간다. */
    @Test
    void aFailedConsentInsertFailsTheWholeSocialSignup() {
        arrangeGeneratedUserId(41L);
        doThrow(new com.tripbora.service.policy.PolicyConsentPersistenceException(
                "insert failed"))
                .when(policyConsentRecorder).record(any(), any(), any(), any());

        assertThatThrownBy(() -> service.complete(
                pending("google-sub", "new@example.com", true), acceptedForm("여행자123")))
                .isInstanceOf(SocialSignupPersistenceException.class);
    }

    static Stream<PendingSocialSignup> everySignupProvider() {
        return Stream.of(
                pending("google-sub", "new@example.com", true),
                pending(SocialProvider.KAKAO, "kakao-sub", null, null),
                pending(SocialProvider.NAVER, "naver-id", "naver@example.com", null));
    }

    private void arrangeGeneratedUserId(long userId) {
        doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(userId);
            return null;
        }).when(userMapper).insertUser(any());
        when(socialAccountMapper.insert(any())).thenReturn(1);
        // 토큰 발급은 일반 회원가입과 같은 서비스가 맡는다. 여기서는 값만 흉내 낸다.
        lenient().doAnswer(invocation -> {
            invocation.<User>getArgument(0).setVerificationToken(VERIFICATION_TOKEN);
            return null;
        }).when(emailVerificationService).initializeVerification(any());
    }

    private void assertValidation(String field, PendingSocialSignup pending,
                                  SocialSignupForm form) {
        assertThatThrownBy(() -> service.complete(pending, form))
                .isInstanceOfSatisfying(SocialSignupValidationException.class,
                        exception -> assertThat(exception.getField()).isEqualTo(field));
    }

    private SocialSignupForm acceptedForm(String nickname) {
        return acceptedForm(nickname, null);
    }

    private SocialSignupForm acceptedForm(String nickname, String userEmail) {
        SocialSignupForm form = new SocialSignupForm();
        // 연령 확인은 이 테스트들의 관심사가 아니므로 통과하는 값을 기본으로 둔다.
        form.setBirthDate("2000-01-01");
        form.setNickname(nickname);
        form.setUserEmail(userEmail);
        // 활성 정책 세트의 필수 동의 두 건. 선택 항목은 기본적으로 거절 상태다.
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());
        form.setShownPolicyVersionIds(SignupPolicyFixtures.activeSignupPolicies().policies().stream()
                .map(com.tripbora.service.policy.SignupPolicy::policyVersionId).toList());
        return form;
    }

    private User userWithStatus(UserStatus status) {
        User user = new User();
        user.setId(99L);
        user.setStatus(status);
        return user;
    }

    private static PendingSocialSignup pending(String sub, String email, Boolean verified) {
        return pending(SocialProvider.GOOGLE, sub, email, verified);
    }

    private static PendingSocialSignup pending(SocialProvider provider,
                                                String sub,
                                                String email,
                                                Boolean verified) {
        Instant now = Instant.now();
        return new PendingSocialSignup(
                "flow-123", provider, sub, email, verified,
                now.minusSeconds(10), now.plusSeconds(590));
    }

    private SocialSignupService transactionalProxy(
            RecordingTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource());
        ProxyFactory proxyFactory = new ProxyFactory(service);
        proxyFactory.addAdvice(interceptor);
        return (SocialSignupService) proxyFactory.getProxy();
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private boolean committed;
        private boolean rolledBack;

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            committed = true;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            rolledBack = true;
        }
    }
}
