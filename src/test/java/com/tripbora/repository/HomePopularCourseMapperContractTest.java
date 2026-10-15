package com.tripbora.repository;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HomePopularCourseMapperContractTest {

    @Test
    void popularCoursesAreFilteredAndRankedBeforeTheBoundLimit() throws IOException {
        String mapper = resource("/mapper/CourseMapper.xml");
        String query = between(mapper, "<select id=\"findPopularCourses\"", "</select>");

        assertThat(query)
                .contains("FROM courses c")
                .contains("JOIN users u ON u.id = c.user_id")
                .contains("LEFT JOIN (")
                .contains("COUNT(stops.destination_id) AS destination_count")
                .contains("c.deleted = 0")
                .contains("c.deleted_at IS NULL")
                .contains("GROUP BY c.id")
                .contains("ORDER BY c.views DESC, c.created_at DESC, c.id DESC")
                .contains("LIMIT #{limit}");
    }

    @Test
    void previewStopsAndTheirRepresentativeImagesAreLoadedInOneBatchAndVisitOrder()
            throws Exception {
        String query = sql("findPopularCourseStops");

        assertThat(query)
                .contains("FROM course_days day JOIN course_items item")
                .contains("JOIN destinations linked ON linked.id = item.destination_id")
                .contains("dt.language_code = 'ko'")
                .contains("FROM destination_images di")
                .contains("di.is_main = 1")
                .contains("ORDER BY di.order_index ASC, di.id ASC")
                .contains("AS image_url")
                .contains("AND stops.course_id IN ( ? , ? )")
                .contains("ORDER BY stops.course_id ASC, stops.day_number ASC, stops.display_order ASC, stops.stop_id ASC")
                .doesNotContain("LIMIT 3");
    }

    @Test
    void legacyFallbackDependsOnAllDaysItemsRatherThanLinkedPreviewOrTimestamps() throws Exception {
        for (String id : List.of("findPopularCourses", "findPopularCourseStops")) {
            assertThat(sql(id)).as(id)
                    .contains("UNION ALL", "FROM course_destinations cd", "WHERE NOT EXISTS ( SELECT 1 FROM course_days existing_day JOIN course_items existing_item ON existing_item.course_day_id = existing_day.id WHERE existing_day.course_id = cd.course_id )")
                    .doesNotContain("existing_day.day_number = 1", "existing_item.destination_id IS NOT NULL", "updated_at");
        }
    }

    @Test
    void representativeImageUsesCoverThenExactlyTheFirstLinkedItemOfDayOne() throws Exception {
        String query = sql("findPopularCourses");
        assertThat(query)
                .contains("NULLIF(TRIM(c.cover_image_url), '')")
                .contains("representative.day_number = 1")
                .contains("ORDER BY representative.display_order ASC, representative.stop_id ASC LIMIT 1")
                .contains("AS representative_image_url");
        String representative = between(query, "SELECT representative.destination_id", ") ");
        assertThat(representative).doesNotContain("destination_images", "image_url");
    }

    private String sql(String id) throws Exception {
        Configuration configuration = new Configuration();
        String path = "mapper/CourseMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.tripbora.repository.course.CourseMapper." + id)
                .getBoundSql(Map.of("limit", 3, "courseIds", List.of(10L, 20L)))
                .getSql().replaceAll("\\s+", " ").trim();
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
