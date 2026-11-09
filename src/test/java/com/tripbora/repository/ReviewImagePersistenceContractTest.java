package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewImagePersistenceContractTest {
    @Test
    void fivePhotoMigrationOnlyWidensOrderCheckAndPreservesRowsAndUniqueness() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20261030_003__increase_destination_comment_image_limit.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("DROP CHECK chk_destination_comment_images_order", "display_order BETWEEN 1 AND 5")
                    .doesNotContain("DELETE FROM", "DROP INDEX", "DROP TABLE", "TRUNCATE");
        }
    }
    private static final String NAMESPACE = "com.tripbora.repository.comment.DestinationCommentImageMapper.";

    @Test
    void mapperParsesAndManagementUsesCurrentReadsWithoutLockingPublicReads() throws Exception {
        Configuration configuration = new Configuration();
        String path = "mapper/DestinationCommentImageMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        assertThat(configuration.getMappedStatement(NAMESPACE + "findByCommentIdForUpdate")
                .getBoundSql(java.util.Map.of("commentId", 1L)).getSql())
                .contains("FOR UPDATE");
        assertThat(configuration.getMappedStatement(NAMESPACE + "findByCommentIds")
                .getBoundSql(java.util.Map.of("commentIds", java.util.List.of(1L))).getSql())
                .doesNotContain("FOR UPDATE");
        assertThat(configuration.getMappedStatement(NAMESPACE + "updateConsent")
                .getBoundSql(java.util.Map.of("imageId", 1L, "consent", true, "version", "v1")).getSql())
                .contains("gallery_status = 'PENDING'", "reviewed_by = NULL", "reviewed_at = NULL");
    }

    @Test
    void migrationPreservesExistingPhotosAndGuardsApprovedConsent() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V20261030_001__add_review_image_moderation.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("DEFAULT 'NONE'", "gallery_consent_withdrawn_at IS NULL")
                    .doesNotContain("CREATE TABLE", "UPDATE destination_comment_images", "destination_images");
        }
    }
}
