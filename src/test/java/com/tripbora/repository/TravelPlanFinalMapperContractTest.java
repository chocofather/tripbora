package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료된 여행 계획의 최종본 계약.
 * 컬럼명은 docs/db/tripbora_schema_reference.md 의 실제 구조와 일치해야 한다.
 * 이번 단계에서 표를 새로 만들거나 컬럼을 더하지 않는다.
 */
class TravelPlanFinalMapperContractTest {

    @Test
    void everyDeclaredMapperMethodHasAStatement() throws IOException {
        String mapper = mapperXml();
        String mapperInterface = Files.readString(
                Path.of("src/main/java/com/tripbora/repository/travelplan/"
                        + "TravelPlanFinalMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains("namespace=\"com.tripbora.repository.travelplan."
                + "TravelPlanFinalMapper\"");
        for (String id : new String[]{
                "insertSnapshot", "insertMember", "insertDay", "insertItem",
                "insertAlternative", "existsByPlanId", "existsMemberByPlanAndUser",
                "findSnapshotsByUserId", "findSnapshotByPlanAndUser",
                "findMembersBySnapshotId", "findDaysBySnapshotId",
                "findItemsBySnapshotId", "findAlternativesBySnapshotId"}) {
            assertThat(mapperInterface).as("interface declares %s", id).contains(id);
            assertThat(mapper).as("xml defines %s", id).contains("id=\"" + id + "\"");
        }
    }

    @Test
    void everyFinalColumnExistsInTheSchemaReference() throws IOException {
        String schema = schemaReference();

        assertThat(between(schema,
                "CREATE TABLE `travel_plan_final_snapshots`", ") ENGINE=InnoDB"))
                .contains("`travel_plan_id`")
                .contains("`title`")
                .contains("`start_date`")
                .contains("`end_date`")
                .contains("`representative_image_url`")
                .contains("`finalized_at`");
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_final_members`", ") ENGINE=InnoDB"))
                .contains("`snapshot_id`")
                .contains("`user_id`")
                .contains("`display_name`")
                .contains("`role`");
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_final_days`", ") ENGINE=InnoDB"))
                .contains("`snapshot_id`")
                .contains("`day_number`")
                .contains("`plan_date`");
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_final_items`", ") ENGINE=InnoDB"))
                .contains("`final_day_id`")
                .contains("`content`")
                .contains("`tag`")
                .contains("`display_order`");
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_final_item_alternatives`", ") ENGINE=InnoDB"))
                .contains("`final_item_id`")
                .contains("`alternative_order`")
                .contains("`condition_label`")
                .contains("`content`");
    }

    @Test
    void aRoomKeepsExactlyOneFinalCopy() throws IOException {
        assertThat(between(schemaReference(),
                "CREATE TABLE `travel_plan_final_snapshots`", ") ENGINE=InnoDB"))
                .contains("UNIQUE KEY `uk_travel_plan_final_snapshots_plan` (`travel_plan_id`)");
    }

    @Test
    void theFinalCopyIsTiedTogetherByItsOwnNumbers() throws IOException {
        String mapper = mapperXml();

        /*
          원본 표의 번호를 그대로 쓰지 않는다.
          최종본 안에서만 통하는 번호로 새로 이어, 원본이 나중에 어떻게 되든 그대로 남는다.
        */
        assertThat(between(mapper, "<insert id=\"insertDay\"", "</insert>"))
                .contains("snapshot_id")
                .doesNotContain("travel_plan_day_id");
        assertThat(between(mapper, "<insert id=\"insertItem\"", "</insert>"))
                .contains("final_day_id")
                .doesNotContain("travel_plan_item_id");
        assertThat(between(mapper, "<insert id=\"insertAlternative\"", "</insert>"))
                .contains("final_item_id")
                .doesNotContain("travel_plan_item_id");

        // 붙일 자리를 알려면 방금 만든 번호를 받아야 한다
        for (String id : new String[]{"insertSnapshot", "insertDay", "insertItem"}) {
            assertThat(between(mapper, "<insert id=\"" + id + "\"", ">"))
                    .as("%s", id)
                    .contains("useGeneratedKeys=\"true\"")
                    .contains("keyProperty=\"id\"");
        }
    }

    @Test
    void theFinalCopyOnlyTouchesTheFinalTables() throws IOException {
        String mapper = mapperXml();

        assertThat(mapper)
                .contains("travel_plan_final_snapshots")
                .contains("travel_plan_final_members")
                .contains("travel_plan_final_days")
                .contains("travel_plan_final_items")
                .contains("travel_plan_final_item_alternatives");
        // 투표와 대화는 최종본에 옮겨 적지 않는다
        assertThat(mapper)
                .doesNotContain("travel_plan_polls")
                .doesNotContain("travel_plan_chat_messages")
                // 원본 일정을 다시 조합하지 않는다. 최종본이 기준이다
                .doesNotContain("travel_plan_items")
                .doesNotContain("travel_plan_item_alternatives")
                // 최종본은 한번 만들어지면 바뀌지 않는다. 지우는 문장은 없다
                .doesNotContain("DELETE ");

        /*
          고치는 문장은 하나뿐이고, 그것도 최종본의 내용이 아니라
          "누가 자기 목록에서 치웠는지" 를 적는 것이다.
          날짜·일정·대안은 어느 문장에서도 바뀌지 않는다.
        */
        assertThat(countOf(mapper, "<update id=")).isEqualTo(1);
        assertThat(mapper).contains("<update id=\"hideSnapshotForUser\"");
        for (String contentTable : new String[]{
                "travel_plan_final_snapshots", "travel_plan_final_days",
                "travel_plan_final_items", "travel_plan_final_item_alternatives"}) {
            assertThat(between(mapper, "<update id=\"hideSnapshotForUser\"", "</update>"))
                    .as("최종본 내용을 고침: %s", contentTable)
                    .doesNotContain("UPDATE " + contentTable);
        }
    }

    @Test
    void whoeverIsCopiedIntoTheFinalCopyKeepsTheirAccount() throws IOException {
        String planMapper = resource("/mapper/TravelPlanMapper.xml");

        /*
          최종 명단의 user_id 로 "내 완료된 여행" 을 찾는다.
          옮겨 적을 때 계정 번호를 읽지 않으면 NULL 로 저장되고,
          그러면 그 여행은 누구의 목록에도 나오지 않는다(NULL 은 무엇과도 같지 않다).
        */
        assertThat(between(planMapper,
                "<select id=\"findActiveMembersForSnapshot\"", "</select>"))
                .contains("user_id")
                .contains("status = #{memberStatus}");

        // 화면에 뿌리는 조회는 지금처럼 계정 번호를 읽지 않는다
        assertThat(between(planMapper,
                "<select id=\"findActiveMembersByPlanId\"", "</select>"))
                .doesNotContain("user_id");
    }

    @Test
    void theFinishedListIsFoundByTheAccountThatWasCopied() throws IOException {
        // 위에서 저장한 그 값으로 찾는다. 둘이 어긋나면 목록이 비어 보인다
        assertThat(between(mapperXml(), "<insert id=\"insertMember\"", "</insert>"))
                .contains("user_id");
        assertThat(between(mapperXml(), "<select id=\"findSnapshotsByUserId\"", "</select>"))
                .contains("m.user_id = #{userId}");
    }

    @Test
    void readingTheFinishedTripTakesTheSnapshotsOwnOrder() throws IOException {
        String mapper = mapperXml();

        // 완료 시점의 차례가 화면에 그대로 나온다
        assertThat(between(mapper, "<select id=\"findDaysBySnapshotId\"", "</select>"))
                .contains("ORDER BY d.day_number ASC")
                .contains("original.title")
                .contains("original.travel_plan_id = s.travel_plan_id")
                .contains("original.day_number = d.day_number")
                .contains("original.plan_date = d.plan_date");
        assertThat(between(mapper, "<select id=\"findItemsBySnapshotId\"", "</select>"))
                .contains("ORDER BY d.day_number ASC, i.display_order ASC, i.id ASC");
        assertThat(between(mapper, "<select id=\"findAlternativesBySnapshotId\"", "</select>"))
                .contains("ORDER BY a.final_item_id ASC, a.alternative_order ASC");
    }

    @Test
    void theListCountsWhoWasThereWithoutAskingAgain() throws IOException {
        // 목록에서 여행 수만큼 조회가 나가지 않게 한 문장으로 센다
        assertThat(between(mapperXml(), "<select id=\"findSnapshotsByUserId\"", "</select>"))
                .contains("SELECT COUNT(*)")
                .contains("AS member_count")
                .contains("ORDER BY s.finalized_at DESC");
    }

    @Test
    void theFinishedListIsCutInTheDatabaseNotAfterReadingEverything() throws IOException {
        String select = between(mapperXml(), "<select id=\"findSnapshotsByUserId\"", "</select>");

        // 최근에 끝난 것부터 정렬한 뒤 쪽 크기만큼만 끊어 온다
        assertThat(select).contains("LIMIT #{limit} OFFSET #{offset}");
        assertThat(select.indexOf("ORDER BY"))
                .as("정렬이 먼저다").isLessThan(select.indexOf("LIMIT"));
    }

    @Test
    void theFinishedTabNumberLeavesOutWhatSomeoneCleared() throws IOException {
        String count = between(mapperXml(), "<select id=\"countSnapshotsByUserId\"", "</select>");

        // 목록과 같은 조건으로 센다. 숫자만 남고 목록이 비어 보이는 일이 없어야 한다
        assertThat(count)
                .contains("SELECT COUNT(*)")
                .contains("FROM travel_plan_final_snapshots s")
                .contains("JOIN travel_plan_final_members m ON m.snapshot_id = s.id")
                .contains("WHERE m.user_id = #{userId}")
                .contains("AND m.hidden_at IS NULL");
        assertThat(count).doesNotContain("LIMIT").doesNotContain("${");
    }

    // ── 내 목록에서만 지우기 ─────────────────────────────────

    @Test
    void thePlaceToRememberWhoClearedItAlreadyExists() throws IOException {
        // 사용자별로 숨길 자리가 이미 있다. DB 를 바꾸지 않는다
        String table = between(schemaReference(),
                "CREATE TABLE `travel_plan_final_members`", ") ENGINE=InnoDB");

        assertThat(table)
                .contains("`user_id` bigint DEFAULT NULL")
                .contains("`hidden_at` timestamp NULL DEFAULT NULL")
                // 사람마다 자기 행이 하나뿐이라 남의 행이 섞이지 않는다
                .contains("UNIQUE KEY `uk_travel_plan_final_members_snapshot_user`")
                // 목록 조회가 그대로 타는 인덱스
                .contains("KEY `idx_travel_plan_final_members_user_visible` "
                        + "(`user_id`,`hidden_at`,`snapshot_id`)");
    }

    @Test
    void clearingItOnlyEverMarksTheCallersOwnRow() throws IOException {
        String update = between(mapperXml(), "<update id=\"hideSnapshotForUser\"", "</update>");

        assertThat(update)
                .contains("UPDATE travel_plan_final_members m")
                .contains("SET m.hidden_at = CURRENT_TIMESTAMP")
                .contains("AND m.user_id = #{userId}")
                // 이미 지운 뒤에는 반영되지 않는다
                .contains("AND m.hidden_at IS NULL")
                .doesNotContain("${");

        /*
          최종본은 건드리지 않는다.
          모두가 각자 지워도 스냅숏·날짜·일정·대안은 그대로 남는다.
        */
        assertThat(mapperXml()).doesNotContain("DELETE FROM");
        for (String finalTable : new String[]{
                "travel_plan_final_snapshots", "travel_plan_final_days",
                "travel_plan_final_items", "travel_plan_final_item_alternatives"}) {
            assertThat(between(update, "UPDATE", "WHERE"))
                    .as("최종본을 건드림: %s", finalTable)
                    .doesNotContain("SET " + finalTable)
                    .doesNotContain(finalTable + " SET");
        }
        // 이름이나 역할 같은 기록은 그대로 둔다
        assertThat(between(update, "SET", "WHERE"))
                .doesNotContain("display_name")
                .doesNotContain("role")
                .doesNotContain("user_id");
    }

    @Test
    void whatOnePersonClearedStaysOutOfTheirListAndUrlBoth() throws IOException {
        // 목록과 상세가 같은 조건을 쓴다. 주소를 직접 쳐도 열리지 않는다
        for (String id : new String[]{"findSnapshotsByUserId", "findSnapshotByPlanAndUser"}) {
            assertThat(between(mapperXml(), "<select id=\"" + id + "\"", "</select>"))
                    .as("%s", id)
                    .contains("m.user_id = #{userId}")
                    .contains("AND m.hidden_at IS NULL");
        }
        // 완료된 방으로 들어왔을 때의 안내도 같은 기준을 쓴다
        assertThat(between(mapperXml(), "<select id=\"existsMemberByPlanAndUser\"", "</select>"))
                .contains("AND m.hidden_at IS NULL");
    }

    @Test
    void whatIClearedNeverDisappearsFromAnyoneElsesCopy() throws IOException {
        /*
          지우기는 내 목록에서만 치우는 것이다.
          "함께한 사람" 과 그 인원수는 남의 화면에서 그대로여야 한다.
        */
        assertThat(between(mapperXml(), "<select id=\"findMembersBySnapshotId\"", "</select>"))
                .doesNotContain("hidden_at IS NULL");

        String list = between(mapperXml(), "<select id=\"findSnapshotsByUserId\"", "</select>");
        String memberCount = between(list, "(SELECT COUNT(*)", "AS member_count");
        assertThat(memberCount)
                .contains("WHERE c.snapshot_id = s.id")
                .doesNotContain("hidden_at");
    }

    // ── 마지막 한 사람이 지울 때 ─────────────────────────────

    @Test
    void itIsTheRemainingPeopleThatAreCounted() throws IOException {
        String select = between(mapperXml(),
                "<select id=\"countVisibleMembersByPlanId\"", "</select>");

        // 지운 사람 수가 아니라 남은 사람 수다
        assertThat(select)
                .contains("SELECT COUNT(*)")
                .contains("FROM travel_plan_final_members m")
                .contains("WHERE s.travel_plan_id = #{travelPlanId}")
                .contains("AND m.hidden_at IS NULL")
                .doesNotContain("hidden_at IS NOT NULL")
                .doesNotContain("${");
    }

    @Test
    void anAccountThatCanNeverComeBackDoesNotCountAsSomeoneWhoIsStillThere() throws IOException {
        String select = between(mapperXml(),
                "<select id=\"countVisibleMembersByPlanId\"", "</select>");

        /*
          탈퇴한 사람은 다시 들어와 자기 것을 지울 수 없다.
          남은 사람으로 세면 아무도 볼 수 없는 여행이 영원히 남는다.
        */
        assertThat(select)
                .contains("JOIN users u ON u.id = m.user_id")
                // 빼는 상태는 하나뿐이다. 유예 중 계정까지 빼면 보존 정책이 깨진다.
                .contains("AND u.status != #{withdrawnStatus}")
                .doesNotContain("NOT IN")
                // 어떤 상태를 빼는지는 부르는 쪽이 enum 에서 가져온다
                .doesNotContain("'DEACTIVATED'")
                .doesNotContain("'WITHDRAWAL_PENDING'");

        // 계정과 연결이 끊긴 행도 INNER JOIN 에서 함께 빠진다
        assertThat(select).doesNotContain("LEFT JOIN");

        // 부르는 쪽은 문자열을 지어내지 않는다
        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanFinalDeleteService.java"),
                StandardCharsets.UTF_8);
        assertThat(service)
                .contains("UserStatus.DEACTIVATED.name()")
                // 유예 중 계정을 빼면 30일 보존 정책이 깨지므로 여기서는 넘기지 않는다.
                .doesNotContain("UserStatus.WITHDRAWAL_PENDING.name()");

        // 그 이름이 실제 컬럼에 있는 값인지 스키마에서 확인한다
        assertThat(between(schemaReference(), "CREATE TABLE `users`", ") ENGINE=InnoDB"))
                .contains("`status` enum(")
                .contains("'DEACTIVATED'");
    }

    @Test
    void whoCanStillComeBackIsCountedAsBefore() throws IOException {
        String select = between(mapperXml(),
                "<select id=\"countVisibleMembersByPlanId\"", "</select>");

        /*
          되돌릴 수 없는 삭제라 넉넉하게 본다.
          휴면·이용정지·인증대기는 돌아올 수 있는 자리이므로 그대로 센다.
        */
        for (String comingBack : new String[]{"SUSPENDED", "RESTRICTED", "INACTIVE"}) {
            assertThat(select).as("돌아올 수 있는 상태를 뺐다: %s", comingBack)
                    .doesNotContain(comingBack);
        }
    }

    @Test
    void theEverydayReadingOfAFinishedTripIsLeftAlone() throws IOException {
        /*
          바뀐 것은 "마지막 한 사람인가" 를 가리는 셈뿐이다.
          목록과 상세는 그 사람 자신의 hidden_at 만 보고 그대로 판단한다.
        */
        for (String id : new String[]{
                "findSnapshotsByUserId", "findSnapshotByPlanAndUser",
                "existsMemberByPlanAndUser"}) {
            assertThat(between(mapperXml(), "<select id=\"" + id + "\"", "</select>"))
                    .as("%s", id)
                    .doesNotContain("JOIN users")
                    .doesNotContain("withdrawnStatus");
        }
    }

    @Test
    void theWholeTripGoesWithOneStatementBecauseEverythingCascades() throws IOException {
        String delete = between(resource("/mapper/TravelPlanMapper.xml"),
                "<delete id=\"deletePlanByIdAndStatus\"", "</delete>");

        assertThat(delete)
                .contains("DELETE FROM travel_plans")
                .contains("WHERE id = #{travelPlanId}")
                // 진행 중인 방에는 어떤 경우에도 닿지 않는다
                .contains("AND status = #{planStatus}")
                .doesNotContain("${");

        /*
          딸린 것을 여기서 순서 잡아 지우지 않는다.
          travel_plans 를 향한 ON DELETE CASCADE 가 모두 데려간다.
        */
        String schema = schemaReference();
        for (String cascading : new String[]{
                "fk_travel_plan_members_plan", "fk_travel_plan_invitations_plan",
                "fk_travel_plan_days_plan", "fk_travel_plan_polls_plan",
                "fk_travel_plan_chat_messages_plan", "fk_travel_plan_chat_read_positions_plan",
                "fk_travel_plan_final_snapshots_plan"}) {
            assertThat(constraintLine(schema, cascading))
                    .as("%s", cascading)
                    .contains("REFERENCES `travel_plans` (`id`) ON DELETE CASCADE");
        }
        // 그 아래로도 계속 이어진다
        for (String nested : new String[]{
                "fk_travel_plan_items_day", "fk_travel_plan_item_alternatives_item",
                "fk_travel_plan_poll_options_poll", "fk_travel_plan_poll_votes_poll",
                "fk_travel_plan_poll_vote_selections_vote",
                "fk_travel_plan_poll_vote_selections_option",
                "fk_travel_plan_member_settings_member",
                "fk_travel_plan_chat_read_positions_member",
                "fk_travel_plan_final_members_snapshot", "fk_travel_plan_final_days_snapshot",
                "fk_travel_plan_final_items_day", "fk_travel_plan_final_item_alternatives_item"}) {
            assertThat(constraintLine(schema, nested))
                    .as("%s", nested).contains("ON DELETE CASCADE");
        }
    }

    @Test
    void nothingElseInTheAppReachesForTheDeleteStatement() throws IOException {
        String planMapper = resource("/mapper/TravelPlanMapper.xml");

        // 방을 지우는 문장은 하나뿐이고, 그것도 상태 조건이 걸려 있다
        assertThat(countOf(planMapper, "<delete id=")).isEqualTo(1);
        assertThat(countOf(planMapper, "DELETE FROM")).isEqualTo(1);

        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanFinalDeleteService.java"),
                StandardCharsets.UTF_8);
        // 부를 때도 언제나 COMPLETED 다
        assertThat(service)
                .contains("deletePlanByIdAndStatus(\n                travelPlanId, "
                        + "TravelPlanStatus.COMPLETED.name())")
                .doesNotContain("TravelPlanStatus.ACTIVE");
    }

    private String constraintLine(String schema, String constraintName) {
        int index = schema.indexOf("CONSTRAINT `" + constraintName + "`");
        assertThat(index).as("constraint %s", constraintName).isGreaterThanOrEqualTo(0);
        int end = schema.indexOf('\n', index);
        return schema.substring(index, end < 0 ? schema.length() : end);
    }

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private String schemaReference() throws IOException {
        return Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
    }

    private String mapperXml() throws IOException {
        return resource("/mapper/TravelPlanFinalMapper.xml");
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
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
