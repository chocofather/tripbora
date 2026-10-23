package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HomeFestivalMapperContractTest {
    @Test
    void homeReadsUnfinishedVisiblePeriodsAndFetchesOnlySelectedCardsInOneBatch() throws Exception {
        Configuration configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/TravelInfoMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/TravelInfoMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        // 실제 @Param 호출은 존재하지 않는 키에 예외를 내는 ParamMap을 사용한다.
        var parameters = new MapperMethod.ParamMap<Object>();
        parameters.put("today", LocalDate.of(2026, 10, 2));
        var candidates = configuration.getMappedStatement(
                "com.tripbora.repository.travelinfo.TravelInfoMapper.findHomeFestivalCandidates")
                .getBoundSql(parameters);
        assertThat(candidates.getSql().replaceAll("\\s+", " "))
                .contains("ti.content_type = 'FESTIVAL'", "ic.is_visible = 1",
                        "period.end_date >= ?", "period.start_date <= period.end_date")
                .doesNotContain("CURDATE()", "LIMIT", "info_images");
        assertThat(candidates.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("today");
        parameters.put("periodIds", List.of(10L, 20L));
        var bound = configuration.getMappedStatement(
                "com.tripbora.repository.travelinfo.TravelInfoMapper.findHomeFestivals")
                .getBoundSql(parameters);
        String sql = bound.getSql().replaceAll("\\s+", " ").trim();
        assertThat(sql).contains("ti.content_type = 'FESTIVAL'", "ic.is_visible = 1",
                "period.id IN ( ? , ? )", "ii.is_thumbnail = 1")
                .doesNotContain("CURDATE()", "courses", "ORDER BY period.start_date");
        parameters.put("periodIds", List.of());
        assertThat(configuration.getMappedStatement(
                "com.tripbora.repository.travelinfo.TravelInfoMapper.findHomeFestivals")
                .getBoundSql(parameters).getSql()).contains("AND 1 = 0");
    }
}
