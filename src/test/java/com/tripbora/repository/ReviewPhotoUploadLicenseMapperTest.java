package com.tripbora.repository;

import com.tripbora.model.DestinationCommentImage;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MyBatis 확장으로 두 허가 근거의 분리와 공통 공개 경계를 검증한다. DB/SQL 실행은 하지 않는다. */
class ReviewPhotoUploadLicenseMapperTest {
    private static final String MAPPER = "com.tripbora.repository.comment.DestinationCommentImageMapper.";

    @Test void originalUploadLicenseSurvivesLaterPolicyRevisionAndOptionalConsentWithdrawal() throws Exception {
        String sql = fragment("canonicalUploadPhotoLicense");
        assertThat(sql).contains("i.gallery_status IN ('PENDING','APPROVED')",
                "upload_policy.id = i.gallery_license_policy_id", "upload_policy.policy_type = 'PHOTO_UPLOAD_NOTICE'",
                "upload_policy.requires_consent = 0", "upload_policy.is_required = 0",
                "upload_policy.published_at <= i.gallery_license_accepted_at",
                "upload_policy.effective_at <= i.gallery_license_accepted_at",
                "i.gallery_license_accepted_at <= i.created_at",
                "i.gallery_consent_id IS NULL", "i.gallery_consent_at IS NULL", "i.gallery_consent_version IS NULL");
        assertThat(sql).doesNotContain("user_policy_consents", "current_policy", "is_active", "REVIEW_GALLERY",
                "gallery_consent_withdrawn_at", "requires_reconsent");
        String wrapper = fragment("canonicalPhotoConsent");
        assertThat(wrapper).contains("OR (i.gallery_license_policy_id IS NULL AND i.gallery_status",
                "FROM user_policy_consents grant_event", "stop_event.id > grant_event.id");
    }

    @Test void queuesGalleryAndWorkflowShareBothRightsBranchesAndPublicDurationBoundaries() throws Exception {
        var configuration = CanonicalPhotoConsentMapperTest.configuration();
        Map<String, Map<String, Object>> statements = Map.of(
                MAPPER + "photoRights", Map.of("imageId", 1L),
                MAPPER + "findApprovedGallery", Map.of("destinationId", 10L, "limit", 30),
                MAPPER + "findAdminReviewPhotos", Map.of("ids", java.util.List.of(1L)),
                "com.tripbora.repository.comment.ReviewPhotoAnalysisMapper.workflowSelection",
                Map.of("destinationId", 10L, "ids", java.util.List.of(1L), "lock", true));
        for (var entry : statements.entrySet()) {
            String sql = configuration.getMappedStatement(entry.getKey()).getBoundSql(entry.getValue()).getSql()
                    .replaceAll("\\s+", " ");
            assertThat(sql).contains("upload_policy.id = i.gallery_license_policy_id", "grant_event.id = i.gallery_consent_id",
                    "c.parent_comment_id IS NULL AND c.deleted = 0", "owner.status = 'ACTIVE'",
                    "destination.is_public = 1", "moderation.status = 'ACTIVE'");
        }
        String gallery = configuration.getMappedStatement(MAPPER + "findApprovedGallery")
                .getBoundSql(Map.of("destinationId", 10L, "limit", 30)).getSql();
        assertThat(gallery.indexOf("gallery_license_policy_id")).isLessThan(gallery.indexOf("LIMIT ?"));
        assertThat(gallery).contains("ORDER BY i.gallery_priority IS NULL, i.gallery_priority ASC",
                "c.created_at DESC, c.id DESC, i.display_order ASC, i.id ASC");
    }

    @Test void newLicenseIsNotReportedAsMissingLegacyConsentOrMissingCurrentLegacyPolicy() throws Exception {
        String sql = fragment("canonicalPhotoRestrictionReason");
        assertThat(sql).contains("WHEN i.gallery_license_policy_id IS NULL AND i.gallery_consent_withdrawn_at IS NOT NULL",
                "WHEN i.gallery_license_policy_id IS NULL AND (i.gallery_consent_at IS NULL OR i.gallery_consent_version IS NULL)",
                "WHEN i.gallery_license_policy_id IS NULL AND ( SELECT policy.id");
        String withdrawal = Files.readString(Path.of("src/main/resources/mapper/UserPolicyConsentMapper.xml"));
        assertThat(withdrawal.substring(withdrawal.indexOf("<update id=\"revokeGalleryImages\">"),
                withdrawal.indexOf("</update>", withdrawal.indexOf("<update id=\"revokeGalleryImages\">"))))
                .contains("AND i.gallery_license_policy_id IS NULL");
    }

