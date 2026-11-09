package com.tripbora.service.user;

import com.tripbora.security.InMemoryAccountAbuseGuard;
import com.tripbora.dto.RegistrationForm;
import com.tripbora.model.PolicyConsentSource;
import com.tripbora.model.PolicyType;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.email.EmailDispatchService;
import com.tripbora.service.email.EmailVerificationService;
import com.tripbora.service.policy.PolicyConsentDecision;
import com.tripbora.service.policy.PolicyConsentPersistenceException;
import com.tripbora.service.policy.PolicyConsentRecorder;
import com.tripbora.service.policy.SignupPolicyFixtures;
import com.tripbora.service.policy.SignupPolicyService;
import com.tripbora.service.policy.SignupPolicySet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceRegistrationTest {

    @Mock private UserMapper userMapper;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailDispatchService emailDispatchService;
    @Mock private EmailVerificationService emailVerificationService;
    @Mock private SignupPolicyService signupPolicyService;
    @Mock private PolicyConsentRecorder policyConsentRecorder;

    private UserService userService;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void galleryChoiceUsesTheShownVersionAndSignupSource(boolean agreed) {
        var policies = new java.util.ArrayList<>(SignupPolicyFixtures.activeSignupPolicies().policies());
        policies.add(SignupPolicyFixtures.policy(105L, PolicyType.REVIEW_GALLERY, true, false));
        when(signupPolicyService.loadSignupPolicies()).thenReturn(new SignupPolicySet(policies));
        RegistrationForm form = validForm();
        form.setShownPolicyVersionIds(policies.stream()
                .map(com.tripbora.service.policy.SignupPolicy::policyVersionId).toList());
        if (agreed) {
            form.setAgreedPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID,
                    SignupPolicyFixtures.PRIVACY_COLLECTION_ID, 105L));
        }

        userService.registerUser(form);

        assertThat(decisionFor(recordedDecisions(), PolicyType.REVIEW_GALLERY))
                .isEqualTo(new PolicyConsentDecision(105L, PolicyType.REVIEW_GALLERY, agreed));
    }

    @Test
    void anOptionalVersionChangeRejectsTheWholeSignupWithoutRecordingAnUnseenRefusal() {
        RegistrationForm form = validForm();
        form.setShownPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID,
                SignupPolicyFixtures.PRIVACY_COLLECTION_ID, SignupPolicyFixtures.PRIVACY_POLICY_ID, 999L));

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.changed");

        assertThat(form.getAgreedPolicyVersionIds()).isEmpty();
        verify(userMapper, never()).insertUser(any());
        verifyNoInteractions(policyConsentRecorder, passwordEncoder, emailVerificationService);
    }

    @Test
    void unavailableRequiredPoliciesNeverCreateAUser() {
        when(signupPolicyService.loadSignupPolicies()).thenReturn(SignupPolicySet.empty());
        assertThatThrownBy(() -> userService.registerUser(validForm()))
                .isInstanceOf(RegistrationValidationException.class)
                .hasFieldOrPropertyWithValue("field", "agreedPolicyVersionIds");
        verify(userMapper, never()).insertUser(any());
        verifyNoInteractions(policyConsentRecorder);
    }

    @BeforeEach
    void setUp() {
        // 정상 가입에는 활성 필수 정책과 화면에 표시한 버전이 모두 필요하다.
        lenient().when(signupPolicyService.loadSignupPolicies())
                .thenReturn(SignupPolicyFixtures.activeSignupPolicies());
        // MyBatis 가 useGeneratedKeys 로 채워 주는 id 를 흉내 낸다. 동의 이력이 이 id 를 쓴다.
        lenient().doAnswer(invocation -> {
            invocation.<User>getArgument(0).setId(41L);
            return null;
        }).when(userMapper).insertUser(any());
        userService = new UserService(userMapper, passwordEncoder, emailDispatchService,
                emailVerificationService, signupPolicyService,
                new RegistrationTransactionService(userMapper, policyConsentRecorder),
                // 테스트마다 새 guard 라 첫 요청은 늘 지나간다. (cooldown 은 이 클래스의 관심사가 아니다)
                new InMemoryAccountAbuseGuard());
    }

    /** 정책이 활성화된 뒤의 가입. 화면이 보낸 동의 id 를 서버가 다시 판정한다. */
    private void policiesAreActive() {
        when(signupPolicyService.loadSignupPolicies())
                .thenReturn(SignupPolicyFixtures.activeSignupPolicies());
    }

    @SuppressWarnings("unchecked")
    private List<PolicyConsentDecision> recordedDecisions() {
        ArgumentCaptor<List<PolicyConsentDecision>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(policyConsentRecorder).record(
                any(), captor.capture(), eq(PolicyConsentSource.SIGNUP), any());
        return captor.getValue();
    }

    private PolicyConsentDecision decisionFor(List<PolicyConsentDecision> decisions,
                                              PolicyType type) {
        return decisions.stream()
                .filter(decision -> decision.type() == type)
                .findFirst()
                .orElse(null);
    }

    /** 1) 이용약관에 동의하지 않으면 회원이 만들어지지 않는다. */
    @Test
    void aMissingServiceTermsConsentNeverReachesTheInsert() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(
                List.of(SignupPolicyFixtures.PRIVACY_COLLECTION_ID));

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.service");

        verify(userMapper, never()).insertUser(any());
        verifyNoInteractions(policyConsentRecorder);
    }

    /** 2) 개인정보 수집·이용에 동의하지 않으면 회원이 만들어지지 않는다. */
    @Test
    void aMissingPrivacyCollectionConsentNeverReachesTheInsert() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(List.of(SignupPolicyFixtures.TERMS_ID));

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.privacy");

        verify(userMapper, never()).insertUser(any());
        verifyNoInteractions(policyConsentRecorder);
    }

    /** 3) 선택 항목을 거절해도 가입은 되고, 거절했다는 사실이 agreed = false 로 남는다. */
    @Test
    void decliningTheOptionalMarketingConsentStillCompletesSignupAndIsRecorded() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());

        userService.registerUser(form);

        verify(userMapper).insertUser(any());
        List<PolicyConsentDecision> decisions = recordedDecisions();
        assertThat(decisionFor(decisions, PolicyType.MARKETING_EMAIL))
                .isNotNull()
                .extracting(PolicyConsentDecision::agreed)
                .isEqualTo(false);
    }

    /** 4) 선택 항목에 동의하면 agreed = true 로 남는다. */
    @Test
    void acceptingTheOptionalMarketingConsentIsRecordedAsAgreed() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(List.of(
                SignupPolicyFixtures.TERMS_ID,
                SignupPolicyFixtures.PRIVACY_COLLECTION_ID,
                SignupPolicyFixtures.MARKETING_ID));

        userService.registerUser(form);

        assertThat(decisionFor(recordedDecisions(), PolicyType.MARKETING_EMAIL).agreed()).isTrue();
    }

    /**
     * 5) 개인정보처리방침은 requires_consent = 0 이라 동의행을 만들지 않는다.
     * 8) 나머지 세 건은 실제로 사용된 policy_version_id 그대로 남는다.
     */
    @Test
    void theViewOnlyPrivacyPolicyGetsNoConsentRowAndTheOthersKeepTheirVersionIds() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());

        userService.registerUser(form);

        List<PolicyConsentDecision> decisions = recordedDecisions();
        assertThat(decisions).extracting(PolicyConsentDecision::type)
                .containsExactlyInAnyOrder(PolicyType.TERMS_OF_SERVICE,
                        PolicyType.PRIVACY_COLLECTION, PolicyType.MARKETING_EMAIL)
                .doesNotContain(PolicyType.PRIVACY_POLICY);
        assertThat(decisions).extracting(PolicyConsentDecision::policyVersionId)
                .containsExactlyInAnyOrder(
                        SignupPolicyFixtures.TERMS_ID,
                        SignupPolicyFixtures.PRIVACY_COLLECTION_ID,
                        SignupPolicyFixtures.MARKETING_ID);
    }

    /** 6) 일반가입의 출처는 SIGNUP 이고, locale 은 가입 화면의 현재 언어다. */
    @Test
    void theSignupSourceAndTheScreenLocaleAreStored() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());

        LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag("zh-TW"));
        try {
            userService.registerUser(form);
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }

        verify(policyConsentRecorder).record(
                any(), any(), eq(PolicyConsentSource.SIGNUP), eq("zh-TW"));
    }

    /**
     * 9) 화면이 임의의 policy version id 를 보내도 서버의 현재 정책 세트가 기준이다.
     * 모르는 id 는 버려지므로 필수 동의를 대신하지 못하고, 동의행으로도 남지 않는다.
     */
    @Test
    void aForgedPolicyVersionIdNeitherSatisfiesNorCreatesAConsent() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(List.of(999_999L, 1L));

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class);

        verify(userMapper, never()).insertUser(any());
        verifyNoInteractions(policyConsentRecorder);
    }

    /** 10) 동의 이력 저장이 실패하면 예외가 그대로 올라가 같은 트랜잭션의 회원 생성도 되돌아간다. */
    @Test
    void aFailedConsentInsertAbortsTheRegistration() {
        policiesAreActive();
        RegistrationForm form = validForm();
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());
        doThrow(new PolicyConsentPersistenceException("insert failed"))
                .when(policyConsentRecorder).record(any(), any(), any(), any());

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(PolicyConsentPersistenceException.class);

        // 같은 트랜잭션이라 커밋되지 않는다. 인증메일도 나가지 않는다.
        verify(emailVerificationService, never()).requestInitialVerification(any());
    }

    @Test
    void registrationNormalizesEmailAndStoresOnlyServerControlledAccountState() {
        RegistrationForm form = validForm();
        form.setUserEmail("  MEMBER@GMAIL.COM  ");
        when(passwordEncoder.encode("Password!")).thenReturn("encoded");
        when(emailVerificationService.requestInitialVerification(any())).thenReturn(true);
        doAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setVerificationToken("generated-token");
            user.setVerificationTokenExp(LocalDateTime.of(2026, 8, 14, 10, 0));
            user.setVerificationRequestedAt(LocalDateTime.of(2026, 8, 13, 10, 0));
            return null;
        }).when(emailVerificationService).initializeVerification(any());

        RegistrationResult result = userService.registerUser(form);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insertUser(userCaptor.capture());
        User stored = userCaptor.getValue();
        assertThat(stored.getUserEmail()).isEqualTo("member@gmail.com");
        assertThat(stored.getFullName()).isNull();
        assertThat(stored.getUserPhone()).isNull();
        assertThat(stored.getUserBirth()).isNull();
        assertThat(stored.getUserPassword()).isEqualTo("encoded");
        assertThat(stored.getStatus()).isEqualTo(UserStatus.INACTIVE);
        assertThat(stored.getUserRole()).isEqualTo(UserRole.USER);
        assertThat(stored.getVerificationToken()).isEqualTo("generated-token");
        assertThat(stored.getProfileImage()).isEqualTo("uploads/default.png");
        assertThat(result.email()).isEqualTo("member@gmail.com");
        assertThat(result.verificationEmailRequested()).isTrue();
    }

    @Test
    void mailFailureKeepsTheInsertedInactiveUserAndReturnsRecoverableResult() {
        RegistrationForm form = validForm();
        when(passwordEncoder.encode("Password!")).thenReturn("encoded");
        when(emailVerificationService.requestInitialVerification(any())).thenReturn(false);

        RegistrationResult result = userService.registerUser(form);

        InOrder order = inOrder(userMapper, emailVerificationService);
        order.verify(userMapper).insertUser(any());
        order.verify(emailVerificationService).requestInitialVerification(any());
        assertThat(result.verificationEmailRequested()).isFalse();
    }

    @Test
    void duplicateEmailIsRejectedBeforePasswordEncodingOrInsert() {
        RegistrationForm form = validForm();
        when(userMapper.findByEmail("member@gmail.com")).thenReturn(new User());

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .extracting(exception -> ((RegistrationValidationException) exception).getField())
                .isEqualTo("userEmail");

        verify(passwordEncoder, never()).encode(any());
        verify(userMapper, never()).insertUser(any());
    }

    @Test
    void duplicateNicknameIsRejectedByTheFinalServerCheck() {
        RegistrationForm form = validForm();
        when(userMapper.countByNickname("여행자123")).thenReturn(1);

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .extracting(exception -> ((RegistrationValidationException) exception).getField())
                .isEqualTo("nickname");

        verify(userMapper, never()).insertUser(any());
    }

    @Test
    void databaseUniquenessRaceIsConvertedToSafeRegistrationError() {
        RegistrationForm form = validForm();
        when(passwordEncoder.encode("Password!")).thenReturn("encoded");
        doThrow(new DuplicateKeyException("duplicate")).when(userMapper).insertUser(any());

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOf(RegistrationValidationException.class)
                .hasMessage("이미 사용 중인 회원가입 정보가 있습니다.");

        verify(emailVerificationService, never()).requestInitialVerification(any());
    }


    /** 만 14세 미만은 users 를 만들지 않는다. 우회 POST 도 여기서 막힌다. */
    @Test
    void anUnderageApplicantNeverReachesTheInsert() {
        RegistrationForm form = validForm();
        form.setBirthDate(java.time.LocalDate.now().minusYears(14).plusDays(1).toString());

        assertThatThrownBy(() -> userService.registerUser(form))
                .isInstanceOfSatisfying(RegistrationValidationException.class, exception -> {
                    assertThat(exception.getField()).isEqualTo("birthDate");
                    assertThat(exception.getMessageCode())
                            .isEqualTo("signup.error.birthDate.underage");
                });

        verify(userMapper, never()).insertUser(any());
    }

    /** 생년월일을 아예 빼고 POST 해도 통과하지 못한다. */
    @Test
    void aMissingOrMalformedBirthDateNeverReachesTheInsert() {
        RegistrationForm missing = validForm();
        missing.setBirthDate(null);
        assertThatThrownBy(() -> userService.registerUser(missing))
                .isInstanceOf(RegistrationValidationException.class);

        RegistrationForm malformed = validForm();
        malformed.setBirthDate("not-a-date");
        assertThatThrownBy(() -> userService.registerUser(malformed))
                .isInstanceOf(RegistrationValidationException.class);

        verify(userMapper, never()).insertUser(any());
    }

    /** 14번째 생일 당일은 통과하고, 생년월일은 users 에 담기지 않는다. */
    @Test
    void theFourteenthBirthdayPassesAndTheBirthDateIsNeverStored() {
        RegistrationForm form = validForm();
        form.setBirthDate(java.time.LocalDate.now().minusYears(14).toString());

        userService.registerUser(form);

        org.mockito.ArgumentCaptor<com.tripbora.model.User> captor =
                org.mockito.ArgumentCaptor.forClass(com.tripbora.model.User.class);
        verify(userMapper).insertUser(captor.capture());
        assertThat(captor.getValue().getUserBirth()).isNull();
    }

    private RegistrationForm validForm() {
        RegistrationForm form = new RegistrationForm();
        // 연령 확인은 이 테스트들의 관심사가 아니므로 통과하는 값을 기본으로 둔다.
        form.setBirthDate("2000-01-01");
        form.setUserEmail("member@gmail.com");
        form.setUserPassword("Password!");
        form.setPasswordConfirm("Password!");
        form.setNickname("여행자123");
        form.setShownPolicyVersionIds(SignupPolicyFixtures.activeSignupPolicies().policies().stream()
                .map(com.tripbora.service.policy.SignupPolicy::policyVersionId).toList());
        form.setAgreedPolicyVersionIds(SignupPolicyFixtures.requiredConsentIds());
        return form;
    }
}
