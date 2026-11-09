package com.tripbora.repository;

import com.tripbora.model.DestinationComment;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DestinationReviewPhase2MapperTest {
    private Configuration mapper() throws Exception {
        var c = new Configuration();
        c.getTypeAliasRegistry().registerAlias("DestinationComment", DestinationComment.class);
        try (var input = Files.newInputStream(Path.of("src/main/resources/mapper/DestinationCommentMapper.xml"))) {
            new XMLMapperBuilder(input, c, "DestinationCommentMapper.xml", c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void ratingSortsKeepUnscoredLastAndHaveStableTies() throws Exception {
        for (String sort : new String[]{"rating_desc", "rating_asc"}) {
            String sql = mapper().getMappedStatement("com.tripbora.repository.comment.DestinationCommentMapper.findPagedParentComments")
                    .getBoundSql(Map.of("destinationId", 10L, "sort", sort, "limit", 5, "offset", 5))
                    .getSql().replaceAll("\\s+", " ");
            assertThat(sql).contains("sort_rating IS NULL", "sort_rating " + (sort.endsWith("desc") ? "DESC" : "ASC"),
                    "c.created_at DESC, c.id DESC", "LIMIT ? OFFSET ?");
        }
    }

    @Test void statisticsUseOneAggregateAndTheSamePublicRootFilterForAllMetrics() throws Exception {
        var statement = mapper().getMappedStatement("com.tripbora.repository.comment.DestinationCommentMapper.findReviewStatistics");
        String sql = statement.getBoundSql(Map.of("destinationId", 10L)).getSql().replaceAll("\\s+", " ");
        assertThat(sql).contains("COUNT(*) AS review_count", "COUNT(c.rating) AS rating_count", "AVG(c.rating) AS average_rating",
                "c.parent_comment_id IS NULL", "c.deleted = 0", "AND NOT EXISTS", "cm.status = 'ACTIVE'");
        for (int score = 1; score <= 5; score++) assertThat(sql).contains("COALESCE(SUM(c.rating = " + score + "), 0)");
        assertThat(sql).doesNotContain("JOIN destination_comment_images", "ROUND(", "c.rating IS NOT NULL");
    }

    @Test void deepLinkLatestPageCountsModeratedPlaceholderRoots() throws Exception {
        String sql = mapper().getMappedStatement("com.tripbora.repository.comment.DestinationCommentMapper.countRootCommentsBefore")
                .getBoundSql(Map.of("destinationId", 10L, "rootId", 30L)).getSql().replaceAll("\\s+", " ");
        assertThat(sql).contains("preceding.deleted = 0 OR EXISTS", "cm.target_id = preceding.id", "cm.status = 'ACTIVE'");
    }
}
