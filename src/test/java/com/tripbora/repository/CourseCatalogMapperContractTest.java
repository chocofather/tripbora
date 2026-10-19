package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CourseCatalogMapperContractTest {
    @Test
    void countAndPageShareVisibilityAndIndependentMembershipFilters() throws Exception {
        for (String id : List.of("countCourses", "findCourses")) {
            String query = sql(id, Map.of("kind", "OFFICIAL", "countryId", 20L, "themeId", 3L));
            assertThat(query).contains("c.deleted = 0", "c.deleted_at IS NULL", "c.status = 'PUBLISHED'",
                    "c.course_kind = ?", "FROM course_countries membership",
                    "membership.course_id = c.id", "membership.country_id = ?",
                    "FROM course_theme_mappings mapping", "mapping.course_id = c.id", "mapping.theme_id = ?")
                    .doesNotContain("c.country_id", "JOIN course_countries", "JOIN course_theme_mappings");
        }
    }

    @Test
    void allKindsWithoutOptionalFiltersRemainPublicAndUseStableLatestOrder() throws Exception {
        String query = sql("findCourses", Map.of());
        assertThat(query).contains("c.course_kind IN ('OFFICIAL', 'TRAVELER')",
                        "ORDER BY c.created_at DESC, c.id DESC LIMIT ? OFFSET ?")
                .doesNotContain("membership.country_id =", "mapping.theme_id =", "c.course_kind = ?");
    }

    @Test
    void viewsSortUsesStableTieBreakAndPageNeverJoinsManyToManyMetadata() throws Exception {
        String query = sql("findCourses", Map.of("sort", "views"));
        assertThat(query).contains("ORDER BY c.views DESC, c.created_at DESC, c.id DESC LIMIT ? OFFSET ?")
                .doesNotContain("JOIN course_countries", "JOIN course_theme_mappings", "GROUP BY")
                .contains("SELECT COUNT(*) FROM course_days", "AS day_count");
    }

    @Test
    void coverUsesSharedPolicyAndFirstLinkedDayOneItemWithoutSkippingMissingImages() throws Exception {
        String query = sql("findCourses", Map.of());
        assertThat(query).contains("NULLIF(TRIM(c.cover_image_url), '')", "representative.day_number = 1",
                "ORDER BY representative.display_order ASC, representative.stop_id ASC LIMIT 1",
                "JOIN destinations linked ON linked.id = item.destination_id", "UNION ALL",
                "FROM course_destinations cd", "existing_day.course_id = cd.course_id",
                "ORDER BY CASE WHEN di.is_main = 1 THEN 0 ELSE 1 END, di.order_index ASC, di.id ASC");
    }

    @Test
    void countryMetadataIsOneOrderedBatchFromMembershipOnly() throws Exception {
        String query = sql("findCountriesByCourseIds", Map.of());
        assertThat(query).contains("FROM course_countries membership",
                "membership.course_id IN ( ? , ? )", "ORDER BY membership.course_id, membership.display_order")
                .doesNotContain("FROM courses", "c.country_id");
    }

    @Test
    void regionPathsUseLinkedStopsAndAncestorsInOneBatchWithVisitOrderAndLegacyFallback() throws Exception {
        String query = sql("findRegionsByCourseIds", Map.of());
        assertThat(query).contains("WITH RECURSIVE", "JOIN course_items item ON item.course_day_id = day.id",
                        "JOIN destinations linked ON linked.id = item.destination_id",
                        "FROM course_destinations cd", "NOT EXISTS", "existing_item.course_day_id = existing_day.id",
                        "stop.course_id IN ( ? , ? )", "region.id = destination.region_id",
                        "parent.id = path.parent_id", "AS country_node",
                        "NULLIF(TRIM(path.region_code), '') IS NOT NULL", "path.region_code NOT LIKE '%-%'",
                        "ancestor_parent.parent_id IS NULL", "country_child.parent_id = path.region_id",
                        "country_child.code LIKE CONCAT(path.region_code, '-%')",
                        "ORDER BY course_id, day_number, display_order, stop_id")
                .doesNotContain("address", "country_id = 1", "LIMIT", "OFFSET", "SUBSTRING_INDEX", "country_code");
    }

    private String sql(String id, Map<String, Object> overrides) throws Exception {
        return statementSql("com.tripbora.repository.course.CourseCatalogMapper." + id, overrides);
    }

    @Test
    void detailAndCatalogResolveExactlyTheSameRegionQuery() throws Exception {
        assertThat(statementSql("com.tripbora.repository.course.CourseMapper.findRegionsByCourseIds", Map.of()))
                .isEqualTo(sql("findRegionsByCourseIds", Map.of()));
    }

    private String statementSql(String statement, Map<String, Object> overrides) throws Exception {
        Configuration configuration = new Configuration();
        for (String path : List.of("mapper/CourseMapper.xml", "mapper/CourseCatalogMapper.xml")) {
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
                assertThat(input).as(path).isNotNull();
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        Map<String, Object> values = new HashMap<>();
        values.put("kind", "ALL"); values.put("countryId", null); values.put("themeId", null);
        values.put("sort", "latest"); values.put("limit", 12); values.put("offset", 12);
        values.put("courseIds", List.of(10L, 20L)); values.putAll(overrides);
        return configuration.getMappedStatement(statement)
                .getBoundSql(values).getSql().replaceAll("\\s+", " ").trim();
    }
}
