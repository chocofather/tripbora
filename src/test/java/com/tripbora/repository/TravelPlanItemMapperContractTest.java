package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 일정 조회/등록 계약.
 * 컬럼명은 docs/db/tripbora_schema_reference.md 의 실제 구조와 일치해야 한다.
 */
class TravelPlanItemMapperContractTest {

    @Test
    void everyDeclaredMapperMethodHasAStatement() throws IOException {
        String mapper = mapperXml();
        String mapperInterface = Files.readString(
                Path.of("src/main/java/com/tripbora/repository/travelplan/"
                        + "TravelPlanItemMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains(
                "namespace=\"com.tripbora.repository.travelplan.TravelPlanItemMapper\"");
        for (String id : new String[]{
                "findByDayId", "findByPlanId", "findMaxDisplayOrder", "insertItem",
                "findByIdAndDayId", "updateContent", "deleteByIdAndDayId",
                "resequenceDisplayOrder", "findPreviousItem", "findNextItem",
                "updateDisplayOrderWithVersion", "updateDisplayOrderById",
                "moveToDayWithVersion"}) {
            assertThat(mapperInterface).as("interface declares %s", id).contains(id);
            assertThat(mapper).as("xml defines %s", id).contains("id=\"" + id + "\"");
        }
    }

    @Test
    void itemsComeBackInDisplayOrder() throws IOException {
        String select = between(mapperXml(), "<select id=\"findByDayId\"", "</select>");

        assertThat(select)
                .contains("FROM travel_plan_items")
                .contains("WHERE travel_plan_day_id = #{travelPlanDayId}")
                .contains("ORDER BY display_order ASC, id ASC")
                .doesNotContain("${");
    }

    @Test
    void theLastOrderIsZeroWhenTheDayIsEmpty() throws IOException {
        String select = between(mapperXml(), "<select id=\"findMaxDisplayOrder\"", "</select>");

        assertThat(select)
                .contains("resultType=\"int\"")
                .contains("SELECT COALESCE(MAX(display_order), 0)")
                .contains("FROM travel_plan_items")
                .contains("WHERE travel_plan_day_id = #{travelPlanDayId}");
    }

    @Test
    void insertWritesOnlyTheColumnsThisStageOwns() throws IOException {
        String insert = between(mapperXml(), "<insert id=\"insertItem\"", "</insert>");

        assertThat(insert)
                .contains("useGeneratedKeys=\"true\"")
                .contains("keyProperty=\"id\"")
                .contains("INSERT INTO travel_plan_items")
                .contains("travel_plan_day_id, content, tag, start_time, detail_json, display_order, created_by_member_id")
                .contains("#{travelPlanDayId}, #{content}, #{tag}, #{startTime}")
                .contains("#{details,jdbcType=VARCHAR,typeHandler=com.tripbora.repository.travelplan.TravelPlanItemDetailsTypeHandler}")
                .contains("#{displayOrder}, #{createdByMemberId}");
        // version / created_at / updated_at 은 DB DEFAULT 에 맡긴다
        assertThat(insert)
                .doesNotContain("version")
                .doesNotContain("created_at")
                .doesNotContain("updated_at")
                .doesNotContain("${");
    }

    @Test
    void itemColumnsExistInTheSchemaReference() throws IOException {
        String schema = Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
        String items = between(schema, "CREATE TABLE `travel_plan_items`", ") ENGINE=InnoDB");

        for (String column : new String[]{
                "travel_plan_day_id", "content", "tag", "display_order",
                "created_by_member_id", "version"}) {
            assertThat(items).as("travel_plan_items.%s", column).contains("`" + column + "`");
        }
    }

    @Test
    void theItemLookupIsScopedToItsDay() throws IOException {
        String select = between(mapperXml(), "<select id=\"findByIdAndDayId\"", "</select>");

        // 다른 DAY 의 itemId 를 섞어 넣어도 통과하지 못하게 두 조건을 모두 건다
        assertThat(select)
                .contains("FROM travel_plan_items")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{travelPlanDayId}")
                .contains("version")
                .doesNotContain("${");
    }

    @Test
    void updateOnlyLandsWhenTheVersionStillMatches() throws IOException {
        String update = between(mapperXml(), "<update id=\"updateContent\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plan_items")
                .contains("SET content = #{content}")
                // 낙관적 잠금: 버전이 그대로일 때만 반영되고, 성공하면 1 증가한다
                .contains("version = version + 1")
                .contains("updated_at = CURRENT_TIMESTAMP")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{travelPlanDayId}")
                .contains("AND version = #{version}")
                .doesNotContain("${");
    }

    @Test
    void deleteAndResequenceStayInsideTheirOwnDay() throws IOException {
        String mapper = mapperXml();

        assertThat(between(mapper, "<delete id=\"deleteByIdAndDayId\"", "</delete>"))
                .contains("DELETE FROM travel_plan_items")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{travelPlanDayId}");

        String resequence = between(mapper, "<update id=\"resequenceDisplayOrder\"", "</update>");
        assertThat(resequence)
                .contains("UPDATE travel_plan_items item")
                .contains("ROW_NUMBER() OVER (ORDER BY display_order ASC, id ASC)")
                .contains("SET item.display_order = ordered.new_order")
                // 해당 DAY 밖의 순서는 건드리지 않는다
                .contains("WHERE travel_plan_day_id = #{travelPlanDayId}")
                .contains("WHERE item.travel_plan_day_id = #{travelPlanDayId}")
                .doesNotContain("${");
    }

    @Test
    void theNeighbourLookupsPickTheNearestItemInTheSameDay() throws IOException {
        String mapper = mapperXml();

        String previous = between(mapper, "<select id=\"findPreviousItem\"", "</select>");
        assertThat(previous)
                .contains("FROM travel_plan_items")
                .contains("WHERE travel_plan_day_id = #{travelPlanDayId}")
                .contains("AND display_order &lt; #{displayOrder}")
                // 바로 위 한 건만 필요하다
                .contains("ORDER BY display_order DESC, id DESC")
                .contains("LIMIT 1")
                .doesNotContain("${");

        String next = between(mapper, "<select id=\"findNextItem\"", "</select>");
        assertThat(next)
                .contains("WHERE travel_plan_day_id = #{travelPlanDayId}")
                .contains("AND display_order &gt; #{displayOrder}")
                .contains("ORDER BY display_order ASC, id ASC")
                .contains("LIMIT 1")
                .doesNotContain("${");
    }

    @Test
    void reorderUpdatesStayInsideTheDayAndBumpTheVersion() throws IOException {
        String mapper = mapperXml();

        String guarded = between(mapper, "<update id=\"updateDisplayOrderWithVersion\"", "</update>");
        assertThat(guarded)
                .contains("UPDATE travel_plan_items")
                .contains("SET display_order = #{displayOrder}")
                .contains("version = version + 1")
                .contains("updated_at = CURRENT_TIMESTAMP")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{travelPlanDayId}")
                // 옮기려는 일정은 낙관적 잠금을 통과해야 한다
                .contains("AND version = #{version}")
                .doesNotContain("${");

        // 자리를 비켜 주는 이웃은 버전 조건 없이 옮긴다
        String neighbour = between(mapper, "<update id=\"updateDisplayOrderById\"", "</update>");
        assertThat(neighbour)
                .contains("SET display_order = #{displayOrder}")
                .contains("version = version + 1")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{travelPlanDayId}")
                .doesNotContain("AND version = #{version}")
                .doesNotContain("${");
    }

    @Test
    void theDayMoveKeepsContentAndOwnershipUntouched() throws IOException {
        String update = between(mapperXml(), "<update id=\"moveToDayWithVersion\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plan_items")
                .contains("SET travel_plan_day_id = #{targetDayId}")
                .contains("display_order = #{displayOrder}")
                .contains("version = version + 1")
                .contains("updated_at = CURRENT_TIMESTAMP")
                // 원래 DAY 에 그대로 있고 버전도 맞을 때만 옮긴다
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_day_id = #{sourceDayId}")
                .contains("AND version = #{version}")
                .doesNotContain("${");

        // 이동은 순서만 바꾼다
        assertThat(between(update, "SET", "WHERE"))
                .doesNotContain("content")
                .doesNotContain("tag")
                .doesNotContain("created_by_member_id");
    }

    @Test
    void theRoomsLastActivityIsBumpedByAWriteOnlyStatement() throws IOException {
        String update = between(resource("/mapper/TravelPlanMapper.xml"),
                "<update id=\"touchLastActivity\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plans")
                .contains("SET last_activity_at = CURRENT_TIMESTAMP")
                .contains("WHERE id = #{travelPlanId}");
    }

    @Test
    void theDayLookupIsScopedToItsPlan() throws IOException {
        String select = between(resource("/mapper/TravelPlanMapper.xml"),
                "<select id=\"findDayByPlanAndId\"", "</select>");

        // 다른 방의 dayId 를 섞어 넣어도 통과하지 못하게 두 조건을 모두 건다
        assertThat(select)
                .contains("FROM travel_plan_days")
                .contains("WHERE id = #{dayId}")
                .contains("AND travel_plan_id = #{travelPlanId}")
                .doesNotContain("${");
    }

    private String mapperXml() throws IOException {
        return resource("/mapper/TravelPlanItemMapper.xml");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
