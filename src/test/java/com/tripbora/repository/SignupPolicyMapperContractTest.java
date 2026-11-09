package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 어떤 정책 버전을 회원가입에 쓸지는 전부 SQL 이 정한다.
 * 조건이 하나라도 빠지면 아직 게시하지 않았거나 시행 전인 정책이 가입 화면에 올라간다.
 */
class SignupPolicyMapperContractTest {

    /**
     * 활성 조건 셋을 모두 만족한 버전만 회원가입에 쓴다.
     * 지금은 is_active = 0 이라 이 조건이 한 행도 내주지 않는다. 활성화는 별도 SQL 로 한다.
     */
    @Test
    void onlyPublishedActiveAndAlreadyEffectiveVersionsAreUsed() throws IOException {
        String select = statement(policyXml(), "select", "findActiveVersions");

        assertThat(select)
                .contains("v.is_active = 1")
                .contains("v.published_at IS NOT NULL")
                .contains("v.effective_at &lt;= #{currentTime}");
    }

    /** 한 policy_type 에 활성 버전이 여럿이어도 화면과 검증이 같은 한 버전을 본다. */
    @Test
    void exactlyOneVersionIsSelectedPerPolicyType() throws IOException {
        String select = statement(policyXml(), "select", "findActiveVersions");

        assertThat(select)
                .contains("NOT EXISTS")
                .contains("newer.policy_type = v.policy_type")
                // 더 나중에 시행된 버전(동률이면 더 큰 id)이 이긴다.
                .contains("newer.effective_at &gt; v.effective_at")
                .contains("newer.effective_at = v.effective_at AND newer.id &gt; v.id");
    }

    /** 조회 대상은 파라미터로 받은 policy_type 뿐이다. 코드에 특정 버전을 박아 두지 않는다. */
    @Test
    void theQueryNeverPinsAParticularVersionOrId() throws IOException {
        String policyXml = policyXml();

        assertThat(statement(policyXml, "select", "findActiveVersions"))
                .contains("v.policy_type IN")
                .doesNotContain("'1.0'")
                .doesNotContain("v.version =");
        // 읽기 전용 mapper 다. 정책 등록/활성화는 DB 작업이다.
        assertThat(policyXml)
                .doesNotContain("<insert")
                .doesNotContain("<update")
                .doesNotContain("<delete");
    }

    /** 11) 요청 locale 과 대체 locale 을 함께 읽어 온다. 우선순위 판단은 서비스가 한다. */
    @Test
    void bothTheRequestedAndTheFallbackLocaleAreRead() throws IOException {
        assertThat(statement(policyXml(), "select", "findTranslations"))
                .contains("t.locale IN (#{locale}, #{fallbackLocale})")
                .contains("t.policy_version_id IN");
    }

        /** 동의 이력은 append-only 다. 사진 상태 갱신과 구분한다. */
    @Test
    void theConsentIsOnlyEverInserted() throws IOException {
        String consentXml = resource("mapper/UserPolicyConsentMapper.xml");

        assertThat(statement(consentXml, "insert", "insertConsent"))
                .contains("INSERT INTO user_policy_consents")
                .contains("(user_id, policy_version_id, agreed, decision_action, locale, consent_source)")
                .contains("#{decisionAction}");
        // 기존 동의를 덮어쓰는 경로를 만들지 않는다.
        assertThat(consentXml)
                .doesNotContain("UPDATE user_policy_consents")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("REPLACE INTO");
        // 정책 원본은 참조만 한다.
        assertThat(consentXml)
                .doesNotContain("DELETE FROM policy_versions")
                .doesNotContain("DELETE FROM policy_translations");
    }

    private String policyXml() throws IOException {
        return resource("mapper/PolicyMapper.xml");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(Path.of("src/main/resources").resolve(relativePath),
                StandardCharsets.UTF_8);
    }

    private String statement(String xml, String tag, String id) {
        String open = "<" + tag + " id=\"" + id + "\"";
        int start = xml.indexOf(open);
        int end = xml.indexOf("</" + tag + ">", start);
        assertThat(start).as("%s statement %s", tag, id).isNotNegative();
        assertThat(end).as("%s statement %s closing tag", tag, id).isGreaterThan(start);
        return xml.substring(start, end);
    }
}
