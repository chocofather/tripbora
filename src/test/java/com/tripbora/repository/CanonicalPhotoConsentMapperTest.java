package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.StringResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Actual MyBatis expansion: no application/DB start and no SQL execution. */
class CanonicalPhotoConsentMapperTest {
    @Test void migrationAddsOnlyNullableEvidenceAndPreservesLegacySnapshots() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V20261102_004__bind_review_photo_consent_evidence.sql"));
        assertThat(ChecksumCalculator.calculate(new StringResource(sql))).isEqualTo(847444896);
        assertThat(sql).contains("gallery_consent_id BIGINT NULL", "REFERENCES user_policy_consents (id) ON DELETE RESTRICT",
                "MODIFY COLUMN gallery_consent_at DATETIME(6) NULL");
        assertThat(sql).doesNotContain("MODIFY COLUMN created_at");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(UPDATE|DELETE|DROP)\\b");
    }

    @Test void followUpMigrationChangesOnlyPrecisionAndTheExistingEvidenceForeignKey() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V20261102_005__preserve_review_photo_precision_and_consent_retention.sql"));
        String[] statements = sql.replace("\r\n", "\n")
                .replaceAll("(?m)^\\s*--[^\\r\\n]*", "").strip().split(";\\s*");
        assertThat(statements).hasSize(3);
        assertThat(statements[0]).contains("ALTER TABLE destination_comment_images",
                "MODIFY COLUMN created_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6)")
                .doesNotContain("FOREIGN KEY", "ADD COLUMN");
        assertThat(statements[1].strip()).isEqualTo("ALTER TABLE destination_comment_images\n"
                + "    DROP FOREIGN KEY fk_destination_comment_image_consent");
        assertThat(statements[2]).contains("ALTER TABLE destination_comment_images",
                "ADD CONSTRAINT fk_destination_comment_image_consent",
                "FOREIGN KEY (gallery_consent_id) REFERENCES user_policy_consents (id) ON DELETE SET NULL")
                .doesNotContain("DROP FOREIGN KEY", "MODIFY COLUMN");
        assertThat(sql).contains("DROP FOREIGN KEY fk_destination_comment_image_consent",
                "MODIFY COLUMN created_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6)",
                "ADD CONSTRAINT fk_destination_comment_image_consent",
                "REFERENCES user_policy_consents (id) ON DELETE SET NULL");
        assertThat(sql).doesNotContain("ADD COLUMN", "MODIFY COLUMN gallery_consent_at");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(UPDATE|DELETE|DROP TABLE|DROP COLUMN)\\b");
    }

    @Test void runtimeAndGalleryUseTheSameOriginalGrantScopeAndWithdrawalBarrier() throws Exception {
        var configuration = configuration();
        String runtime = sql(configuration, "DestinationCommentImageMapper.photoRights", Map.of("imageId", 1L));
        String gallery = sql(configuration, "DestinationCommentImageMapper.findApprovedGallery",
                Map.of("destinationId", 10L, "limit", 30, "currentTime", LocalDateTime.now()));
        for (String query : new String[]{runtime, gallery}) {
            assertThat(query).contains("grant_event.id = i.gallery_consent_id", "grant_event.user_id = c.user_id",
                    "original.version = i.gallery_consent_version", "original.policy_type = 'REVIEW_GALLERY'",
                    "grant_event.agreed = 1", "grant_event.decision_action = 'AGREE'",
                    "stop_event.id > grant_event.id", "stop_event.decision_action = 'WITHDRAW'",
                    "stop_event.decision_action IS NULL AND stop_event.agreed = 0",
                    "current_basis.id = original_basis.id", "middle.consent_basis_version_id IS NULL",
                    "middle.consent_basis_version_id != basis.id", "middle.requires_reconsent = 1");
            assertThat(query).doesNotContain("ORDER BY decision.id DESC LIMIT 1");
        }
        assertThat(runtime).contains("i.gallery_status IN ('PENDING','APPROVED')");
        assertThat(gallery).contains("i.gallery_status = 'APPROVED'", "ORDER BY i.gallery_priority IS NULL", "LIMIT ?");
    }

    @Test void legacyResolutionRequiresUniqueCorroborationWithoutInventedTimeZoneAdjustment() throws Exception {
        String query = sql(configuration(), "DestinationCommentImageMapper.photoRights", Map.of("imageId", 1L));
        assertThat(query).contains("i.gallery_consent_id IS NULL", "COUNT(*)", "legacy_event.user_id = c.user_id",
                "legacy_event.policy_version_id = original.id",
                "MICROSECOND(", "500000", "= 1");
        assertThat(query).doesNotContain("ABS(TIMESTAMPDIFF", "CONVERT_TZ", "INTERVAL 1 SECOND");
    }

    @Test void workflowUsesCanonicalFragmentInsteadOfMemberLatestDecision() throws Exception {
        String source = Files.readString(Path.of("src/main/resources/mapper/ReviewPhotoAnalysisMapper.xml"));
        assertThat(source).contains("DestinationCommentImageMapper.canonicalPhotoConsent",
                "DestinationCommentImageMapper.canonicalPhotoPublicConditions");
        String query = sql(configuration(), "ReviewPhotoAnalysisMapper.workflowSelection",
                Map.of("destinationId", 10L, "ids", java.util.List.of(1L), "lock", true));
        assertThat(query).contains("grant_event.id = i.gallery_consent_id", "AS eligibility_reason", "FOR UPDATE");
    }

    @Test void snapshotInsertAndReadPreserveTheOriginalEventIdAndMicroseconds() throws Exception {
        var configuration = configuration();
        var image = new com.tripbora.model.DestinationCommentImage();
        image.setGalleryConsentId(25L);
        image.setGalleryConsentAt(java.sql.Timestamp.valueOf("2026-11-01 10:00:00.123456"));
        String insert = configuration.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper.insert")
                .getBoundSql(image).getSql();
        assertThat(insert).contains("gallery_consent_id", "gallery_consent_at");
        var read = configuration.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper.findById");
        assertThat(read.getBoundSql(Map.of("id", 1L)).getSql()).contains("gallery_consent_id");
        assertThat(read.getResultMaps().get(0).getResultMappings()).anySatisfy(mapping -> {
            assertThat(mapping.getProperty()).isEqualTo("galleryConsentId");
            assertThat(mapping.getColumn()).isEqualTo("gallery_consent_id");
        });
    }

    @Test void consentRetentionCanDeleteEvidenceWithoutRestoringGalleryEligibility() throws Exception {
        var configuration = configuration();
        String runtime = sql(configuration, "DestinationCommentImageMapper.photoRights", Map.of("imageId", 1L));
        assertThat(runtime).contains("FROM user_policy_consents grant_event", "grant_event.user_id = c.user_id",
                "COUNT(*) FROM user_policy_consents legacy_event", "owner.status = 'ACTIVE'");
        String retention = Files.readString(Path.of("src/main/resources/mapper/UserPolicyConsentMapper.xml"));
        assertThat(retention).contains("DELETE FROM user_policy_consents", "WHERE user_id = #{userId}",
                "u.status = 'DEACTIVATED'", "u.deleted_at &lt;= #{retentionCutoff}");
        // FK SET NULL alone is insufficient: both ID and legacy paths still require a surviving owner event;
        // retention removes all that owner's events and the public boundary also excludes DEACTIVATED.
    }

    @Test void bothAdminQueuesProjectTheSamePhotoEligibilityAndReason() throws Exception {
        var configuration = configuration();
        for (var statement : Map.of("findAdminReviewPhotos", Map.<String, Object>of("ids", java.util.List.of(1L)),
                "listForAdmin", Map.<String, Object>of("status", "PENDING", "offset", 0, "limit", 20)).entrySet()) {
            String query = sql(configuration, "DestinationCommentImageMapper." + statement.getKey(), statement.getValue());
            assertThat(query).contains("AS eligible", "AS eligibility_reason", "PHOTO_CONSENT_INVALID",
                    "grant_event.id = i.gallery_consent_id", "current_basis.id = original_basis.id");
        }
        var gallery = configuration.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper.findApprovedGallery")
                .getBoundSql(Map.of("destinationId", 10L, "limit", 30, "currentTime", LocalDateTime.now()));
        assertThat(gallery.getSql()).contains("CURRENT_TIMESTAMP(6)");
        assertThat(gallery.getParameterMappings()).noneMatch(parameter -> parameter.getProperty().equals("currentTime"));
    }

    static Configuration configuration() throws Exception {
        var configuration = new Configuration();
        load(configuration, "DestinationCommentImageMapper");
        load(configuration, "ReviewPhotoAnalysisMapper");
        return configuration;
    }

    static void load(Configuration configuration, String mapper) throws Exception {
        String path = "src/main/resources/mapper/" + mapper + ".xml";
        try (var input = Files.newInputStream(Path.of(path))) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
    }

    private String sql(Configuration configuration, String mapper, Map<String, Object> arguments) {
        return configuration.getMappedStatement("com.tripbora.repository.comment." + mapper)
                .getBoundSql(arguments).getSql().replaceAll("\\s+", " ");
    }
}
