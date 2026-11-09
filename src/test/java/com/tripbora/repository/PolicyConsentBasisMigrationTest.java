package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class PolicyConsentBasisMigrationTest {
    @Test
    void migrationAddsNullableEvidenceWithoutRewritingHistoricDecisionsOrPhotos() throws Exception {
        String sql=Files.readString(Path.of("src/main/resources/db/migration/V20261102_003__add_policy_consent_basis_and_decision.sql"));
        String commands=sql.replaceAll("(?m)--[^\\r\\n]*", "").toUpperCase(java.util.Locale.ROOT);
        assertThat(commands).contains("CONSENT_BASIS_VERSION_ID BIGINT NULL", "DECISION_ACTION VARCHAR(16)",
                "REFERENCES POLICY_VERSIONS (ID) ON DELETE RESTRICT", "DECISION_ACTION IS NULL",
                "AGREED = 1", "AGREED = 0", "'AGREE'", "'DECLINE'", "'WITHDRAW'",
                "(USER_ID, POLICY_VERSION_ID, ID)");
        assertThat(commands).doesNotContainPattern("(?im)^\\s*(UPDATE|INSERT|DELETE|DROP)\\b")
                .doesNotContain("DESTINATION_COMMENT_IMAGES");
    }
}
