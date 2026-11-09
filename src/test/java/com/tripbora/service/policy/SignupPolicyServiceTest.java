package com.tripbora.service.policy;

import com.tripbora.model.PolicyTranslation;
import com.tripbora.model.PolicyType;
import com.tripbora.model.PolicyVersion;
import com.tripbora.repository.policy.PolicyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 가입 정책 세트 조회. 어떤 버전을 쓸지는 SQL 이 정하고, 여기에서는 번역 선택과 화면 구성만 본다.
 */
@ExtendWith(MockitoExtension.class)
class SignupPolicyServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 13, 10, 0);

    @Mock
    private PolicyMapper policyMapper;

    private SignupPolicyService service() {
        return new SignupPolicyService(policyMapper);
    }

    private PolicyVersion version(long id, PolicyType type,
                                  boolean requiresConsent, boolean required) {
        PolicyVersion policyVersion = new PolicyVersion();
        policyVersion.setId(id);
        policyVersion.setPolicyType(type.name());
        policyVersion.setVersion("1.0");
        policyVersion.setRequiresConsent(requiresConsent);
        policyVersion.setRequired(required);
        policyVersion.setActive(true);
        policyVersion.setEffectiveAt(NOW.minusDays(1));
        policyVersion.setPublishedAt(NOW.minusDays(1));
        return policyVersion;
    }

    private PolicyTranslation translation(long versionId, String locale, String title) {
        PolicyTranslation policyTranslation = new PolicyTranslation();
        policyTranslation.setPolicyVersionId(versionId);
        policyTranslation.setLocale(locale);
        policyTranslation.setTitle(title);
        policyTranslation.setContent("<p>" + title + "</p>");
        return policyTranslation;
    }

    private void activeVersions(PolicyVersion... versions) {
        when(policyMapper.findActiveVersions(anyList(), any())).thenReturn(List.of(versions));
    }

    /** 아직 활성화하지 않은 지금 상태. 세트가 비어 필수 동의도 없고 기존 가입 흐름이 그대로 돈다. */
    @Test
    void noActiveVersionYieldsAnEmptySetThatRequiresNothing() {
        when(policyMapper.findActiveVersions(anyList(), any())).thenReturn(List.of());

        SignupPolicySet policies = service().loadSignupPolicies("ko", NOW);

        assertThat(policies.isEmpty()).isTrue();
        assertThat(policies.decide(List.of())).isEmpty();
    }

    /** 시행일 판정 기준 시각과 조회 대상 policy_type 을 SQL 에 그대로 넘긴다. */
    @Test
    void theActiveVersionQueryCarriesTheSignupPolicyTypesAndTheCurrentTime() {
        when(policyMapper.findActiveVersions(anyList(), any())).thenReturn(List.of());

        service().loadSignupPolicies("ko", NOW);

        verify(policyMapper).findActiveVersions(
                List.of("TERMS_OF_SERVICE", "PRIVACY_COLLECTION",
                        "MARKETING_EMAIL", "PRIVACY_POLICY"),
                NOW);
    }

    @Test
    void photoPoliciesAreExcludedFromSignupDisplayAndDecisionHistory() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true),
                version(2L, PolicyType.PRIVACY_COLLECTION, true, true),
                version(50L, PolicyType.REVIEW_GALLERY, true, false),
                version(51L, PolicyType.PHOTO_UPLOAD_NOTICE, false, false));
        when(policyMapper.findTranslations(anyList(), anyString(), anyString())).thenReturn(List.of());
        SignupPolicySet policies = service().loadSignupPolicies("ko", NOW);
        assertThat(policies.policies()).extracting(SignupPolicy::type)
                .containsExactly(PolicyType.TERMS_OF_SERVICE, PolicyType.PRIVACY_COLLECTION);
        assertThat(policies.decide(List.of(1L, 2L, 50L, 51L)))
                .extracting(PolicyConsentDecision::policyVersionId).containsExactly(1L, 2L);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> policies.decide(List.of(1L)))
                .isInstanceOf(PolicyConsentRequiredException.class);
    }

    /** 동의 항목과 열람 전용 항목이 갈린다. 개인정보처리방침은 체크박스를 만들지 않는다. */
    @Test
    void theViewOnlyPolicyIsSeparatedFromTheConsentPolicies() {
        activeVersions(
                version(1L, PolicyType.TERMS_OF_SERVICE, true, true),
                version(2L, PolicyType.PRIVACY_COLLECTION, true, true),
                version(3L, PolicyType.MARKETING_EMAIL, true, false),
                version(4L, PolicyType.PRIVACY_POLICY, false, false));
        when(policyMapper.findTranslations(anyList(), anyString(), anyString()))
                .thenReturn(List.of());

        SignupPolicySet policies = service().loadSignupPolicies("ko", NOW);

        assertThat(policies.consentPolicies()).extracting(SignupPolicy::type)
                .containsExactly(PolicyType.TERMS_OF_SERVICE,
                        PolicyType.PRIVACY_COLLECTION, PolicyType.MARKETING_EMAIL);
        assertThat(policies.viewOnlyPolicies()).extracting(SignupPolicy::type)
                .containsExactly(PolicyType.PRIVACY_POLICY);
        // 필수 여부는 화면 문자열이 아니라 policy_versions.is_required 를 그대로 따른다.
        assertThat(policies.consentPolicies()).extracting(SignupPolicy::required)
                .containsExactly(true, true, false);
    }

    /** 11) 요청 locale 번역이 없으면 ko 본문으로 대체한다. 화면이 비지 않는다. */
    @Test
    void aMissingTranslationFallsBackToKorean() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true));
        when(policyMapper.findTranslations(List.of(1L), "ja", "ko"))
                .thenReturn(List.of(translation(1L, "ko", "이용약관")));

        SignupPolicySet policies = service().loadSignupPolicies("ja", NOW);

        SignupPolicy terms = policies.consentPolicies().get(0);
        assertThat(terms.title()).isEqualTo("이용약관");
        assertThat(terms.contentLocale()).isEqualTo("ko");
        assertThat(terms.content()).contains("이용약관");
        assertThat(terms.effectiveAt()).isEqualTo(NOW.minusDays(1));
    }

    /** 요청 locale 번역이 있으면 대체본을 먼저 읽었더라도 그쪽이 이긴다. */
    @Test
    void theRequestedLocaleWinsOverTheFallbackRegardlessOfRowOrder() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true));
        when(policyMapper.findTranslations(List.of(1L), "en", "ko"))
                .thenReturn(List.of(
                        translation(1L, "ko", "이용약관"),
                        translation(1L, "en", "Terms of Service")));

        SignupPolicy terms = service().loadSignupPolicies("en", NOW)
                .consentPolicies().get(0);

        assertThat(terms.title()).isEqualTo("Terms of Service");
        assertThat(terms.contentLocale()).isEqualTo("en");
    }

    /** 번역이 아예 없어도 정책 자체는 살아 있어야 한다. 제목은 화면이 messages 로 대신 채운다. */
    @Test
    void aPolicyWithoutAnyTranslationStillCountsForConsent() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true));
        when(policyMapper.findTranslations(anyList(), anyString(), anyString()))
                .thenReturn(List.of());

        SignupPolicy terms = service().loadSignupPolicies("ko", NOW)
                .consentPolicies().get(0);

        assertThat(terms.title()).isNull();
        assertThat(terms.content()).isNull();
        assertThat(terms.labelCode()).isEqualTo("signup.terms.service");
        assertThat(terms.mandatory()).isTrue();
    }

    /** 알 수 없는 policy_type 이 DB 에 들어와도 조회가 깨지지 않고 그 행만 빠진다. */
    @Test
    void anUnknownPolicyTypeIsIgnored() {
        PolicyVersion unknown = version(9L, PolicyType.TERMS_OF_SERVICE, true, true);
        unknown.setPolicyType("SOMETHING_NEW");
        when(policyMapper.findActiveVersions(anyList(), any())).thenReturn(List.of(unknown));

        assertThat(service().loadSignupPolicies("ko", NOW).isEmpty()).isTrue();
        verify(policyMapper, org.mockito.Mockito.never())
                .findTranslations(anyList(), anyString(), anyString());
    }

    /** ko 이외의 화면도 대체 locale 로 언제나 ko 를 함께 조회한다. */
    @Test
    void theFallbackLocaleIsAlwaysKorean() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true));
        when(policyMapper.findTranslations(anyList(), anyString(), anyString()))
                .thenReturn(List.of());

        service().loadSignupPolicies("zh-TW", NOW);

        verify(policyMapper).findTranslations(List.of(1L), "zh-TW", "ko");
    }

    /** 본문이 평문이어도 줄바꿈이 남고, 실행 가능한 태그는 남지 않는다. */
    @Test
    void thePolicyContentIsSanitizedAndKeepsPlainTextLineBreaks() {
        activeVersions(version(1L, PolicyType.TERMS_OF_SERVICE, true, true));
        PolicyTranslation plain = translation(1L, "ko", "이용약관");
        plain.setContent("제1조\n제2조 <script>alert(1)</script>");
        when(policyMapper.findTranslations(anyList(), anyString(), anyString()))
                .thenReturn(List.of(plain));

        String content = service().loadSignupPolicies("ko", NOW)
                .consentPolicies().get(0).content();

        assertThat(content).contains("제1조<br>제2조").doesNotContain("<script");
    }

    /** 동의 이력에 남길 locale 은 지원 언어 다섯 개 중 하나로 정규화된다. */
    @Test
    void theConsentLocaleIsNormalizedToASupportedLanguage() {
        assertThat(localeTagFor("ko-KR")).isEqualTo("ko");
        assertThat(localeTagFor("en-GB")).isEqualTo("en");
        assertThat(localeTagFor("zh-HK")).isEqualTo("zh-TW");
        assertThat(localeTagFor("zh-CN")).isEqualTo("zh-CN");
        // 지원하지 않는 언어는 ko 로 떨어진다. CHECK 제약을 어기는 값이 들어가지 않는다.
        assertThat(localeTagFor("fr-FR")).isEqualTo("ko");
    }

    private String localeTagFor(String languageTag) {
        org.springframework.context.i18n.LocaleContextHolder.setLocale(
                java.util.Locale.forLanguageTag(languageTag));
        try {
            return SignupPolicyService.currentLocaleTag();
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    /** 화면이 보낸 id 는 현재 세트로만 해석된다. 모르는 id 는 버려진다. */
    @Test
    void submittedIdsOutsideTheCurrentSetAreDiscarded() {
        SignupPolicySet policies = SignupPolicyFixtures.activeSignupPolicies();

        List<PolicyConsentDecision> decisions = policies.decide(List.of(
                SignupPolicyFixtures.TERMS_ID,
                SignupPolicyFixtures.PRIVACY_COLLECTION_ID,
                999_999L,
                // 열람 전용 정책의 id 를 보내도 동의행이 생기지 않는다.
                SignupPolicyFixtures.PRIVACY_POLICY_ID));

        assertThat(decisions).extracting(PolicyConsentDecision::policyVersionId)
                .containsExactly(
                        SignupPolicyFixtures.TERMS_ID,
                        SignupPolicyFixtures.PRIVACY_COLLECTION_ID,
                        SignupPolicyFixtures.MARKETING_ID)
                .doesNotContain(999_999L, SignupPolicyFixtures.PRIVACY_POLICY_ID);
    }

    /** 필수 동의가 빠지면 그 정책의 messages key 를 달고 거절한다. */
    @Test
    void aMissingMandatoryConsentReportsThePolicyThatFailed() {
        SignupPolicySet policies = SignupPolicyFixtures.activeSignupPolicies();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> policies.decide(List.of(SignupPolicyFixtures.TERMS_ID)))
                .isInstanceOf(PolicyConsentRequiredException.class)
                .hasFieldOrPropertyWithValue("policyType", PolicyType.PRIVACY_COLLECTION)
                .hasFieldOrPropertyWithValue("messageCode", "signup.error.terms.privacy");
    }

    /** null 이 섞여 들어와도 판정이 깨지지 않는다. */
    @Test
    void nullSubmissionsAreTreatedAsNoConsent() {
        SignupPolicySet policies = new SignupPolicySet(List.of(
                SignupPolicyFixtures.policy(SignupPolicyFixtures.MARKETING_ID,
                        PolicyType.MARKETING_EMAIL, true, false)));

        assertThat(policies.decide(null)).extracting(PolicyConsentDecision::agreed)
                .containsExactly(false);
        assertThat(policies.decide(java.util.Collections.singletonList(null)))
                .extracting(PolicyConsentDecision::agreed)
                .containsExactly(false);
    }

    /** 같은 타입에 활성 버전이 둘이면 SQL 이 하나만 주므로, 서비스도 그 하나만 쓴다. */
    @Test
    void onlyOneVersionPerTypeReachesTheScreen() {
        activeVersions(
                version(1L, PolicyType.TERMS_OF_SERVICE, true, true),
                version(2L, PolicyType.TERMS_OF_SERVICE, true, true));
        when(policyMapper.findTranslations(anyList(), eq("ko"), eq("ko")))
                .thenReturn(List.of());

        SignupPolicySet policies = service().loadSignupPolicies("ko", NOW);

        assertThat(policies.consentPolicies()).hasSize(1);
    }
}
