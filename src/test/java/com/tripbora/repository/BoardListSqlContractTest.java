package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커뮤니티 목록이 만들어 내는 SQL 의 계약.
 *
 * <p>여기서 고정하는 것은 두 가지다.
 * 하나는 <b>결과가 달라지지 않는다는 것</b> — 걸러내는 조건(soft delete, 글 유형, 국가)과
 * 정렬 기준(동점일 때의 차례까지)이 예전 그대로인지 본다.
 * 다른 하나는 <b>읽는 양이 줄었다는 것</b> — 필요 없는 UNION 분기를 아예 만들지 않고,
 * 행마다 도는 COUNT 서브쿼리를 쓰지 않으며, 개수 세기가 목록용 값들을 계산하지 않는지 본다.
 *
 * <p>이 저장소에는 테스트용 DB 가 없어 실제 행을 비교할 수는 없다. 그래서 결과를 정하는
 * 술어와 정렬식을 문자열로 고정한다. ({@code DestinationListPerformanceMapperTest} 와 같은 방식)
 */
class BoardListSqlContractTest {

    private static final String NAMESPACE =
            "com.tripbora.repository.board.BoardMapper";

    // ---------- 분기 선택 ----------

    @Test
    void wholeBoardReadsBothTablesExactlyOnce() throws IOException {
        String sql = listSql(params(null, null, "all", null, "latest"));

        assertThat(sql).contains("FROM user_posts");
        assertThat(sql).contains("FROM courses");
        assertThat(countOf(sql, "UNION ALL")).isEqualTo(1);
    }

    @Test
    void postBoardNeverReadsTheCoursesTable() throws IOException {
        String sql = listSql(params("post", null, "all", null, "latest"));

        assertThat(sql).contains("FROM user_posts");
        assertThat(sql).doesNotContain("FROM courses");
        assertThat(sql).doesNotContain("UNION ALL");
    }

    @Test
    void courseBoardNeverReadsTheUserPostsTable() throws IOException {
        String sql = listSql(params("course", null, "all", null, "latest"));

        assertThat(sql).contains("FROM courses");
        assertThat(sql).doesNotContain("FROM user_posts");
        assertThat(sql).doesNotContain("UNION ALL");
    }

    @Test
    void postTypeAloneStillMeansThePostBoard() throws IOException {
        String sql = listSql(params(null, "TIP", "all", null, "latest"));

        assertThat(sql).contains("FROM user_posts");
        assertThat(sql).doesNotContain("FROM courses");
        assertThat(sql).doesNotContain("UNION ALL");
    }

    /** 코스 게시판에 글 유형을 함께 주면 예전에도 결과가 없었다. 그 계약을 그대로 둔다. */
    @Test
    void courseBoardWithPostTypeKeepsReturningNothing() throws IOException {
        Map<String, Object> parameters = params("course", "TIP", "all", null, "latest");

        assertThat(listSql(parameters)).contains("1 = 0");
        assertThat(countSql(parameters)).contains("1 = 0");
    }

    // ---------- 걸러내는 조건 (결과 보존) ----------

    @Test
    void softDeleteConditionSurvivesOnEveryBranch() throws IOException {
        assertThat(listSql(params(null, null, "all", null, "latest")))
                .contains("p.deleted = 0")
                .contains("c.deleted = 0");
        assertThat(listSql(params("post", null, "all", null, "latest")))
                .contains("p.deleted = 0");
        assertThat(listSql(params("course", null, "all", null, "latest")))
                .contains("c.deleted = 0");
    }

    @Test
    void postTypeIsFilteredOnTheSourceTableInsteadOfTheDerivedTable() throws IOException {
        String sql = listSql(params("post", "QUESTION", "all", null, "latest"));

        assertThat(sql).contains("p.post_type = ?");
        // 파생 테이블을 다 만든 뒤 바깥에서 거르지 않는다.
        assertThat(sql).doesNotContain("board.postType =");
        assertThat(sql).doesNotContain("board.boardType =");
    }

    @Test
    void domesticCourseScopeMatchesAnyDeclaredCountryWithLegacyFallback() throws IOException {
        String sql = listSql(params("course", null, "domestic", null, "latest"));

        assertThat(sql).contains("country.id = c.country_id");
        assertThat(sql).contains("country.parent_id IS NULL");
        assertThat(sql).doesNotContain("board.countryId");
        assertCountryMembership(sql);
    }

    @Test
    void overseasCourseScopeKeepsTheCountryNarrowing() throws IOException {
        String withoutCountry = listSql(params("course", null, "overseas", null, "latest"));
        String withCountry = listSql(params("course", null, "overseas", 38L, "latest"));

        assertThat(withoutCountry)
                .contains("country.parent_id IS NOT NULL")
                .contains("country.code NOT LIKE '%-%'")
                .doesNotContain("AND country.id = ?");
        assertThat(withCountry).contains("AND country.id = ?");
        assertCountryMembership(withoutCountry);
        assertCountryMembership(withCountry);
    }

