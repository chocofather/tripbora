package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationListPerformanceMapperTest {

    @Test
    void listReadsTheSameMainImagesUrlAndPositionInItsExistingQuery() throws IOException {
        Configuration configuration = mapperConfiguration("/mapper/DestinationMapper.xml", "mapper/DestinationMapper.xml");
        for (String sql : List.of(destinationListSql(configuration, "default"), domesticDestinationListSql(configuration, "default"))) {
            assertThat(sql).contains("card_image.image_url AS thumbnailPath",
                    "COALESCE(card_image.card_position_x, 50) AS cardPositionX",
                    "COALESCE(card_image.card_position_y, 50) AS cardPositionY",
                    "WHERE destination_id = d.id AND is_main = 1 ORDER BY id LIMIT 1");
        }
    }

    private static final String COUNTRY_CATEGORY_NAMESPACE =
            "com.tripbora.repository.category.CountryCategoryMapper";
    private static final String DESTINATION_NAMESPACE =
            "com.tripbora.repository.destination.DestinationMapper";

    @Test
    void descendantRegionIdsUseOneRecursiveQueryIncludingTheRoot() throws IOException {
        Configuration configuration = mapperConfiguration(
                "/mapper/CountryCategoryMapper.xml", "mapper/CountryCategoryMapper.xml");

        BoundSql boundSql = configuration.getMappedStatement(
                        COUNTRY_CATEGORY_NAMESPACE + ".findAllRegionIdsUnder")
                .getBoundSql(Map.of("rootRegionId", 7L));

        assertThat(normalize(boundSql.getSql()))
                .isEqualTo("WITH RECURSIVE region_tree AS ("
                        + "SELECT id FROM country_categories WHERE id = ? "
                        + "UNION ALL "
                        + "SELECT child.id FROM country_categories child "
                        + "JOIN region_tree parent ON child.parent_id = parent.id"
                        + ") SELECT id FROM region_tree");
    }

    @Test
    void defaultAndViewsSortDoNotCalculateBookmarkCounts() throws IOException {
        Configuration configuration = mapperConfiguration(
                "/mapper/DestinationMapper.xml", "mapper/DestinationMapper.xml");

        for (String sort : List.of("default", "views")) {
            String sql = destinationListSql(configuration, sort);
            String domesticSql = domesticDestinationListSql(configuration, sort);

            assertThat(sql)
                    .doesNotContain("FROM bookmarks")
                    .doesNotContain("bookmarkCount");
            assertThat(domesticSql)
                    .doesNotContain("FROM bookmarks")
                    .doesNotContain("bookmarkCount");
        }
    }

    @Test
    void bookmarksSortCalculatesAndOrdersByBookmarkCount() throws IOException {
        Configuration configuration = mapperConfiguration(
                "/mapper/DestinationMapper.xml", "mapper/DestinationMapper.xml");

        for (String sql : List.of(
                destinationListSql(configuration, "bookmarks"),
                domesticDestinationListSql(configuration, "bookmarks"))) {
            assertThat(sql)
                    .contains("SELECT COUNT(*) FROM bookmarks")
                    .contains("AS bookmarkCount")
                    .contains("ORDER BY bookmarkCount DESC");
        }
    }

    /**
     * 목록 카드는 짧은 소개만 그린다. 본문(description)은 상세에서만 쓰는데
     * 목록 SELECT 가 함께 읽으면 한 쪽마다 긴 TEXT 를 그만큼 실어 오게 된다.
     */
    @Test
    void regionListDoesNotReadTheLongDescriptionColumn() throws IOException {
        Configuration configuration = mapperConfiguration(
                "/mapper/DestinationMapper.xml", "mapper/DestinationMapper.xml");

        for (String sort : List.of("default", "views", "bookmarks")) {
            String sql = destinationListSql(configuration, sort);

            assertThat(sql).doesNotContain("dt.description");
            // 카드가 실제로 쓰는 값은 그대로 남아 있어야 한다.
            assertThat(sql)
                    .contains("dt.short_description AS shortDescription")
                    .contains("dt.name AS name");
        }
    }

    private String destinationListSql(Configuration configuration, String sort) {
        return normalize(configuration.getMappedStatement(
                        DESTINATION_NAMESPACE + ".findByRegionIdsPaged")
                .getBoundSql(Map.of(
                        "regionIds", List.of(7L, 38L, 235L),
                        "offset", 0,
                        "size", 12,
                        "sort", sort))
                .getSql());
    }

    private String domesticDestinationListSql(Configuration configuration, String sort) {
        return normalize(configuration.getMappedStatement(
                        DESTINATION_NAMESPACE + ".findDomesticPaged")
                .getBoundSql(Map.of(
                        "offset", 0,
                        "size", 12,
                        "sort", sort))
                .getSql());
    }

    private Configuration mapperConfiguration(String resource, String name) throws IOException {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, name,
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
