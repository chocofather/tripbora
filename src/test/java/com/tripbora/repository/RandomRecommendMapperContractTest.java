package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RandomRecommendMapperContractTest {

    @Test
    void regionTreeRandomQueryResolvesCountryAndExcludesHiddenPaths() throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");
        String query = between(mapper, "<select id=\"findRandomByRegionIds\"", "</select>");

        assertThat(query)
                .contains("WITH RECURSIVE destination_regions")
                .contains("SUBSTRING_INDEX(region.code, '-', 1) AS country_code")
                .contains("JOIN country_categories parent ON parent.id = destination_regions.parent_id")
                .contains("destination_countries.country_id")
                .contains("d.region_id AS region_id")
                .contains("country_name")
                .contains("region_name")
                .contains("is_visible")
                .contains("ORDER BY RAND()")
                .contains("LIMIT #{limit}")
                .doesNotContain("= 7", "=7");
    }

    @Test
    void randomQueriesPickOneStableMainPhotoSoPhotoCountNeverChangesTheOdds() throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");

        for (String id : new String[]{"findRandomByRegion", "findRandomByRegionIds"}) {
            String query = between(mapper, "<select id=\"" + id + "\"", "</select>");
            assertThat(query)
                    .as(id)
                    // 대표 사진 수만큼 후보 행이 늘지 않게 JOIN 하지 않고 한 장만 고른다.
                    .doesNotContain("JOIN destination_images")
                    .contains("SELECT di.image_url")
                    .contains("WHERE di.destination_id = d.id AND di.is_main = 1")
                    // 요청마다 사진이 바뀌지 않게 순서를 고정한다.
                    .contains("ORDER BY di.order_index ASC, di.id ASC")
                    .contains("LIMIT 1")
                    .contains(") AS image_url")
                    // 여행지 추천은 그대로 랜덤이다.
                    .contains("ORDER BY RAND()")
                    .contains("LIMIT #{limit}");
            // destination_images 는 사진을 고르는 서브쿼리 한 곳에서만 읽는다.
            // (사진 존재 조건이 없어 대표 사진이 없는 여행지도 후보에 남는다)
            assertThat(query.split("FROM destination_images", -1)).as(id).hasSize(2);
        }
    }

    @Test
    void randomResultMapKeepsExistingFieldsAndAddsCardLocationFields() throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");
        String resultMap = between(mapper, "<resultMap id=\"RandomDestinationDtoMap\"", "</resultMap>");

        assertThat(resultMap)
                .contains("property=\"destinationId\"")
                .contains("property=\"destinationName\"")
                .contains("property=\"shortDescription\"")
                .contains("property=\"imageUrl\"")
                .contains("property=\"countryId\"")
                .contains("property=\"countryName\"")
                .contains("property=\"regionId\"")
                .contains("property=\"regionName\"");
    }

    @Test
    void eligibleCountryQueryStartsFromActualCountryIdsAndRequiresADisplayableDestination()
            throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");
        String query = between(mapper, "<select id=\"findRandomEligibleCountry\"", "</select>");

        assertThat(query)
                .contains("WITH RECURSIVE country_regions")
                .contains("collection=\"countryIds\"")
                .contains("JOIN destinations")
                .contains("language_code = 'ko'")
                .contains("excludeRegionId")
                .contains("ORDER BY")
                .contains("RAND()")
                .doesNotContain("depth =", "parent_id IN (1,2,3,4,5,6)", "= 7", "=7");
    }

    @Test
    void eligibleRegionQueryAnchorsCandidatesAtTheCountrysDirectChildren() throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");
        String query = between(
                mapper, "<select id=\"findRandomEligibleChildRegion\"", "</select>");

        assertThat(query)
                .contains("WITH RECURSIVE child_regions")
                .contains("child.parent_id = #{countryId}")
                .contains("JOIN destinations")
                .contains("language_code = 'ko'")
                .contains("excludeRegionId")
                .doesNotContain("depth =");
    }

    @Test
    void selectedRegionDescendantsUseOneVisibleRecursiveQuery() throws IOException {
        String mapper = resource("/mapper/RandomRecommendMapper.xml");
        String query = between(
                mapper, "<select id=\"findAllVisibleRegionIdsUnder\"", "</select>");

        assertThat(query)
                .contains("WITH RECURSIVE region_tree")
                .contains("id = #{regionId}")
                .contains("parent_id = region_tree.id")
                .contains("is_visible = 1")
                .doesNotContain("depth =");
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
