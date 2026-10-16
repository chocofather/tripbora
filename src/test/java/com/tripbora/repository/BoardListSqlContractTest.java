package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 연결 없이 실제 동적 SQL의 조회 범위와 집계·정렬 계약을 확인한다. */
class BoardListSqlContractTest {
    @Test
    void publicListAndCountReadPostsOnlyForEveryLegacyBoardFilter() throws Exception {
        for (String boardType : List.of("all", "post", "course", "invalid")) {
            for (String scope : List.of("all", "domestic", "overseas")) {
                for (String statement : List.of("findBoardList", "countBoard")) {
                    var parameters = params();
                    parameters.put("boardType", "all".equals(boardType) ? null : boardType);
                    parameters.put("scope", scope);
                    parameters.put("countryId", 8L);
                    assertThat(sql(statement, parameters)).as("%s/%s/%s", statement, boardType, scope)
                            .contains("FROM user_posts p", "p.deleted = 0")
                            .doesNotContain("FROM courses", "course_comments", "course_countries",
                                    "country_categories", "UNION", "target_type = 'COURSE'", "AND 1 = 0");
                }
            }
        }
    }

    @Test
    void listAndCountSharePostTypeFiltersAndUseBoundParameters() throws Exception {
        for (String type : List.of("QUESTION", "TIP")) {
            var parameters = params();
            parameters.put("postType", type);
            for (String statement : List.of("findBoardList", "countBoard")) {
                assertThat(sql(statement, parameters)).contains("p.post_type = ?").doesNotContain(type);
            }
        }
        assertThat(sql("findBoardList", params())).doesNotContain("p.post_type = ?");
        assertThat(sql("countBoard", params())).doesNotContain("p.post_type = ?");
    }

    @Test
    void allSortsKeepStableTieBreakersAndOffsetPagination() throws Exception {
        Map<String, String> orders = Map.of(
                "latest", "board.createdAt DESC, board.boardType ASC, board.id DESC",
                "oldest", "board.createdAt ASC, board.boardType ASC, board.id ASC",
                "views", "board.views DESC, board.createdAt DESC, board.boardType ASC, board.id DESC",
                "comments", "board.commentCount DESC, board.createdAt DESC, board.boardType ASC, board.id DESC",
                "bookmarks", "board.bookmarkCount DESC, board.createdAt DESC, board.boardType ASC, board.id DESC");
        for (var order : orders.entrySet()) {
            var parameters = params();
            parameters.put("sort", order.getKey());
            assertThat(sql("findBoardList", parameters))
                    .endsWith("ORDER BY " + order.getValue() + " LIMIT ?, ?");
        }
    }

    @Test
    void listAggregatesPostMetricsOnceAndCountSkipsListOnlyWork() throws Exception {
        assertThat(sql("findBoardList", params()))
                .contains("GROUP BY post_id", "GROUP BY target_id", "target_type = 'POST'",
                        "COALESCE(postComments.commentCount, 0)", "COALESCE(postBookmarks.bookmarkCount, 0)")
                .doesNotContain("pc.post_id = p.id");
        assertThat(sql("countBoard", params()))
                .isEqualTo("SELECT COUNT(*) FROM user_posts p WHERE p.deleted = 0");
    }

    @Test
    void authoredListsRetainTravelerCoursesAndPublicProfileRequiresPublished() throws Exception {
        for (String statement : List.of("findBoardListByUserId", "countBoardByUserId",
                "findPublicBoardListByUserId", "countPublicBoardByUserId")) {
            String query = sql(statement, params());
            assertThat(query).contains("FROM courses c", "c.course_kind = 'TRAVELER'", "p.post_type IN ('QUESTION', 'TIP')");
            if (statement.contains("Public")) {
                assertThat(query).contains("c.status = 'PUBLISHED'");
            } else {
                assertThat(query).doesNotContain("c.status = 'PUBLISHED'");
            }
        }
    }

    private Map<String, Object> params() {
        Map<String, Object> result = new HashMap<>();
        result.put("boardType", null);
        result.put("postType", null);
        result.put("scope", "all");
        result.put("countryId", null);
        result.put("sort", "latest");
        result.put("userId", 7L);
        result.put("offset", 20L);
        result.put("limit", 10);
        return result;
    }

    private String sql(String statement, Map<String, Object> parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/BoardMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "mapper/BoardMapper.xml", configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.tripbora.repository.board.BoardMapper." + statement)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(").replaceAll(" \\)", ")").trim();
    }
}
