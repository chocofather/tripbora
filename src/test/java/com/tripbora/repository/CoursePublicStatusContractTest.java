package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 연결 없이 실제 MyBatis 동적 SQL의 공개 범위를 검증한다. */
class CoursePublicStatusContractTest {
    @Test
    void publicDetailHomeAndTranslationRequirePublished() throws Exception {
        for (String id : List.of("incrementViews", "findCourseDetail", "findPopularCourses",
                "findPopularCourseStops", "findVisibleTranslationSource",
                "findVisibleTranslationSourceForUpdate")) {
            assertThat(sql("CourseMapper", id)).as(id).contains("status = 'PUBLISHED'");
        }
    }

    @Test
    void publicSearchSitemapBookmarksAndCommentsRequirePublished() throws Exception {
        for (String[] query : List.of(
                new String[]{"GlobalSearchMapper", "search"},
                new String[]{"GlobalSearchMapper", "count"},
                new String[]{"SitemapMapper", "findCourseIds"},
                new String[]{"MyPageBookmarkMapper", "findCommunityBookmarks"},
                new String[]{"MyPageBookmarkMapper", "countCommunityBookmarks"},
                new String[]{"CourseCommentMapper", "existsActiveCourse"},
                new String[]{"CourseCommentMapper", "findVisibleTranslationSource"},
                new String[]{"CourseCommentMapper", "findVisibleTranslationSourceForUpdate"})) {
            assertThat(sql(query[0], query[1])).as(String.join(".", query))
                    .contains("status = 'PUBLISHED'");
        }
    }

    @Test
    void publicBoardListAndEveryCountBranchRequirePublished() throws Exception {
        for (String boardType : List.of("course", "all")) {
            Map<String, Object> params = params();
            params.put("boardType", boardType.equals("all") ? null : boardType);
            for (String id : List.of("findBoardList", "countBoard")) {
                assertThat(sql("BoardMapper", id, params)).as(id + boardType)
                        .contains("c.status = 'PUBLISHED'");
            }
        }
    }

    @Test
    void ownershipAndModerationQueriesRetainNonPublicAccess() throws Exception {
        for (String id : List.of("findActiveCourse", "findActiveCourseForUpdate")) {
            assertThat(sql("CourseMapper", id)).doesNotContain("status = 'PUBLISHED'");
        }
        assertThat(sql("BoardMapper", "findBoardListByUserId"))
                .doesNotContain("status = 'PUBLISHED'");
    }

    @Test
    void publicProfileFiltersStatusWhileMyPageCountRemainsUnfiltered() throws Exception {
        assertThat(sql("BoardMapper", "findPublicBoardListByUserId"))
                .contains("c.status = 'PUBLISHED'");
        assertThat(sql("BoardMapper", "countPublicBoardByUserId"))
                .contains("c.status = 'PUBLISHED'");
        assertThat(sql("BoardMapper", "countBoardByUserId"))
                .doesNotContain("status = 'PUBLISHED'");
    }

    private String sql(String mapper, String id) throws Exception {
        return sql(mapper, id, params());
    }

    private String sql(String mapper, String id, Map<String, Object> params) throws Exception {
        Configuration configuration = new Configuration();
        String path = "mapper/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        String statement = configuration.getMappedStatementNames().stream()
                .filter(name -> name.endsWith("." + id)).findFirst().orElseThrow();
        return configuration.getMappedStatement(statement).getBoundSql(params).getSql()
                .replaceAll("\\s+", " ");
    }

    private Map<String, Object> params() {
        Map<String, Object> params = new HashMap<>();
        params.put("courseId", 1L);
        params.put("currentUserId", null);
        params.put("courseIds", List.of(1L));
        params.put("languageCode", "ko");
        params.put("keywordPattern", "여행");
        params.put("boardType", "course");
        params.put("postType", null);
        params.put("scope", "all");
        params.put("sort", "latest");
        params.put("type", "course");
        params.put("userId", 1L);
        params.put("limit", 10);
        params.put("offset", 0);
        return params;
    }
}
