package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import com.tripbora.dto.DestinationGalleryPosition;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationGalleryPagingMapperTest {
    private Configuration configuration() throws Exception {
        var configuration = new Configuration();
        for (String file : new String[]{"DestinationMapper", "DestinationCommentImageMapper"}) {
            try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/" + file + ".xml"))) {
                new XMLMapperBuilder(input, configuration, file, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }

    @Test void publicDetailDoesNotJoinOrLoadAllImages() throws Exception {
        var config = configuration();
        var statement = config.getMappedStatement("com.tripbora.repository.destination.DestinationMapper.findPublicDestinationDetail");
        String sql = statement.getBoundSql(10L).getSql();
        assertThat(sql).contains("d.*").doesNotContain("destination_images");
        assertThat(statement.getResultMaps().get(0).hasNestedResultMaps()).isFalse();
    }

    @Test void bothKeysetDirectionsReuseRightsAndBoundQueriesWithoutOffset() throws Exception {
        var config = configuration();
        var parameters = new HashMap<String, Object>();
        parameters.put("destinationId", 10L);
        parameters.put("limit", 31);
        parameters.put("anchor", null);
        for (boolean reverse : new boolean[]{false, true}) {
            parameters.put("reverse", reverse);
            String sql = config.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper.findGalleryCandidates")
                    .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("i.gallery_status = 'APPROVED'", "upload_policy.policy_type = 'PHOTO_UPLOAD_NOTICE'",
                    "original.policy_type = 'REVIEW_GALLERY'", "owner.status = 'ACTIVE'", "destination.is_public = 1",
                    "moderation.status = 'ACTIVE'", "LIMIT ?").doesNotContain("OFFSET");
            assertThat(sql).contains(reverse ? "i.id DESC" : "i.id ASC");
        }
        String admin = config.getMappedStatement("com.tripbora.repository.destination.DestinationMapper.findGalleryCandidates")
                .getBoundSql(parameters).getSql();
        assertThat(admin).contains("destination_image_sources", "img_custom_attribution", "is_public = 1", "LIMIT ?")
                .doesNotContain("OFFSET");
    }

    @Test void nonNullSeekAnchorsBindEveryTieBreakerInBothDirectionsIncludingNullPriority() throws Exception {
        var config = configuration();
        var anchor = new DestinationGalleryPosition();
        anchor.setSection("R"); anchor.setImageId(45L); anchor.setCommentId(12L); anchor.setDisplayOrder(2);
        anchor.setReviewCreatedAt(LocalDateTime.of(2026, 11, 6, 12, 0, 0, 123456000));
        anchor.setOrderIndex(3);
        var parameters = new HashMap<String, Object>();
        parameters.put("destinationId", 10L); parameters.put("limit", 31); parameters.put("anchor", anchor);
        for (Integer priority : new Integer[]{null, 1, 30}) {
            anchor.setPriority(priority);
            for (boolean reverse : new boolean[]{false, true}) {
                parameters.put("reverse", reverse);
                var bound = config.getMappedStatement("com.tripbora.repository.comment.DestinationCommentImageMapper.findGalleryCandidates")
                        .getBoundSql(parameters);
                String sql = bound.getSql().replaceAll("\\s+", " ");
                assertThat(sql).contains(reverse ? "i.id < ?" : "i.id > ?",
                        reverse ? "c.id > ?" : "c.id < ?", "i.display_order = ?", "COALESCE(c.created_at,")
                        .doesNotContain("${", "OFFSET", "123456");
                assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                        .contains("anchor.priorityNull", "anchor.priorityValue", "anchor.reviewCreatedAt",
                                "anchor.commentId", "anchor.displayOrder", "anchor.imageId");
                assertThat(sql.chars().filter(c -> c == '(').count()).isEqualTo(sql.chars().filter(c -> c == ')').count());
                String admin = config.getMappedStatement("com.tripbora.repository.destination.DestinationMapper.findGalleryCandidates")
                        .getBoundSql(parameters).getSql();
                assertThat(admin).contains(reverse ? "di.id < ?" : "di.id > ?", "di.order_index = ?");
            }
        }
    }
}
