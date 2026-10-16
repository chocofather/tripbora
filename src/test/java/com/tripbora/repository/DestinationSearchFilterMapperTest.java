package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 MyBatis 동적 SQL 생성까지만 검증한다. DB에 연결하거나 SQL을 실행하지 않는다. */
class DestinationSearchFilterMapperTest {
    private BoundSql query(String statement, String keyword, String scope, Long countryId, Long regionId) throws Exception {
        Configuration configuration = new Configuration();
        try (InputStream input = getClass().getResourceAsStream("/mapper/DestinationSearchMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DestinationSearchMapper.xml", configuration.getSqlFragments()).parse();
        }
        var parameters = new HashMap<String, Object>();
        parameters.put("keyword", keyword); parameters.put("limit", 20);
        parameters.put("scope", scope); parameters.put("countryId", countryId); parameters.put("regionId", regionId);
        return configuration.getMappedStatement("com.tripbora.repository.search.DestinationSearchMapper." + statement).getBoundSql(parameters);
    }

    @Test
    void legacyAndKeywordOnlySearchDoNotRestrictGeography() throws Exception {
        for (String statement : new String[]{"searchDestinations", "searchFilteredDestinations"}) {
            var sql = query(statement, "에펠탑", null, null, null).getSql();
            assertThat(sql).contains("dt.name LIKE", "LIMIT ?")
                    .doesNotContain("AND dc.country_id =", "country.code = 'KR'", "country.code != 'KR'", "ancestor.region_id =");
        }
    }

    @Test
    void scopeUsesCountryCodeAndRegionIncludesDescendantsWithAndConditions() throws Exception {
        var domestic = query("searchFilteredDestinations", "성", "domestic", 50L, 60L);
        assertThat(domestic.getSql()).contains("country.code = 'KR'", "AND dc.country_id = ?",
                "ancestor.destination_id = d.id AND ancestor.region_id = ?", "dt.name LIKE");
        assertThat(domestic.getParameterMappings().stream().map(mapping -> mapping.getProperty()))
                .contains("keyword", "countryId", "regionId", "limit");
        assertThat(query("searchFilteredDestinations", "", "overseas", null, null).getSql())
                .contains("country.code != 'KR'").doesNotContain("AND dc.country_id = ?");
        assertThat(query("searchFilteredDestinations", "성", "overseas", 80L, null).getSql())
                .contains("country.code != 'KR'", "AND dc.country_id = ?", "dt.name LIKE");
    }
}