    @Test void snapshotPersistsBothNewFieldsWithoutSynthesizingLegacyConsent() throws Exception {
        var configuration = CanonicalPhotoConsentMapperTest.configuration();
        var image = new DestinationCommentImage();
        image.setGalleryLicensePolicyId(100L);
        image.setGalleryLicenseAcceptedAt(LocalDateTime.parse("2026-11-06T10:00:00.123456"));
        var insert = configuration.getMappedStatement(MAPPER + "insert").getBoundSql(image);
        assertThat(insert.getSql()).contains("gallery_license_policy_id", "gallery_license_accepted_at");
        assertThat(insert.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .contains("galleryLicensePolicyId", "galleryLicenseAcceptedAt");
        assertThat(image.getGalleryConsentId()).isNull();
        assertThat(image.getGalleryConsentAt()).isNull();
        assertThat(image.getGalleryConsentVersion()).isNull();
        var read = configuration.getMappedStatement(MAPPER + "findById");
        assertThat(read.getBoundSql(Map.of("id", 1L)).getSql()).contains("gallery_license_policy_id", "gallery_license_accepted_at");
        assertThat(read.getResultMaps().get(0).getResultMappings()).anySatisfy(mapping -> {
            assertThat(mapping.getProperty()).isEqualTo("galleryLicenseAcceptedAt");
            assertThat(mapping.getColumn()).isEqualTo("gallery_license_accepted_at");
            assertThat(mapping.getJavaType()).isEqualTo(LocalDateTime.class);
        });
    }

    @Test void migrationOnlyAddsNullableEvidenceAndNewNoticeWithoutChangingExistingRows() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/migration/V20261106_001__add_review_photo_upload_license.sql"));
        assertThat(sql).contains("gallery_license_policy_id BIGINT NULL", "gallery_license_accepted_at DATETIME(6) NULL",
                "REFERENCES policy_versions (id) ON DELETE RESTRICT", "chk_destination_comment_image_license_pair",
                "DROP CHECK chk_destination_comment_image_gallery_consent",
                "ADD CONSTRAINT chk_destination_comment_image_gallery_consent",
                "('PHOTO_UPLOAD_NOTICE', '20261106-v1', 0, 0, 0,");
        assertThat(sql).doesNotContainPattern("(?im)^\\s*(UPDATE|DELETE|DROP TABLE|DROP COLUMN)\\b");
        for (String locale : new String[]{"ko", "en", "ja", "zh-CN", "zh-TW"}) {
            assertThat(sql).contains("SELECT id, '" + locale + "'");
        }
        String statements = sql.replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        String licensePair = statements.substring(statements.indexOf("ADD CONSTRAINT chk_destination_comment_image_license_pair"),
                statements.indexOf(';', statements.indexOf("ADD CONSTRAINT chk_destination_comment_image_license_pair")));
        // MySQL forbids CHECK references to an existing ON DELETE SET NULL FK column.
        assertThat(licensePair).doesNotContain("gallery_consent_id");
        assertThat(statements.split(";[\\t ]*(?:\\r?\\n|$)\\s*")).hasSize(11);
        assertThat(statements).doesNotContain("'REVIEW_GALLERY'", "INSERT INTO user_policy_consents");
        for (String statement : statements.split(";[\\t ]*(?:\\r?\\n|$)\\s*")) {
            if (statement.contains("DROP CHECK")) {
                assertThat(statement).doesNotContain("ADD CONSTRAINT", "ADD COLUMN");
            }
        }
    }

    private String fragment(String name) throws Exception {
        Configuration configuration = CanonicalPhotoConsentMapperTest.configuration();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">"
                + "<mapper namespace=\"uploadLicenseProbe\"><select id=\"check\" resultType=\"boolean\">SELECT "
                + "<include refid=\"" + MAPPER + name + "\"><property name=\"photo\" value=\"i\"/>"
                + "<property name=\"comment\" value=\"c\"/></include></select></mapper>";
        try (var input = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            new XMLMapperBuilder(input, configuration, "upload-license-probe.xml", configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("uploadLicenseProbe.check").getBoundSql(Map.of()).getSql()
                .replaceAll("\\s+", " ");
    }
}