    @Test
    void countryMembershipIsIdenticalForListAndCountAndNeverJoinsOuterCourseRows() throws IOException {
        for (String scope : List.of("domestic", "overseas")) {
            for (Long countryId : List.of(38L, 39L)) {
                Map<String, Object> parameters = params("course", null, scope, countryId, "latest");
                String list = listSql(parameters);
                String count = countSql(parameters);
                assertCountryMembership(list);
                assertCountryMembership(count);
                assertThat(countryFilterOf(list)).isEqualTo(countryFilterOf(count));
                assertThat(list).doesNotContain("JOIN course_countries");
                assertThat(count).doesNotContain("JOIN course_countries");
            }
        }
        assertThat(listSql(params("course", null, "all", null, "latest")))
                .doesNotContain("course_countries");
        assertThat(countSql(countParams("course", null, "all", null)))
                .doesNotContain("course_countries");
    }

    @Test
    void courseScopeIsNotAppliedToThePostBoard() throws IOException {
        String sql = listSql(params("post", null, "domestic", null, "latest"));

        assertThat(sql).doesNotContain("country_categories");
    }

    // ---------- 정렬 (동점 차례까지) ----------

    /**
     * 정렬식은 글자 하나까지 예전 그대로다. 정렬 기준과 동점일 때의 차례가 곧 결과 순서라,
     * 이 문자열이 같으면 같은 자료에서 같은 차례가 나온다.
     */
    @Test
    void everySortKeepsItsOrderingAndTieBreakers() throws IOException {
        assertThat(orderOf(listSql(params(null, null, "all", null, "latest"))))
                .isEqualTo("ORDER BY board.createdAt DESC, board.boardType ASC, board.id DESC");
        assertThat(orderOf(listSql(params(null, null, "all", null, "oldest"))))
                .isEqualTo("ORDER BY board.createdAt ASC, board.boardType ASC, board.id ASC");
        assertThat(orderOf(listSql(params(null, null, "all", null, "views"))))
                .isEqualTo("ORDER BY board.views DESC, board.createdAt DESC,"
                        + " board.boardType ASC, board.id DESC");
        assertThat(orderOf(listSql(params(null, null, "all", null, "comments"))))
                .isEqualTo("ORDER BY board.commentCount DESC, board.createdAt DESC,"
                        + " board.boardType ASC, board.id DESC");
        assertThat(orderOf(listSql(params(null, null, "all", null, "bookmarks"))))
                .isEqualTo("ORDER BY board.bookmarkCount DESC, board.createdAt DESC,"
                        + " board.boardType ASC, board.id DESC");
    }

    /** 분기를 하나만 읽을 때도 정렬식은 같아야 한다. */
    @Test
    void singleBranchKeepsTheSameOrdering() throws IOException {
        for (String boardType : List.of("post", "course")) {
            assertThat(orderOf(listSql(params(boardType, null, "all", null, "views"))))
                    .as("boardType=%s", boardType)
                    .isEqualTo("ORDER BY board.views DESC, board.createdAt DESC,"
                            + " board.boardType ASC, board.id DESC");
        }
    }

    @Test
    void paginationStaysOffsetBased() throws IOException {
        assertThat(listSql(params(null, null, "all", null, "latest")))
                .endsWith("LIMIT ?, ?");
    }

    // ---------- 행마다 도는 COUNT 제거 ----------

    @Test
    void commentCountIsAggregatedOnceInsteadOfPerRow() throws IOException {
        String sql = listSql(params(null, null, "all", null, "latest"));

        // 예전 형태: 행마다 (SELECT COUNT(*) FROM post_comments pc WHERE pc.post_id = p.id ...)
        assertThat(sql).doesNotContain("pc.post_id = p.id");
        assertThat(sql).doesNotContain("cc.course_id = c.id");
        // 한 번에 묶어 세고 붙인다.
        assertThat(sql)
                .contains("GROUP BY post_id")
                .contains("GROUP BY course_id");
    }

    @Test
    void bookmarkCountIsAggregatedPerTargetTypeOfTheBranch() throws IOException {
        assertThat(listSql(params("post", null, "all", null, "latest")))
                .contains("target_type = 'POST'")
                .doesNotContain("target_type = 'COURSE'");
        assertThat(listSql(params("course", null, "all", null, "latest")))
                .contains("target_type = 'COURSE'")
                .doesNotContain("target_type = 'POST'");
    }

    /** 화면이 모든 정렬에서 북마크 수를 보여 준다. 값이 빠지면 안 된다. */
    @Test
    void bookmarkCountIsStillSelectedForEverySort() throws IOException {
        for (String sort : List.of("latest", "oldest", "views", "comments", "bookmarks")) {
            assertThat(listSql(params(null, null, "all", null, sort)))
                    .as("sort=%s", sort)
                    .contains("bookmarkCount");
        }
    }

    // ---------- 개수 세기 ----------

    @Test
    void countDoesNotBuildAnyOfTheListOnlyValues() throws IOException {
        for (Map<String, Object> parameters : List.of(
                countParams(null, null, "all", null),
                countParams("post", null, "all", null),
                countParams("course", null, "all", null))) {
            assertThat(countSql(parameters))
                    .doesNotContain("nickname")
                    .doesNotContain("commentCount")
                    .doesNotContain("bookmarkCount")
                    .doesNotContain("FROM bookmarks")
                    .doesNotContain("post_comments")
                    .doesNotContain("course_comments")
                    .doesNotContain("JOIN users");
        }
    }

