package com.tripbora.service.user;

import com.tripbora.dto.SocialSignupForm;
import com.tripbora.model.PendingSocialSignup;
import com.tripbora.model.PolicyConsentSource;
import com.tripbora.model.SocialAccount;
import com.tripbora.model.SocialProvider;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.user.SocialAccountMapper;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.email.EmailVerificationService;
import com.tripbora.service.policy.PolicyConsentDecision;
import com.tripbora.service.policy.PolicyConsentPersistenceException;
import com.tripbora.service.policy.PolicyConsentRecorder;
import com.tripbora.service.policy.PolicyConsentRequiredException;
import com.tripbora.service.policy.PolicySnapshotException;
import com.tripbora.service.policy.SignupPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SocialSignupService {

    private static final String DUPLICATE_NICKNAME_MESSAGE =
            "이미 사용 중인 닉네임입니다.";

    private final UserMapper userMapper;
    private final SocialAccountMapper socialAccountMapper;
    private final SocialEmailAccountResolver socialEmailAccountResolver;
    private final EmailVerificationService emailVerificationService;
    private final SignupPolicyService signupPolicyService;
    private final PolicyConsentRecorder policyConsentRecorder;

    /**
     * 가입 트랜잭션. users 와 social_accounts 를 함께 쓰고, Tripbora 이메일 인증이 필요한
     * provider 면 인증 토큰 컬럼까지 같은 INSERT 에 담는다.
     *
     * <p>인증메일 발송은 커밋 뒤에 {@link #sendVerificationEmail} 로 따로 한다.
     * 일반 회원가입과 마찬가지로 발송이 실패해도 가입 자체는 남는다.
     */
    @Transactional
    public SocialSignupOutcome complete(PendingSocialSignup pending, SocialSignupForm form) {
        validatePending(pending);

        if (socialAccountMapper.findByProviderAndProviderUserId(
                pending.provider(), pending.providerUserId()) != null) {
            throw new SocialSignupFlowException("이미 처리된 소셜 가입 정보입니다.");
        }

        // 신규 users 를 만드는 경로다. Google/Kakao/Naver 모두 여기를 지나므로
        // 어떤 provider 든 연령 확인 전에는 INSERT 가 일어나지 않는다.
        verifyAge(form);

        // 일반 회원가입과 같은 정책 세트를 같은 방식으로 검증한다.
        // 기존 계정 로그인/연결은 이 메서드를 지나지 않으므로 동의를 다시 받지 않는다.
        String consentLocale = SignupPolicyService.currentLocaleTag();
        List<PolicyConsentDecision> consentDecisions = resolveConsentDecisions(form);

        String nickname = validateForm(form);
        if (userMapper.countByNickname(nickname) > 0) {
            throw new SocialSignupValidationException(
                    "nickname", DUPLICATE_NICKNAME_MESSAGE,
                    "signup.error.nickname.duplicate");
        }

        String userEmail = resolveUserEmail(pending, form);
        // 가입 화면을 여는 사이 같은 이메일 계정이 생겼을 수 있다. UNIQUE 예외를 기다리지 않고 먼저 본다.
        if (userEmail != null && userMapper.findByEmail(userEmail) != null) {
            throw new SocialSignupFlowException("이미 같은 이메일로 가입된 계정이 있습니다.");
        }

        // Google 은 provider 가 인증한 이메일이라 바로 ACTIVE 다.
        // Kakao/Naver 는 일반 회원가입과 같이 INACTIVE 로 두고 인증 링크를 기다린다.
        boolean verifiedByProvider = pending.provider() == SocialProvider.GOOGLE;

        User user = new User();
        user.setNickname(nickname);
        user.setUserEmail(userEmail);
        user.setUserRole(UserRole.USER);
        user.setStatus(verifiedByProvider ? UserStatus.ACTIVE : UserStatus.INACTIVE);
        user.setCreatedAt(Timestamp.from(Instant.now()));
        if (!verifiedByProvider) {
            emailVerificationService.initializeVerification(user);
        }

        try {
            userMapper.insertUser(user);
        } catch (DataIntegrityViolationException exception) {
            // user_email 도 UNIQUE 라 닉네임이 아니라 이메일 경합일 수 있다.
            if (userEmail != null && userMapper.findByEmail(userEmail) != null) {
                throw new SocialSignupFlowException("이미 같은 이메일로 가입된 계정이 있습니다.");
            }
            throw new SocialSignupValidationException(
                    "nickname", DUPLICATE_NICKNAME_MESSAGE,
                    "signup.error.nickname.duplicate");
        }
        if (user.getId() == null) {
            throw new SocialSignupPersistenceException("회원 정보를 저장하지 못했습니다.");
        }

        SocialAccount socialAccount = new SocialAccount();
        socialAccount.setUserId(user.getId());
        socialAccount.setProvider(pending.provider());
        socialAccount.setProviderUserId(pending.providerUserId());
        socialAccount.setProviderEmail(normalizeReferenceEmail(pending.providerEmail()));
        socialAccount.setProviderEmailVerified(pending.providerEmailVerified());

        try {
            if (socialAccountMapper.insert(socialAccount) != 1) {
                throw new SocialSignupPersistenceException("소셜 계정을 연결하지 못했습니다.");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new SocialSignupFlowException("이미 처리된 소셜 가입 정보입니다.");
        }

        // 같은 트랜잭션이다. 동의 이력을 남기지 못하면 users 와 social_accounts 도 되돌아간다.
        try {
            policyConsentRecorder.record(user.getId(), consentDecisions,
                    PolicyConsentSource.SOCIAL_SIGNUP, consentLocale);
        } catch (PolicyConsentPersistenceException exception) {
            throw new SocialSignupPersistenceException(exception.getMessage());
        }
        return new SocialSignupOutcome(
                user.getId(), userEmail,
                verifiedByProvider ? null : user.getVerificationToken());
    }

    /**
     * 인증메일 발송. 트랜잭션이 끝난 뒤에 부른다. 발송용 메서드가 @Async 라 커밋 전에 부르면
     * 아직 없는 회원에게 인증 링크를 보낼 수 있다.
     *
     * @return 일반 회원가입과 같이 발송을 요청했는지 여부. 실패해도 가입은 되돌리지 않는다.
     */
    public boolean sendVerificationEmail(SocialSignupOutcome outcome) {
        if (!outcome.requiresEmailVerification()) {
            return false;
        }
        User user = new User();
        user.setId(outcome.userId());
        user.setUserEmail(outcome.userEmail());
        user.setVerificationToken(outcome.verificationToken());
        return emailVerificationService.requestInitialVerification(user);
    }

    private void validatePending(PendingSocialSignup pending) {
        Instant now = Instant.now();
        if (pending == null
                || !isSupportedProvider(pending.provider())
                || isBlank(pending.flowId())
                || isBlank(pending.providerUserId())
                || pending.createdAt() == null
                || pending.isExpired(now)) {
            throw new SocialSignupFlowException("소셜 로그인 정보가 만료되었습니다.");
        }
    }

    /** 생년월일은 판정에만 쓰고 User 에 담지 않는다. */
    private void verifyAge(SocialSignupForm form) {
        try {
            AgeVerificationPolicy.verify(
                    form == null ? null : form.getBirthDate(), LocalDate.now());
        } catch (RegistrationValidationException exception) {
            throw new SocialSignupValidationException(
                    exception.getField(), exception.getMessage(), exception.getMessageCode());
        }
    }

    /**
     * 현재 필수 정책 구성과 표시했던 전체 버전을 검증한 후 동의 여부를 판정한다.
     */
    private List<PolicyConsentDecision> resolveConsentDecisions(SocialSignupForm form) {
        List<Long> agreed = form == null ? null : form.getAgreedPolicyVersionIds();
        try {
            return signupPolicyService.loadSignupPolicies().decide(agreed,
                    form == null ? null : form.getShownPolicyVersionIds());
        } catch (PolicySnapshotException exception) {
            if (form != null) {
                form.setAgreedPolicyVersionIds(List.of());
            }
            throw new SocialSignupValidationException(
                    "agreedPolicyVersionIds", exception.getMessage(), exception.getMessageCode());
        } catch (PolicyConsentRequiredException exception) {
            throw new SocialSignupValidationException(
                    "agreedPolicyVersionIds", exception.getMessage(), exception.getMessageCode());
        }
    }

    private String validateForm(SocialSignupForm form) {
        if (form == null) {
            throw new SocialSignupValidationException(
                    "nickname", "닉네임을 입력해주세요.", "signup.error.nickname.required");
        }
        try {
            return NicknamePolicy.normalizeAndValidate(form.getNickname());
        } catch (NicknamePolicy.ViolationException exception) {
            // 형식/금칙어 구분은 일반 회원가입과 같은 key 를 쓴다.
            String messageCode =
                    exception.getViolationType() == NicknamePolicy.ViolationType.FORBIDDEN
                            ? "signup.error.nickname.forbidden"
                            : "signup.error.nickname.invalid";
            throw new SocialSignupValidationException(
                    "nickname", exception.getMessage(), messageCode);
        }
    }

    /**
     * users.user_email 에 저장할 공식 이메일.
     *
     * <p>Google 은 OIDC 가 email_verified 를 함께 주므로 provider 가 준 이메일을 그대로 신뢰한다.
     * Kakao/Naver 는 provider 이메일을 인증된 것으로 보지 않으므로 사용자가 입력한 이메일을 받아
     * Tripbora 이메일 인증을 거치게 한다.
     */
    private String resolveUserEmail(PendingSocialSignup pending, SocialSignupForm form) {
        if (pending.provider() == SocialProvider.GOOGLE) {
            return googleVerifiedEmail(pending);
        }
        return enteredEmail(form);
    }

    private String googleVerifiedEmail(PendingSocialSignup pending) {
        if (isBlank(pending.providerEmail())
                || !Boolean.TRUE.equals(pending.providerEmailVerified())) {
            throw new SocialSignupFlowException("인증된 소셜 이메일을 확인할 수 없습니다.");
        }
        try {
            return EmailPolicy.normalizeAndValidate(pending.providerEmail());
        } catch (RegistrationValidationException exception) {
            throw new SocialSignupFlowException("인증된 소셜 이메일을 확인할 수 없습니다.");
        }
    }

    /**
     * 사용자가 입력한 이메일. AJAX 결과와 무관하게 여기서 다시 정규화하고 상태를 확인한다.
     * 이번 단계에서는 아무도 쓰지 않는 이메일만 신규 가입으로 진행한다.
     */
    private String enteredEmail(SocialSignupForm form) {
        String submitted = form == null ? null : form.getUserEmail();
        if (isBlank(submitted)) {
            throw new SocialSignupValidationException(
                    "userEmail", "이메일을 입력해주세요.", "signup.social.email.required");
        }

        // 화면 안내와 같은 message key 를 쓴다. AJAX 와 최종 판정의 문구가 갈리지 않는다.
        SocialEmailAccountResolver.EnteredEmail entered =
                socialEmailAccountResolver.classifyEnteredEmail(submitted);
        return switch (entered.status()) {
            case AVAILABLE -> entered.email();
            case INVALID -> throw new SocialSignupValidationException(
                    "userEmail", EmailPolicy.INVALID_MESSAGE, "signup.error.email.invalid");
            // 연결은 다음 단계에서 지원한다. 지금은 새 계정을 만들지 않고 안내만 한다.
            case EXISTING_ACTIVE -> throw new SocialSignupValidationException(
                    "userEmail", "이미 가입된 이메일입니다.", "signup.social.email.existing");
            case UNAVAILABLE -> throw new SocialSignupValidationException(
                    "userEmail", "현재 사용할 수 없는 이메일입니다.",
                    "signup.social.email.unavailable");
        };
    }

    private String normalizeReferenceEmail(String email) {
        return isBlank(email) ? null : email.strip();
    }

    private boolean isSupportedProvider(SocialProvider provider) {
        return provider == SocialProvider.GOOGLE
                || provider == SocialProvider.KAKAO
                || provider == SocialProvider.NAVER;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
