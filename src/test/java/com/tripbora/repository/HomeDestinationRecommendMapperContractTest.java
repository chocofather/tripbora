package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomeDestinationRecommendMapperContractTest {

    @Test
    void homeRecommendationQueriesExposeStableRegionIdsWithoutChangingSelectionRules()
            throws IOException {
        String popular = resource("/mapper/PopularRecommendMapper.xml");
        String seasonal = resource("/mapper/DestinationRecommendMapper.xml");

        assertThat(popular)
                .contains("property=\"regionId\" column=\"region_id\"")
                .contains("d.region_id AS region_id")
                .contains("ORDER BY (d.views +")
                .contains("ORDER BY RAND()")
                .contains("LIMIT #{limit}");
        assertThat(seasonal)
                .contains("property=\"regionId\"")
                .contains("column=\"region_id\"")
                .contains("d.region_id AS region_id")
                .contains("WHERE d.season = #{season}")
                .contains("ORDER BY RAND()")
                .contains("LIMIT #{limit}");
    }

    @Test
    void seasonCurationTabsSelectByAnyLinkedCategoryNameWithoutRestrictingTheDestinationSeason()
            throws IOException {
        String seasonal = resource("/mapper/DestinationRecommendMapper.xml");
        int start = seasonal.indexOf("<select id=\"findByCurationCategoryName\"");
        assertThat(start).isNotNegative();
        String curation = seasonal.substring(start, seasonal.indexOf("</select>", start));

        assertThat(curation)
                // 카테고리는 번호가 아니라 UNIQUE 이름으로 고른다.
                .contains("JOIN categories c ON c.name = #{categoryName}")
                // 대표 카테고리만이 아니라 연결 전체를 EXISTS 로 본다. (여행지는 한 번만 나온다)
                .contains("WHERE EXISTS (")
                .contains("WHERE dc.destination_id = d.id AND dc.category_id = c.id")
                .doesNotContain("dc.is_main")
                // ALL_SEASONS 여행지도 테마 카테고리가 있으면 나와야 하므로 season 으로 제한하지 않는다.
                .doesNotContain("d.season =")
                .doesNotContain("#{season}")
                .contains("ORDER BY RAND()")
                .contains("LIMIT #{limit}");
    }

    @Test
    void seasonQueriesPickOneStableMainPhotoSoADestinationIsNeverRepeated() throws IOException {
        String seasonal = resource("/mapper/DestinationRecommendMapper.xml");

        for (String id : new String[]{"findByCurationCategoryName", "findBySeason"}) {
            String query = select(seasonal, id);
            assertThat(query)
                    .as(id)
                    // 대표 사진이 여러 장이어도 행이 늘지 않게 JOIN 하지 않고 한 장만 고른다.
                    .doesNotContain("JOIN destination_images")
                    .contains("SELECT di.image_url")
                    .contains("WHERE di.destination_id = d.id AND di.is_main = 1")
                    // 랜드마크·지금 뜨는 여행지와 같은 순서로 고정한다. (요청마다 사진이 바뀌지 않는다)
                    .contains("ORDER BY di.order_index ASC, di.id ASC")
                    .contains("LIMIT 1")
                    .contains(") AS image_url")
                    // 여행지 추천 순서·개수는 그대로다.
                    .contains("ORDER BY RAND()")
                    .contains("LIMIT #{limit}");
            // destination_images 는 사진을 고르는 서브쿼리 한 곳에서만 읽는다.
            // (사진 존재 조건이 없어 대표 사진이 없는 여행지도 image_url null 로 그대로 나온다)
            assertThat(query.split("FROM destination_images", -1)).as(id).hasSize(2);
        }
    }

    private static String select(String mapper, String id) {
        int start = mapper.indexOf("<select id=\"" + id + "\"");
        assertThat(start).as(id).isNotNegative();
        return mapper.substring(start, mapper.indexOf("</select>", start));
    }

    @Test
    void popularQueriesPickTheSameMainPhotoEveryTime() throws IOException {
        String popular = resource("/mapper/PopularRecommendMapper.xml");

        for (String id : new String[]{"findPopularDomesticDestinations",
                "findPopularOverseasDestinations", "findPopularThemeDestinations"}) {
            String query = select(popular, id);
            assertThat(query)
                    .as(id)
                    .doesNotContain("JOIN destination_images")
                    .contains("SELECT di.image_url")
                    .contains("WHERE di.destination_id = d.id AND di.is_main = 1")
                    // 대표 사진이 여러 장이어도 순서(order_index, id)가 가장 앞선 한 장만 쓴다.
                    .contains("ORDER BY di.order_index ASC, di.id ASC")
                    .contains("LIMIT 1")
                    .contains(") AS image_url");
            assertThat(query.split("FROM destination_images", -1)).as(id).hasSize(2);
        }
    }

    @Test
    void homeLandmarksUseTheCategoryNameAStableOrderAndOnlyDestinationsWithAMainPhoto()
            throws IOException {
        String seasonal = resource("/mapper/DestinationRecommendMapper.xml");
        int start = seasonal.indexOf("<select id=\"findByCategoryName\"");
        assertThat(start).isNotNegative();
        String landmarks = seasonal.substring(start, seasonal.indexOf("</select>", start));

        assertThat(landmarks)
                .contains("c.name = #{categoryName}")
                .contains("JOIN destination_translations dt ON d.id = dt.destination_id AND dt.language_code = 'ko'")
                .contains("pcc.region_name AS parent_region_name")
                .contains("di.is_main = 1 AND TRIM(di.image_url) &lt;&gt; ''")
                // 인기 여행지 추천과 같은 점수, 같으면 id 순. 요청마다 바뀌는 순서는 쓰지 않는다.
                .contains("ORDER BY (d.views + (")
                .contains("d.id ASC")
                .contains("LIMIT #{limit}")
                .doesNotContain("RAND()");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
