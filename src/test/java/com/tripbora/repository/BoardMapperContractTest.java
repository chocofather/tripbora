package com.tripbora.repository;

import com.tripbora.dto.BoardListDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoardMapperContractTest {

    /**
     * 북마크 수는 대상 유형과 대상 번호로 묶어 센다.
     *
     * <p>예전에는 POST 와 COURSE 를 한 번에 묶은 뒤 CASE 로 갈라 붙였다. 지금은 분기마다
     * 자기 대상 유형만 센다. 세는 기준과 결과 값은 같고, 다른 유형이 섞이지 않는 것도 같다.
     */
    @Test
    void listAggregatesBookmarksByTargetTypeAndTargetId() throws IOException {
        String mapper = resource("/mapper/BoardMapper.xml");

        assertThat(between(mapper, "<sql id=\"postBookmarkCount\"", "</sql>"))
                .contains("target_type = 'POST'")
                .contains("GROUP BY target_id")
                .contains("postBookmarks.target_id = p.id");
        assertThat(between(mapper, "<sql id=\"courseBookmarkCount\"", "</sql>"))
                .contains("target_type = 'COURSE'")
                .contains("GROUP BY target_id")
                .contains("courseBookmarks.target_id = c.id");

        // 북마크가 없는 글은 0 이다. (예전 COALESCE 계약 그대로)
        assertThat(between(mapper, "<sql id=\"publicPostBranch\"", "</sql>"))
                .contains("COALESCE(postBookmarks.bookmarkCount, 0) AS bookmarkCount");
        assertThat(between(mapper, "<sql id=\"publicCourseBranch\"", "</sql>"))
                .contains("COALESCE(courseBookmarks.bookmarkCount, 0) AS bookmarkCount");

        // 여행지 북마크는 이 목록에 들어오지 않는다.
        assertThat(mapper).doesNotContain("'DESTINATION'");
    }

    @Test
    void bookmarkAggregationDoesNotChangeCountQuery() throws IOException {
        String mapper = resource("/mapper/BoardMapper.xml");
        String countQuery = between(mapper, "<select id=\"countBoard\"", "</select>");

        assertThat(countQuery)
                .contains("SELECT COUNT(*)")
                .doesNotContain("bookmarks")
                .doesNotContain("bookmarkCounts");
    }

    @Test
    void bookmarkSortReusesTheExistingAggregateWithStableTieBreakers() throws IOException {
        String mapper = resource("/mapper/BoardMapper.xml");
        String order = between(mapper, "<sql id=\"publicBoardOrder\"", "</sql>");

        assertThat(order)
                .contains("<when test=\"sort == 'bookmarks'\">")
                .contains("ORDER BY board.bookmarkCount DESC, board.createdAt DESC,"
                        + " board.boardType ASC, board.id DESC");
        // 정렬하려고 북마크를 다시 세지 않는다. 분기가 이미 만들어 둔 값을 그대로 쓴다.
        assertThat(order).doesNotContain("FROM bookmarks");
    }

    @Test
    void boardListDtoCarriesZeroSafeBookmarkCount() {
        BoardListDto dto = new BoardListDto();

        assertThat(dto.getBookmarkCount()).isZero();
        dto.setBookmarkCount(3);
        assertThat(dto.getBookmarkCount()).isEqualTo(3);
    }

    @Test
    void listAndCountShareCourseCountryFiltersWithoutAffectingPosts() throws IOException {
        String mapper = resource("/mapper/BoardMapper.xml");
        String filters = between(mapper, "<sql id=\"courseCountryFilters\"", "</sql>");
        String listQuery = between(mapper, "<select id=\"findBoardList\"", "</select>");
        String countQuery = between(mapper, "<select id=\"countBoard\"", "</select>");

        assertThat(filters)
                .contains("boardType == 'course' and scope == 'domestic'")
                .contains("country.parent_id IS NULL")
                .contains("country_child.code LIKE CONCAT(country.code, '-%')")
                .contains("boardType == 'course' and scope == 'overseas'")
                .contains("JOIN country_categories parent ON parent.id = country.parent_id")
                .contains("parent.parent_id IS NULL")
                .contains("country.code NOT LIKE '%-%'")
                .contains("country.id = #{countryId}")
                .doesNotContain("= 7");
        // 목록과 개수 세기가 같은 조각을 쓴다. 조건이 어긋나면 쪽 수가 맞지 않는다.
        assertThat(listQuery).contains("<include refid=\"publicBoardSource\"/>");
        assertThat(between(mapper, "<sql id=\"publicCourseBranch\"", "</sql>"))
                .contains("<include refid=\"courseCountryFilters\"/>");
        assertThat(countQuery).contains("<include refid=\"courseCountryFilters\"/>");
        // 포함 국가 membership을 먼저 확인하고 선언 행이 없을 때만 legacy 국가를 쓴다.
        assertThat(filters)
                .contains("<include refid=\"courseCountryMembership\"/>")
                .doesNotContain("board.countryId");
        assertThat(between(mapper, "<sql id=\"courseCountryMembership\"", "</sql>"))
                .contains("FROM course_countries course_country")
                .contains("course_country.course_id = c.id")
                .contains("course_country.country_id = country.id")
                .contains("NOT EXISTS (")
                .contains("FROM course_countries declared_country")
                .contains("declared_country.course_id = c.id")
                .contains("AND country.id = c.country_id");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex);
        assertThat(startIndex).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
