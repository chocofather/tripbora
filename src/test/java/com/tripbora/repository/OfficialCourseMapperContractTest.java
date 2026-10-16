package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class OfficialCourseMapperContractTest {
    @Test void adminReadsAndWritesAlwaysRequireActiveOfficialKind() throws Exception {
        for (String id : List.of("findOfficialCourses", "countOfficialCourses", "findOfficialCourse",
                "findOfficialCourseForUpdate", "findOfficialCourseDetail", "updateOfficialCourse")) {
            assertThat(sql(id)).as(id).contains("course_kind = 'OFFICIAL'", "deleted = 0");
        }
    }
    @Test void travelerWritesCannotChangeOfficialCourses() throws Exception {
        for (String id : List.of("updateCourse", "softDeleteCourse", "updateCoverImage")) {
            assertThat(sql(id)).as(id).contains("course_kind = 'TRAVELER'");
        }
    }
    @Test void listUsesCreationTimeAndHasNoPublicStatusRestriction() throws Exception {
        assertThat(sql("findOfficialCourses")).contains("ORDER BY c.created_at DESC, c.id DESC")
                .doesNotContain("updated_at", "status = 'PUBLISHED'");
    }
    @Test void normalOfficialWritesLeaveLegacyStructuredContentUntouched() throws Exception {
        assertThat(sql("insertCourse")).doesNotContain("structured_content");
        assertThat(sql("updateOfficialCourse")).doesNotContain("structured_content");
    }
    private String sql(String id) throws Exception {
        var config = new Configuration();
        String path = "mapper/CourseMapper.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
            new XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        Map<String,Object> p = new HashMap<>();
        p.put("keyword", null); p.put("status", null); p.put("courseIds", List.of(1L));
        p.put("courseId", 1L); p.put("currentUserId", null);
        return config.getMappedStatement("com.tripbora.repository.course.CourseMapper." + id)
                .getBoundSql(p).getSql().replaceAll("\\s+", " ");
    }
}
