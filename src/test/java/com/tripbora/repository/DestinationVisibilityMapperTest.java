package com.tripbora.repository;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SQL 생성만 검증하며 DB 연결이나 실행은 하지 않는다. */
class DestinationVisibilityMapperTest {
    private String sql(String id, Map<String, Object> parameters) throws Exception {
        Configuration configuration = new Configuration();
        try (var input = getClass().getResourceAsStream("/mapper/DestinationMapper.xml")) {
            new XMLMapperBuilder(input, configuration, "mapper/DestinationMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        return configuration.getMappedStatement("com.tripbora.repository.destination.DestinationMapper." + id)
                .getBoundSql(parameters).getSql().replaceAll("\\s+", " ");
    }

    private Map<String, Object> parameters() {
        var p = new HashMap<String, Object>();
        p.put("regionIds", List.of(3L, 4L));
        p.put("categoryIds", List.of(5L));
        p.put("selectedCategoryIds", List.of(5L));
        p.put("keyword", "서울");
        p.put("destinationType", "ATTRACTION");
        p.put("dataStatus", "missing_image");
        p.put("sort", "name");
        p.put("size", 30);
        p.put("offset", 30);
        p.put("publicVisible", false);
        return p;
    }

    @Test
    void publicListsCountsAndCategoryCountsExcludeHiddenRowsBeforePaging() throws Exception {
        for (String id : List.of("findByRegionIdsPaged", "countByRegionIds", "findDomesticPaged",
                "countDomestic", "findDomestic", "findSimilarDestinations", "findCategoryFilterCounts")) {
            var p = parameters();
            p.put("limit", 5);
            assertThat(sql(id, p)).as(id).contains("d.is_public = 1");
        }
    }

    @Test
    void adminFilterCombinesWithDataStatusSearchAndPagingAndUsesSameCountCondition() throws Exception {
        for (boolean visible : List.of(true, false)) {
            var p = parameters();
            p.put("publicVisible", visible);
            for (String id : List.of("findAdminDestinationPage", "countAdminDestinations",
                    "countAdminDestinationDataStatus")) {
                assertThat(sql(id, p)).as(id).contains("d.is_public = ?", "d.type = ?", "dt.name LIKE");
            }
        }
        var all = parameters();
        all.put("publicVisible", null);
        assertThat(sql("countAdminDestinations", all)).doesNotContain("d.is_public =");
        assertThat(sql("findAdminDestinationPage", all)).contains("d.is_public AS publicVisible", "LIMIT ? OFFSET ?");
    }

    @Test
    void hiddenCountIgnoresVisibilityWhileExistingStatusCountsRetainItInOneAggregate() throws Exception {
        var p = parameters();
        p.put("publicVisible", true);
        String aggregate = sql("countAdminDestinationDataStatus", p);
        assertThat(aggregate).contains("COALESCE(SUM(s.hidden), 0) AS hidden", "d.is_public = 0 AS hidden",
                "d.is_public = ? AS matchesVisibility", "SUM(s.missingImage * s.matchesVisibility)",
                "SUM(s.matchesVisibility)");
        assertThat(aggregate.substring(aggregate.indexOf("FROM destinations d")))
                .contains("d.type = ?", "dt.name LIKE")
                .doesNotContain("d.is_public =", "destination_images", "LIMIT");
        p.put("publicVisible", null);
        assertThat(sql("countAdminDestinationDataStatus", p)).contains("1 AS matchesVisibility");
    }

    @Test
    void visibilityMutationDoesNotTouchOtherDataAndNormalEditsCannotResetIt() throws Exception {
        assertThat(sql("updateVisibility", parameters())).isEqualTo(
                "UPDATE destinations SET is_public = ? WHERE id = ?");
        assertThat(sql("updateDestination", parameters())).doesNotContain("is_public");
        assertThat(sql("insertDestination", parameters())).doesNotContain("is_public");
        // 관리자 및 기존 코스/계획이 참조하는 ID 조회는 숨김 상태와 무관하게 유지한다.
        assertThat(sql("findById", parameters())).doesNotContain("is_public =");
        assertThat(sql("findDestinationDetail", parameters())).doesNotContain("is_public =");
    }

    @Test
    void migrationKeepsExistingRowsPublicWithoutChangingReferences() throws Exception {
        String migration;
        try (var input = getClass().getResourceAsStream(
                "/db/migration/V20261022_001__add_destination_public_visibility.sql")) {
            migration = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertThat(migration).contains("is_public TINYINT(1) NOT NULL DEFAULT 1")
                .doesNotContain("DELETE ", "DROP ", "UPDATE ");
    }
}
