package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 동의 판정용 조회는 전체 이력 대신 회원·정책별 최신 근거만 반환한다. SQL 실행은 하지 않는다. */
class PolicyConsentEvidenceMapperTest {
    @Test
    void evidenceQueriesAreScopedBoundedAndUseDecisionOrderRatherThanTimestampOrder() throws Exception {
        for(String id : new String[]{"findLatestForBasis", "findLatestStopForType", "findLatestAgreementOnOtherBasis", "findLatestAgreementForType"}) {
            String sql=select("UserPolicyConsentMapper.xml", id);
            assertThat(sql).contains("c.user_id = #{userId}", "v.policy_type = #{policyType}",
                    "c.created_at &lt;= #{asOf}", "ORDER BY c.id DESC LIMIT 1")
                    .doesNotContain("ORDER BY c.created_at");
        }
    }

    @Test void withdrawalAccessRequiresAPositiveAgreementEvenIfLaterDeclined() throws Exception {
        String sql = select("UserPolicyConsentMapper.xml", "findLatestAgreementForType");
        assertThat(sql).contains("c.agreed = 1", "c.decision_action = 'AGREE' OR c.decision_action IS NULL");
        assertThat(sql).doesNotContain("NOT EXISTS", "'DECLINE'", "v.is_active");
    }

    @Test
    void withdrawalAndUnknownLegacyFalseStopOlderGrantsButDeclineDoesNotWithdrawOtherScopes() throws Exception {
        String sql=select("UserPolicyConsentMapper.xml", "findLatestStopForType");
        assertThat(sql).contains("c.decision_action = 'WITHDRAW'", "c.decision_action IS NULL AND c.agreed = 0")
                .doesNotContain("'DECLINE'");
        String older=select("UserPolicyConsentMapper.xml", "findLatestAgreementOnOtherBasis");
        assertThat(older).contains("NOT EXISTS", "newer.user_id = c.user_id", "newer.id &gt; c.id",
                "newer.created_at &lt;= #{asOf}", "newer_version.policy_type = v.policy_type",
                "COALESCE(newer_version.consent_basis_version_id, newer_version.id)",
                "= COALESCE(v.consent_basis_version_id, v.id)");
    }

    @Test
    void basisValidationDoesNotSkipAnInactiveOrUnclassifiedIntermediateRevision() throws Exception {
        String sql=select("PolicyMapper.xml", "hasInterveningMaterialVersion");
        assertThat(sql).contains("middle.policy_type = basis.policy_type", "middle.published_at IS NOT NULL",
                "middle.requires_reconsent = 1", "middle.consent_basis_version_id IS NULL",
                "middle.consent_basis_version_id != basis.id")
                .doesNotContain("middle.is_active = 1");
        assertThat(select("PolicyMapper.xml", "findVersionById")).contains("consent_basis_version_id", "WHERE id = #{id}");
    }

    private String select(String file,String id) throws Exception {
        String xml=Files.readString(Path.of("src/main/resources/mapper",file));
        int start=xml.indexOf("<select id=\""+id+"\"");
        assertThat(start).as(id).isNotNegative();
        int end=xml.indexOf("</select>",start);
        assertThat(end).isGreaterThan(start);
        return xml.substring(start,end);
    }
}
