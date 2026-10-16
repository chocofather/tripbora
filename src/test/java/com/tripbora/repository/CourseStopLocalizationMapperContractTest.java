package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코스 STOP 조회가 여행지 번호를 함께 내려 주는지 본다.
 *
 * <p>이름 번역은 STOP 마다 다시 읽지 않고 이 번호를 모아 한 번에 읽는다.
 */
class CourseStopLocalizationMapperContractTest {

    @Test
    void legacyMapItemsReadDestinationCoordinatesAndCourseCountryCode() throws Exception {
        var config = new org.apache.ibatis.session.Configuration();
        String path = "mapper/CourseMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(path)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, config, path, config.getSqlFragments()).parse();
        }
        String query = config.getMappedStatement("com.tripbora.repository.course.CourseMapper.findCourseStops")
                .getBoundSql(java.util.Map.of("courseId", 10L)).getSql().replaceAll("\\s+", " ");
        assertThat(query).contains("d.latitude", "d.longitude", "d.google_place_id", "cc.region_name AS region_name", "country.code AS country_code");
        assertThat(config.getResultMap("com.tripbora.repository.course.CourseMapper.CourseStopMap")
                .getResultMappings()).extracting(org.apache.ibatis.mapping.ResultMapping::getProperty)
                .contains("latitude", "longitude", "countryCode", "googlePlaceId");
    }

    @Test
    void courseStopsCarryTheirDestinationIdAndKeepVisitOrder() throws IOException {
        String xml = mapperXml();

        assertThat(xml)
                .contains("<result property=\"destinationId\" column=\"destination_id\"/>")
                .contains("cd.destination_id AS destination_id")
                .contains("ORDER BY cd.visit_order ASC, cd.id ASC");
    }

    @Test
    void homePopularCourseStopsCarryTheirDestinationIdInOneBatch() throws IOException {
        String xml = mapperXml();

        assertThat(xml)
                .contains("<select id=\"findPopularCourseStops\"")
                .contains("stops.destination_id,")
                .contains("<foreach collection=\"courseIds\"")
                .contains("ORDER BY stops.course_id ASC, stops.day_number ASC, stops.display_order ASC, stops.stop_id ASC");
    }

    private String mapperXml() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/mapper/CourseMapper.xml")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
