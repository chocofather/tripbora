package com.tripbora.service.policy;

import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.model.PolicyTranslation;
import com.tripbora.model.PolicyType;
import com.tripbora.model.PolicyVersion;
import com.tripbora.repository.policy.PolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 회원가입(일반/소셜)에 쓰는 정책 세트를 조회한다.
 *
 * <p>어떤 버전을 쓸지는 코드가 아니라 DB 가 정한다. is_active / published_at / effective_at 을
 * 모두 만족하는 버전만 대상이고, 특정 v1.0 을 서비스 로직에 박아 두지 않는다.
 * 그래서 활성화 전인 지금은 빈 세트가 나오고, 활성화 SQL 을 실행하는 순간부터
 * 화면과 서버 검증이 함께 그 버전을 쓰기 시작한다.
 *
 * <p>같은 policy_type 에 활성 버전이 둘 이상 남아 있어도 SQL 이 한 행만 주므로
 * 화면과 검증이 서로 다른 버전을 볼 수 없다.
 */
@Service
@RequiredArgsConstructor
public class SignupPolicyService {

    /**
     * 회원가입 화면에 내보낼 정책과 그 순서.
     * 동의 항목을 먼저 두고, 열람만 제공하는 개인정보처리방침을 마지막에 둔다.
     */
    static final List<PolicyType> SIGNUP_POLICY_TYPES = List.of(
            PolicyType.TERMS_OF_SERVICE,
            PolicyType.PRIVACY_COLLECTION,
            PolicyType.MARKETING_EMAIL,
            PolicyType.PRIVACY_POLICY);

    /** 요청 locale 번역이 아직 없을 때 대신 보여줄 언어. */
    static final String FALLBACK_LOCALE = SupportedLanguage.KOREAN.getLanguageTag();

    private final PolicyMapper policyMapper;

    /** 현재 요청 locale 과 현재 시각 기준의 가입 정책 세트. */
    public SignupPolicySet loadSignupPolicies() {
        return loadSignupPolicies(currentLocaleTag(), LocalDateTime.now());
    }

    /**
     * @param localeTag ko/en/ja/zh-CN/zh-TW 중 하나
     * @param currentTime effective_at 판정 기준 시각
     */
    SignupPolicySet loadSignupPolicies(String localeTag, LocalDateTime currentTime) {
        List<String> typeNames = SIGNUP_POLICY_TYPES.stream().map(Enum::name).toList();
        List<PolicyVersion> versions = policyMapper.findActiveVersions(typeNames, currentTime);
        if (versions == null || versions.isEmpty()) {
            return SignupPolicySet.empty();
        }

        Map<PolicyType, PolicyVersion> activeByType = new EnumMap<>(PolicyType.class);
        for (PolicyVersion version : versions) {
            if (version == null || version.getId() == null) {
                continue;
            }
            // 아는 policy_type 만 화면에 올린다. 모르는 값이 들어와도 조회가 깨지지 않는다.
            PolicyType.from(version.getPolicyType())
                    .filter(SIGNUP_POLICY_TYPES::contains)
                    .ifPresent(type -> activeByType.putIfAbsent(type, version));
        }
        if (activeByType.isEmpty()) {
            return SignupPolicySet.empty();
        }

        List<Long> versionIds = activeByType.values().stream()
                .map(PolicyVersion::getId)
                .toList();
        Map<Long, PolicyTranslation> translations = chooseTranslations(versionIds, localeTag);

        List<SignupPolicy> policies = new ArrayList<>();
        for (PolicyType type : SIGNUP_POLICY_TYPES) {
            PolicyVersion version = activeByType.get(type);
            if (version == null) {
                continue;
            }
            PolicyTranslation translation = translations.get(version.getId());
            policies.add(new SignupPolicy(
                    version.getId(),
                    type,
                    version.getVersion(),
                    version.isRequiresConsent(),
                    version.isRequired(),
                    translation == null ? null : translation.getTitle(),
                    translation == null ? null : PolicyContent.toSafeHtml(translation.getContent()),
                    translation == null ? null : translation.getLocale(),
                    version.getEffectiveAt()));
        }
        return new SignupPolicySet(policies);
    }

    /**
     * 버전별로 실제 보여줄 번역 하나를 고른다.
     * 요청 locale 번역이 있으면 그것을 쓰고, 없으면 ko 로 대체한다.
     * 지금은 ko 만 있으므로 영어/일본어/중국어 화면도 한국어 본문을 받아 화면이 비지 않는다.
     */
    private Map<Long, PolicyTranslation> chooseTranslations(List<Long> versionIds,
                                                            String localeTag) {
        List<PolicyTranslation> rows =
                policyMapper.findTranslations(versionIds, localeTag, FALLBACK_LOCALE);
        Map<Long, PolicyTranslation> chosen = new HashMap<>();
        if (rows == null) {
            return chosen;
        }
        for (PolicyTranslation row : rows) {
            if (row == null || row.getPolicyVersionId() == null) {
                continue;
            }
            if (localeTag.equals(row.getLocale())) {
                // 요청 locale 이 언제나 이긴다. 대체본을 먼저 읽었더라도 덮어쓴다.
                chosen.put(row.getPolicyVersionId(), row);
            } else {
                chosen.putIfAbsent(row.getPolicyVersionId(), row);
            }
        }
        return chosen;
    }

    /**
     * 동의 이력에 남길 locale. users 화면에서 실제로 쓰인 언어이며
     * user_policy_consents 의 CHECK 제약이 허용하는 다섯 값 중 하나로 정규화된다.
     */
    public static String currentLocaleTag() {
        return SupportedLanguage.normalize(LocaleContextHolder.getLocale())
                .orElse(SupportedLanguage.KOREAN)
                .getLanguageTag();
    }
}
