package com.tripbora.repository;

import com.tripbora.model.CourseItem;
import com.tripbora.repository.course.CourseStructureMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CourseStructureMapperContractTest {
    private Configuration configuration() throws Exception {
        Configuration config = new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        String path = "mapper/CourseStructureMapper.xml";
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(in, config, path, config.getSqlFragments()).parse();
        }
        return config;
    }

    @Test
    void countriesReadOrderedSetWithLegacyFallbackAndSyncLegacyFromFirstCountry() throws Exception {
        String read = sql("findCountries", Map.of("courseId", 10L));
        assertThat(read).contains("FROM course_countries countries", "countries.course_id = ?",
                "NOT EXISTS (SELECT 1 FROM course_countries existing WHERE existing.course_id = course.id)",
                "ORDER BY display_order ASC, id ASC");
        String write = sql("syncLegacyCountry", Map.of("courseId", 10L));
        assertThat(write).contains("UPDATE courses SET country_id", "FROM course_countries",
                "WHERE course_id = ? ORDER BY display_order ASC LIMIT 1", "WHERE id = ?");
    }

    @Test
    void everyMapperMethodIsBoundAndDayAndItemIdsAreGenerated() throws Exception {
        Configuration config = configuration();
        for (var method : CourseStructureMapper.class.getMethods()) {
            assertThat(config.hasStatement(CourseStructureMapper.class.getName() + "." + method.getName()))
                    .as(method.getName()).isTrue();
        }
        for (String method : List.of("insertDay", "insertItem")) {
            var statement = config.getMappedStatement(CourseStructureMapper.class.getName() + "." + method);
            assertThat(statement.getKeyGenerator()).isInstanceOf(Jdbc3KeyGenerator.class);
            assertThat(statement.getKeyProperties()).containsExactly("id");
        }
    }

    @Test
    void snapshotQueryKeepsMigrationNameFallbackAndUnambiguousCountryResolution() throws Exception {
        String sql = sql("findDestinationSnapshots", Map.of("destinationIds", List.of(3L, 2L)));
        assertThat(sql).contains("WHERE id IN ( ? , ? )", "UNION DISTINCT",
                "region.code = ancestry.country_code", "region.code NOT LIKE '%-%'",
                "parent.parent_id IS NULL", "child.code LIKE CONCAT(region.code, '-%')",
                "HAVING COUNT(DISTINCT region.id) = 1", "NULLIF(TRIM(dt.name), '') IS NOT NULL",
                "WHEN 'ko' THEN 1 WHEN 'en' THEN 2 WHEN 'ja' THEN 3",
                "WHEN 'zh-CN' THEN 4 WHEN 'zh-TW' THEN 5 ELSE 6",
                "END, BINARY dt.language_code, dt.id", "LIMIT 1");
        assertThat(sql).doesNotContain("이름 없는 여행지", "updated_at");
    }

    @Test
    void itemWriteBindsSnapshotFieldsAndReplacementsAreScopedToTheExistingDay() throws Exception {
        var statement = configuration().getMappedStatement(CourseStructureMapper.class.getName() + ".insertItem");
        var bound = statement.getBoundSql(new CourseItem());
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("courseDayId", "displayOrder", "itemType", "destinationId", "countryId",
                        "placeName", "address", "latitude", "longitude", "startTime", "endTime", "memo");
        assertThat(sql("deleteDayItems", Map.of("courseDayId", 50L)))
                .contains("DELETE FROM course_items WHERE course_day_id = ?");
        assertThat(sql("findDayOne", Map.of("courseId", 10L)))
                .contains("course_id = ? AND day_number = 1");
    }

    @Test
    void structuredReadPreservesItemOrderAndNullLinkedSnapshotsWithoutDependingOnLegacy() throws Exception {
        String sql = sql("findCourseStops", Map.of("courseId", 10L));
        assertThat(sql).contains("FROM course_days day", "JOIN course_items item",
                "LEFT JOIN destinations d", "item.place_name", "item.country_id",
                "item.address", "item.latitude", "item.longitude", "country.code AS country_code",
                "d.google_place_id", "region.region_name",
                "TIME_FORMAT(item.start_time, '%H:%i')", "item.memo",
                "ORDER BY day.day_number ASC, item.display_order ASC, item.id ASC");
        assertThat(sql).doesNotContain("course_destinations", "updated_at");
    }

    @Test
    void dayReplacementUsesCourseOwnershipAndCollisionFreeNumberParking() throws Exception {
        assertThat(sql("parkDayNumbers", Map.of("courseId", 10L, "offset", 92)))
                .contains("day_number = day_number + ?", "WHERE course_id = ?", "ORDER BY day_number DESC");
        assertThat(sql("deleteDay", Map.of("courseId", 10L, "dayId", 50L)))
                .contains("WHERE id = ? AND course_id = ?");
        assertThat(sql("findDays", Map.of("courseId", 10L)))
                .contains("ORDER BY day_number ASC, id ASC");
    }

    private String sql(String id, Object params) throws Exception {
        return configuration().getMappedStatement(CourseStructureMapper.class.getName() + "." + id)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ").trim();
    }
}
