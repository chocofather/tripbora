package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelPlanDayTitleMapperContractTest {
    @Test void readsIncludeTitleAndUpdateScopesBothPlanAndVersion() throws Exception {
        var configuration = new Configuration();
        String resource = "mapper/TravelPlanMapper.xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        String namespace = "com.tripbora.repository.travelplan.TravelPlanMapper.";
        for (String read : new String[]{"findDaysByPlanId", "findDayByPlanAndId"}) {
            assertThat(configuration.getMappedStatement(namespace + read).getBoundSql(Map.of()).getSql())
                    .contains("title, title_version");
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("travelPlanId", 42L);
        parameters.put("dayId", 100L);
        parameters.put("title", null);
        parameters.put("titleVersion", 2);
        var bound = configuration.getMappedStatement(namespace + "updateDayTitle").getBoundSql(parameters);
        assertThat(bound.getSql().replaceAll("\\s+", " "))
                .contains("title = ?", "title_version = title_version + 1", "WHERE id = ?",
                        "AND travel_plan_id = ?", "AND title_version = ?");
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty())
                .containsExactly("title", "dayId", "travelPlanId", "titleVersion");
    }
}
