package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ApprovedReviewGalleryMapperTest {
    @Test void continuationUsesExactlyTheSameRightsVisibilityAndOrderingBeforePaging() throws Exception {
        var config = CanonicalPhotoConsentMapperTest.configuration();
        String mapper="com.tripbora.repository.comment.DestinationCommentImageMapper.";
        var parameters=Map.of("destinationId",10L,"limit",30,"offset",30L);
        String first=config.getMappedStatement(mapper+"findApprovedGallery").getBoundSql(parameters)
                .getSql().replaceAll("\\s+"," ").trim();
        var page=config.getMappedStatement(mapper+"findApprovedGalleryPage").getBoundSql(parameters);
        assertThat(page.getSql().replaceAll("\\s+"," ").trim()).isEqualTo(first+" OFFSET ?");
        assertThat(page.getParameterMappings()).extracting(mapping->mapping.getProperty())
                .containsExactly("destinationId","limit","offset");
        assertThat(first).contains("upload_policy.policy_type = 'PHOTO_UPLOAD_NOTICE'",
                "original.policy_type = 'REVIEW_GALLERY'", "i.gallery_status = 'APPROVED'",
                "owner.status = 'ACTIVE'", "destination.is_public = 1", "moderation.status = 'ACTIVE'");
    }
    @Test void galleryQueryRequiresApprovalCurrentConsentAndPublicContent() throws Exception {
        var config = new Configuration();
        try(var input = Files.newInputStream(Path.of("src/main/resources/mapper/DestinationCommentImageMapper.xml"))) {
            new XMLMapperBuilder(input, config, "gallery", config.getSqlFragments()).parse();
        }
        String name="com.tripbora.repository.comment.DestinationCommentImageMapper.findApprovedGallery";
        assertThat(config.hasStatement(name)).isTrue();
        String sql=config.getMappedStatement(name).getBoundSql(Map.of("destinationId",10L,"limit",30,"currentTime",LocalDateTime.now()))
                .getSql().replaceAll("\\s+"," ");
        assertThat(sql).contains("i.gallery_status = 'APPROVED'", "i.gallery_consent_at IS NOT NULL",
                "i.gallery_consent_version IS NOT NULL", "i.gallery_consent_withdrawn_at IS NULL",
                "c.deleted = 0", "c.parent_comment_id IS NULL",
                "grant_event.user_id = c.user_id", "grant_event.agreed = 1",
                "original.version = i.gallery_consent_version", "current_basis.id = original_basis.id",
                "stop_event.id > grant_event.id", "stop_event.decision_action = 'WITHDRAW'",
                "ORDER BY i.gallery_priority IS NULL, i.gallery_priority ASC, c.created_at DESC, c.id DESC, i.display_order ASC, i.id ASC", "LIMIT ?");
        assertThat(sql).doesNotContain("destination_images", "c.rating IS NOT NULL", "ORDER BY decision.id DESC LIMIT 1");
        // 원본 동의 근거로 필터링한 다음에만 메인 갤러리 개수 제한을 적용한다.
        assertThat(sql.indexOf("grant_event.user_id = c.user_id")).isLessThan(sql.lastIndexOf("LIMIT ?"));
    }
}
