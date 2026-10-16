package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL은 생성까지만 확인하며 DB에 접속하거나 실행하지 않는다. */
class CourseThemeMapperTest {
    @Test
    void readIsOneOrderedBatchAndWritesBindOnlyValidatedIdsAndOrder() throws Exception {
        var configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/CourseThemeMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/CourseThemeMapper.xml", configuration.getSqlFragments()).parse();
        }
        String namespace = "com.tripbora.repository.course.CourseThemeMapper.";
        var batch = configuration.getMappedStatement(namespace + "findByCourseIds").getBoundSql(Map.of("courseIds", List.of(10L, 20L)));
        assertThat(batch.getSql().replaceAll("\\s+", " "))
                .contains("mapping.course_id IN ( ? , ? )", "ORDER BY mapping.course_id, mapping.display_order", "theme.is_active = TRUE");
        var insert = configuration.getMappedStatement(namespace + "insertMapping")
                .getBoundSql(Map.of("courseId", 10L, "themeId", 7L, "displayOrder", 2));
        assertThat(insert.getSql()).contains("is_active = TRUE");
        assertThat(insert.getParameterMappings().stream().map(value -> value.getProperty()))
                .containsExactly("courseId", "displayOrder", "themeId");
        assertThat(configuration.getMappedStatement(namespace + "deleteMappings").getBoundSql(Map.of("courseId", 10L)).getSql())
                .contains("WHERE course_id = ?");
    }
}