    @Test
    void countReadsOnlyTheTableThatTheBoardTypeNeeds() throws IOException {
        assertThat(countSql(countParams("post", null, "all", null)))
                .contains("FROM user_posts")
                .doesNotContain("FROM courses");
        assertThat(countSql(countParams("course", null, "all", null)))
                .contains("FROM courses")
                .doesNotContain("FROM user_posts");
        assertThat(countSql(countParams(null, null, "all", null)))
                .contains("FROM user_posts")
                .contains("FROM courses");
    }

    /** 세는 조건과 뽑는 조건이 어긋나면 쪽 수가 맞지 않는다. */
    @Test
    void countAndListShareTheSameFilters() throws IOException {
        assertThat(countSql(countParams("post", "TIP", "all", null)))
                .contains("p.deleted = 0")
                .contains("p.post_type = ?");
        assertThat(countSql(countParams("course", null, "domestic", null)))
                .contains("c.deleted = 0")
                .contains("country.id = c.country_id")
                .contains("country.parent_id IS NULL");
        assertThat(countSql(countParams("course", null, "overseas", 38L)))
                .contains("country.parent_id IS NOT NULL")
                .contains("AND country.id = ?");
    }

    // ---------- 공개 프로필 목록(같은 mapper, 이번 작업 대상 아님) ----------

    @Test
    void profileListKeepsItsOwnFiltersAndOrder() throws IOException {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("userId", 7L);
        parameters.put("boardType", null);
        parameters.put("postType", null);
        parameters.put("offset", 0L);
        parameters.put("limit", 10);

        String sql = sqlOf("findBoardListByUserId", parameters);

        assertThat(sql)
                .contains("p.post_type IN ('QUESTION', 'TIP')")
                .contains("p.deleted_at IS NULL")
                .contains("c.deleted_at IS NULL");
        assertThat(orderOf(sql))
                .isEqualTo("ORDER BY board.createdAt DESC, board.boardType ASC, board.id DESC");
    }

    // ---------- 도우미 ----------

    private void assertCountryMembership(String sql) {
        assertThat(sql).contains("EXISTS (SELECT 1 FROM course_countries course_country"
                + " WHERE course_country.course_id = c.id AND course_country.country_id = country.id)");
        assertThat(sql).contains("OR (NOT EXISTS (SELECT 1 FROM course_countries declared_country"
                + " WHERE declared_country.course_id = c.id) AND country.id = c.country_id)");
    }

    private String countryFilterOf(String sql) {
        int start = sql.indexOf("AND EXISTS (SELECT 1 FROM country_categories country");
        assertThat(start).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf(" ORDER BY", start);
        if (end < 0) end = sql.length();
        // 목록의 파생 테이블 닫힘은 필터 바깥이다.
        return sql.substring(start, end).replaceFirst("\\) board$", "").trim();
    }

    private Map<String, Object> params(String boardType, String postType,
                                       String scope, Long countryId, String sort) {
        Map<String, Object> parameters = countParams(boardType, postType, scope, countryId);
        parameters.put("sort", sort);
        parameters.put("offset", 0L);
        parameters.put("limit", 10);
        return parameters;
    }

    private Map<String, Object> countParams(String boardType, String postType,
                                            String scope, Long countryId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("boardType", boardType);
        parameters.put("postType", postType);
        parameters.put("scope", scope);
        parameters.put("countryId", countryId);
        return parameters;
    }

    private String listSql(Map<String, Object> parameters) throws IOException {
        return sqlOf("findBoardList", parameters);
    }

    private String countSql(Map<String, Object> parameters) throws IOException {
        return sqlOf("countBoard", parameters);
    }

    private String sqlOf(String statement, Map<String, Object> parameters) throws IOException {
        return normalize(mapperConfiguration()
                .getMappedStatement(NAMESPACE + "." + statement)
                .getBoundSql(parameters)
                .getSql());
    }

    /** ORDER BY 부터 LIMIT 앞까지. 정렬식만 떼어 비교한다. */
    private String orderOf(String sql) {
        int start = sql.indexOf("ORDER BY");
        assertThat(start).as("ORDER BY 가 없다: %s", sql).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf(" LIMIT", start);
        return (end < 0 ? sql.substring(start) : sql.substring(start, end)).trim();
    }

    private int countOf(String sql, String token) {
        int count = 0;
        for (int i = sql.indexOf(token); i >= 0; i = sql.indexOf(token, i + token.length())) {
            count++;
        }
        return count;
    }

    private Configuration mapperConfiguration() throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream("/mapper/BoardMapper.xml")) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, "mapper/BoardMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private String normalize(String sql) {
        return sql.replaceAll("\\s+", " ")
                .replaceAll("\\( ", "(")
                .replaceAll(" \\)", ")")
                .trim();
    }
}
