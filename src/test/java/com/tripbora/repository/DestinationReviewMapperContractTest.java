package com.tripbora.repository;

import com.tripbora.model.DestinationComment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** MyBatis의 실제 statement를 구성한다. DB 접속·SQL 실행은 하지 않는다. */
class DestinationReviewMapperContractTest {
    private Configuration mapper() throws Exception {
        var config = new Configuration();
        config.getTypeAliasRegistry().registerAlias("DestinationComment", DestinationComment.class);
        try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/DestinationCommentMapper.xml"))) {
            new XMLMapperBuilder(input, config, "DestinationCommentMapper.xml", config.getSqlFragments()).parse();
        }
        return config;
    }
    private String sql(Configuration c, String name, Object args) {
        return c.getMappedStatement("com.tripbora.repository.comment.DestinationCommentMapper." + name)
                .getBoundSql(args).getSql().replaceAll("\\s+", " ").trim();
    }
    @Test void duplicateLookupIsCurrentReadAndReservesActiveModerationButNotDirectDeletion() throws Exception {
        var c = mapper(); var params = new HashMap<String, Object>();
        params.put("userId", 7L); params.put("destinationId", 10L); params.put("excludeId", 20L);
        assertThat(sql(c, "findBlockingReviewId", params))
                .contains("c.parent_comment_id IS NULL", "c.rating IS NOT NULL", "cm.status = 'ACTIVE'",
                        "(c.deleted = 0 OR cm.id IS NOT NULL)", "c.id != ?", "FOR UPDATE");
        params.put("excludeId", null);
        assertThat(sql(c, "findBlockingReviewId", params)).doesNotContain("c.id !=");
    }
    @Test void ratingIsMappedAndPersistedByBothInsertAndUpdate() throws Exception {
        var c = mapper(); var comment = new DestinationComment(); comment.setRating(4);
        assertThat(sql(c, "insert", comment)).contains("parent_comment_id, rating");
        var update = c.getMappedStatement("com.tripbora.repository.comment.DestinationCommentMapper.updateContent").getBoundSql(comment);
        assertThat(update.getParameterMappings()).extracting(m -> m.getProperty()).contains("rating");
        assertThat(c.getResultMap("com.tripbora.repository.comment.DestinationCommentMapper.commentWithWriterMap")
                .getResultMappings()).anySatisfy(m -> assertThat(m.getProperty()).isEqualTo("rating"));
    }
    @Test void reviewCountExcludesRepliesAndModerationAndMigrationPreservesNull() throws Exception {
        assertThat(sql(mapper(), "countVisibleReviews", Map.of("destinationId", 10L)))
                .contains("c.parent_comment_id IS NULL", "c.deleted = 0", "NOT EXISTS", "cm.status = 'ACTIVE'");
        var migration = Files.readString(Path.of("src/main/resources/db/migration/V20261030_004__add_destination_review_rating.sql"));
        assertThat(migration).contains("rating TINYINT UNSIGNED NULL DEFAULT NULL", "rating BETWEEN 1 AND 5",
                "parent_comment_id IS NULL OR rating IS NULL").doesNotContain("UPDATE ", "DELETE ", "DROP ");
    }
}
