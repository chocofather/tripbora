package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class PublicDestinationDiscoveryMapperContractTest {

    @TestFactory
    Stream<DynamicTest> discoveryAndCountsRequirePublicDestinations() {
        Map<String, List<String>> queries = Map.of(
                "DestinationSearchMapper", List.of("searchDestinations", "searchFilteredDestinations"),
                "GlobalSearchMapper", List.of("search", "count"),
                "DestinationRecommendMapper", List.of("findByCurationCategoryName", "findBySeason",
                        "findByCategoryName", "findHomeLandmarkCandidates", "findTrendingByViewDate", "findSeasonPopularByViewDate"),
                "PopularRecommendMapper", List.of("findPopularDomesticDestinations",
                        "findPopularOverseasDestinations", "findPopularThemeDestinations"),
                "RandomRecommendMapper", List.of("findRandomByRegion", "findRandomByRegionIds",
                        "findRandomEligibleCountry", "findRandomEligibleChildRegion"),
                "SitemapMapper", List.of("findDestinationIds"));
        return queries.entrySet().stream().flatMap(entry -> entry.getValue().stream().map(id ->
                DynamicTest.dynamicTest(entry.getKey() + "." + id, () -> {
                    String sql = sql(entry.getKey(), id);
                    String alias = id.startsWith("findRandomEligible") ? "destination" : "d";
                    assertThat(sql).contains(alias + ".is_public = 1");
                    if (id.equals("findTrendingByViewDate")) {
                        // Hidden high-view rows must not consume the limited candidate slots.
                        String candidates = sql.substring(sql.indexOf("FROM destination_view_daily"));
                        assertThat(candidates.substring(0, candidates.indexOf("LIMIT")))
                                .contains("candidate.is_public = 1");
                    }
                })));
    }

    private String sql(String mapper, String id) throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mapper/" + mapper + ".xml";
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("keyword", "tower");
        parameters.put("keywordPattern", "tower");
        parameters.put("type", "destination");
        parameters.put("languageCode", "en");
        parameters.put("scope", "overseas");
        parameters.put("countryId", 1L);
        parameters.put("regionId", 2L);
        parameters.put("regionIds", List.of(2L));
        parameters.put("countryIds", List.of(1L));
        parameters.put("categoryIds", List.of(3L));
        parameters.put("categoryNames", List.of("autumn"));
        parameters.put("categoryName", "autumn");
        parameters.put("season", "FALL");
        parameters.put("fromDate", LocalDate.of(2026, 10, 1));
        parameters.put("toDate", LocalDate.of(2026, 10, 22));
        parameters.put("limit", 10);
        parameters.put("offset", 0);
        String namespace = configuration.getMappedStatementNames().stream()
                .filter(name -> name.endsWith("." + mapper + "." + id)).findFirst().orElseThrow();
        return configuration.getMappedStatement(namespace).getBoundSql(parameters).getSql()
                .replaceAll("\\s+", " ").trim();
    }
}
