package com.tripbora.service.user;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.dto.RegistrationForm;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.model.UserStatus;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.AccountAbuseGuard;
import com.tripbora.service.email.EmailDispatchService;
import com.tripbora.service.email.EmailVerificationService;
import com.tripbora.service.policy.PolicyConsentDecision;
import com.tripbora.service.policy.PolicyConsentRequiredException;
import com.tripbora.service.policy.PolicySnapshotException;
import com.tripbora.service.policy.SignupPolicyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class UserService {
    public static final String INVALID_RESET_TOKEN_MESSAGE =
            "만료되었거나 잘못된 토큰입니다.";
    public static final String SAME_AS_CURRENT_PASSWORD_MESSAGE =
            "현재 사용 중인 비밀번호와 다른 비밀번호를 입력해 주세요.";
    /** 재설정 링크 유효시간. 메일 안내 문구도 이 값을 그대로 쓴다. */
    public static final Duration RESET_TOKEN_VALIDITY = Duration.ofMinutes(30);
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailDispatchService emailDispatchService;
    private final EmailVerificationService emailVerificationService;
    private final SignupPolicyService signupPolicyService;
    private final RegistrationTransactionService registrationTransactionService;
    /** 같은 주소로 복구 메일이 거듭 나가지 않게 하는 자리. 한도는 이 서비스가 알지 않는다. */
    private final AccountAbuseGuard accountAbuseGuard;

    @Value("${custom.server-url}")
    private String serverUrl;

    @Autowired
    public UserService(UserMapper userMapper, PasswordEncoder passwordEncoder,
                       EmailDispatchService emailDispatchService,
                       EmailVerificationService emailVerificationService,
                       SignupPolicyService signupPolicyService,
                       RegistrationTransactionService registrationTransactionService,
                       AccountAbuseGuard accountAbuseGuard) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.emailDispatchService = emailDispatchService;
        this.emailVerificationService = emailVerificationService;
        this.signupPolicyService = signupPolicyService;
        this.registrationTransactionService = registrationTransactionService;
        this.accountAbuseGuard = accountAbuseGuard;
    }

    // 📝 회원가입 기능
    public RegistrationResult registerUser(RegistrationForm form) {
        // 만 14세 미만은 여기서 막는다. 생년월일은 판정에만 쓰고 User 에 담지 않는다.
        AgeVerificationPolicy.verify(form.getBirthDate(), LocalDate.now());

        String email = EmailPolicy.normalizeAndValidate(form.getUserEmail());
        String nickname = NicknamePolicy.normalizeAndValidate(form.getNickname());
        String rawPassword = form.getUserPassword();

        PasswordPolicy.validate(rawPassword);
        if (!rawPassword.equals(form.getPasswordConfirm())) {
            throw new RegistrationValidationException(
                    "passwordConfirm", "비밀번호가 일치하지 않습니다.",
                    "signup.error.passwordConfirm.mismatch");
        }

        validateRegistrationDuplicates(email, nickname);

        // 화면 체크박스를 믿지 않는다. 현재 가입 정책 세트를 서버가 다시 조회해서
        // 필수 동의가 모두 들어왔는지 보고, 저장할 결정 목록도 여기에서 만든다.
        String consentLocale = SignupPolicyService.currentLocaleTag();
        List<PolicyConsentDecision> consentDecisions =
                resolveConsentDecisions(form);

        User user = new User();
        user.setUserEmail(email);
        user.setNickname(nickname);

        user.setUserPassword(passwordEncoder.encode(rawPassword));

        // ✅ 기본 사용자 정보 설정
        user.setUserRole(UserRole.USER);
        user.setStatus(UserStatus.INACTIVE);
        user.setCreatedAt(Timestamp.valueOf(LocalDateTime.now()));

        emailVerificationService.initializeVerification(user);

        user.setProfileImage("uploads/default.png");

        try {
            // users INSERT 와 동의 이력 INSERT 가 한 트랜잭션이다.
            // 동의 저장이 실패하면 회원도 남지 않는다.
            registrationTransactionService.insertUserWithConsents(
                    user, consentDecisions, consentLocale);
        } catch (DataIntegrityViolationException exception) {
            throw new RegistrationValidationException(
                    "registration", "이미 사용 중인 회원가입 정보가 있습니다.",
                    "signup.error.duplicate");
        }
        log.info("Registration user stored: userId={}, recipient={}, consents={}, locale={}",
                user.getId(), EmailPolicy.mask(email), consentDecisions.size(), consentLocale);

        boolean emailRequested = emailVerificationService.requestInitialVerification(user);
        log.info("Registration verification email dispatch completed: userId={}, requested={}",
                user.getId(), emailRequested);
        return new RegistrationResult(email, emailRequested);
    }

    /**
     * 현재 가입 정책 세트로 동의 여부를 판정한다.
     *
     * <p>필수 정책 구성이 잘못되었거나 표시했던 버전이 바뀌었으면 저장 전에 막는다.
     */
    private List<PolicyConsentDecision> resolveConsentDecisions(RegistrationForm form) {
        try {
            return signupPolicyService.loadSignupPolicies().decide(
                    form.getAgreedPolicyVersionIds(), form.getShownPolicyVersionIds());
        } catch (PolicySnapshotException exception) {
            form.setAgreedPolicyVersionIds(List.of());
            throw new RegistrationValidationException(
                    "agreedPolicyVersionIds", exception.getMessage(), exception.getMessageCode());
        } catch (PolicyConsentRequiredException exception) {
            throw new RegistrationValidationException(
                    "agreedPolicyVersionIds", exception.getMessage(), exception.getMessageCode());
        }
    }

    private void validateRegistrationDuplicates(String email, String nickname) {
        if (userMapper.findByEmail(email) != null) {
            throw new RegistrationValidationException("userEmail", "이미 사용 중인 이메일입니다.",
                    "signup.error.email.duplicate");
        }
        if (userMapper.countByNickname(nickname) > 0) {
            throw new RegistrationValidationException("nickname", "이미 사용 중인 닉네임입니다.",
                    "signup.error.nickname.duplicate");
        }
    }

    // 🏷 닉네임 중복 검사
    public boolean isNicknameExists(String nickname) {
        String normalized = NicknamePolicy.normalizeAndValidate(nickname);
        return userMapper.countByNickname(normalized) > 0;
    }

    /**
     * 자동 추천 전용. 같은 조합의 언어별 표기를 한 번의 조회로 확인한다.
     * 사용자가 직접 입력한 닉네임 검사(isNicknameExists)는 기존 정책 그대로다.
     */
    public boolean isAnyNicknameExists(Collection<String> nicknames) {
        if (nicknames == null || nicknames.isEmpty()) {
            return false;
        }
        List<String> normalized = nicknames.stream()
                .map(NicknamePolicy::normalizeAndValidate)
                .distinct()
                .toList();
        return userMapper.countByNicknameIn(normalized) > 0;
    }

    // 이메일 중복검사
    public boolean isEmailExists(String email) {
        return userMapper.findByEmail(EmailPolicy.normalizeAndValidate(email)) != null;
    }

    // 프로필 이미지
    public String getProfileImage(User user) {
        if (user.getProfileImage() == null || user.getProfileImage().isEmpty()) {
            return "/images/default.png";
        }
        return user.getProfileImage();
    }

    /* =========== [ 비밀번호 재설정 링크 발송 ] =========== */

    /**
     * 비밀번호 재설정 링크 요청.
     *
     * <p>cooldown 에 걸린 요청은 토큰을 새로 발급하지도 않는다. 재설정 토큰은 한 회원에
     * 하나뿐이라 새로 발급하면 방금 메일로 받은 링크가 곧바로 무효가 되기 때문이다.
     * 그래서 거듭 눌러도 먼저 받은 링크를 그대로 쓸 수 있다.
     */
    public void processResetPasswordRequest(String email) {
        String normalizedEmail = EmailPolicy.normalizeAndValidate(email);
        boolean mayDispatch = accountAbuseGuard.allowRecoveryEmail(
                AccountAbuseGuard.RecoveryEmailKind.PASSWORD_RESET, normalizedEmail);

        User u = userMapper.findActiveLocalAccountByEmailForPasswordReset(normalizedEmail);
        if (u == null || !mayDispatch) {
            return;
        }

        String rawToken = UUID.randomUUID().toString();
        String tokenHash = ResetTokenHasher.hash(rawToken);
        LocalDateTime exp = LocalDateTime.now().plus(RESET_TOKEN_VALIDITY);

        userMapper.updateResetToken(u.getId(), tokenHash, exp);

        String link = serverUrl + "/users/reset-password?token=" + rawToken;
        dispatchPasswordResetEmail(normalizedEmail, link);
    }

    /**
     * 메일 발송은 @Async 라 워커 스레드에서 locale 을 다시 읽을 수 없다.
     * 요청 스레드인 여기서 언어를 확정해 넘긴다.
     */
    private SupportedLanguage requestLanguage() {
        return SupportedLanguage.fromLocale(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN);
    }

    private void dispatchPasswordResetEmail(String recipient, String resetUrl) {
        try {
            emailDispatchService.dispatchPasswordResetEmail(
                    recipient, resetUrl, requestLanguage());
        } catch (RuntimeException exception) {
            log.error("Password reset email could not be scheduled: exceptionType={}",
                    exception.getClass().getSimpleName());
        }
    }

    /* =========== [ 토큰 검증 ] =========== */
    public User validateResetToken(String rawToken) {
        String tokenHash = ResetTokenHasher.hash(rawToken);
        User u = userMapper.findByResetToken(tokenHash);
        if (u == null) return null;
        return u.getResetTokenExp().isAfter(LocalDateTime.now()) ? u : null;
    }

    /* =========== [ 실제 비밀번호 변경 ] =========== */

    /**
     * 확인 입력 없이 같은 값으로 바꾼다.
     *
     * <p>이 자리에도 경계를 두는 이유는 아래 3-인자 메서드를 같은 객체에서 부르기 때문이다.
     * 그 호출은 Spring 프록시를 지나지 않아 저쪽 어노테이션이 걸리지 않는다. 바깥에서 들어오는
     * 문은 둘 다이므로 둘 다 경계를 갖는다.
     */
    @Transactional
    public void resetPassword(String rawToken, String rawPw) {
        resetPassword(rawToken, rawPw, rawPw);
    }

    /**
     * 재설정 링크로 비밀번호를 바꾼다.
     *
     * <p>비밀번호 변경과 토큰 폐기는 한 작업이다. 한쪽만 커밋되면 비밀번호는 바뀌었는데
     * 재설정 토큰이 만료 전까지 살아 있어, 메일을 가로챈 쪽이 다시 바꿀 수 있는 창이 남는다.
     * 그래서 둘을 한 트랜잭션으로 묶는다.
     */
    @Transactional
    public void resetPassword(String rawToken, String rawPw, String passwordConfirmation) {
        User u = validateResetToken(rawToken);
        if (u == null) throw new IllegalArgumentException(INVALID_RESET_TOKEN_MESSAGE);

        PasswordPolicy.validate(rawPw);
        PasswordPolicy.validateConfirmation(rawPw, passwordConfirmation);
        if (passwordEncoder.matches(rawPw, u.getUserPassword())) {
            throw new IllegalArgumentException(SAME_AS_CURRENT_PASSWORD_MESSAGE);
        }
        String encPw = passwordEncoder.encode(rawPw);
        userMapper.updateUserPassword(u.getId(), encPw);
        userMapper.clearResetToken(u.getId());   // 1회 사용 후 폐기
    }
}
