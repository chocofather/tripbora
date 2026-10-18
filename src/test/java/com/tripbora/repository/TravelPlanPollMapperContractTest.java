package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방 투표 등록/조회 계약.
 * 컬럼명은 docs/db/tripbora_schema_reference.md 의 실제 구조와 일치해야 한다.
 * 이번 단계에서 표를 새로 만들거나 컬럼을 더하지 않는다.
 */
class TravelPlanPollMapperContractTest {

    @Test
    void everyDeclaredMapperMethodHasAStatement() throws IOException {
        String mapper = mapperXml();
        String mapperInterface = Files.readString(
                Path.of("src/main/java/com/tripbora/repository/travelplan/"
                        + "TravelPlanPollMapper.java"),
                StandardCharsets.UTF_8);

        assertThat(mapper).contains("namespace=\"com.tripbora.repository.travelplan."
                + "TravelPlanPollMapper\"");
        for (String id : new String[]{
                "insertPoll", "insertOption", "findOpenPolls", "findClosedPolls",
                "findRecentPolls", "findPollsBefore", "closePoll", "countPollsByStatus",
                "deletePoll", "deleteVotesOfMemberInOpenPolls", "findOpenPollIds",
                "findByIdAndPlanId", "findOptionsByPollIds"}) {
            assertThat(mapperInterface).as("interface declares %s", id).contains(id);
            assertThat(mapper).as("xml defines %s", id).contains("id=\"" + id + "\"");
        }
    }

    @Test
    void pollColumnsExistInTheSchemaReference() throws IOException {
        String polls = between(schemaReference(),
                "CREATE TABLE `travel_plan_polls`", ") ENGINE=InnoDB");

        for (String column : new String[]{
                "travel_plan_id", "created_by_member_id", "title", "selection_type",
                "result_visibility", "close_type", "deadline_at", "status",
                "close_reason", "closed_at"}) {
            assertThat(polls).as("travel_plan_polls.%s", column).contains("`" + column + "`");
        }
        // 질문은 title 컬럼에 들어간다. 길이 제한도 여기서 온다
        assertThat(polls).containsPattern("`title` varchar\\(200\\)(?: CHARACTER SET \\w+ COLLATE \\w+)? NOT NULL");
        // 만들어진 투표는 곧바로 진행 중이다
        assertThat(polls).containsPattern("`status` varchar\\(20\\)(?: CHARACTER SET \\w+ COLLATE \\w+)? NOT NULL DEFAULT 'OPEN'");
        // 진행 중인 투표 조회가 그대로 타는 인덱스
        assertThat(polls).contains("(`travel_plan_id`,`status`,`created_at`)");
    }

    @Test
    void optionColumnsExistInTheSchemaReference() throws IOException {
        String options = between(schemaReference(),
                "CREATE TABLE `travel_plan_poll_options`", ") ENGINE=InnoDB");

        for (String column : new String[]{"poll_id", "content", "display_order"}) {
            assertThat(options).as("travel_plan_poll_options.%s", column)
                    .contains("`" + column + "`");
        }
        // 보여 줄 순서가 DB 에 남는다. 1 부터라는 것도 제약으로 걸려 있다
        assertThat(options).contains("`display_order` int NOT NULL");
        assertThat(options).contains("CHECK ((`display_order` >= 1))");
        assertThat(options).contains("(`poll_id`,`display_order`,`id`)");
    }

    @Test
    void theMapperOnlyTouchesTheTwoPollTables() throws IOException {
        String mapper = mapperXml();

        assertThat(mapper)
                .contains("travel_plan_polls")
                .contains("travel_plan_poll_options")
                .contains("travel_plan_poll_votes")
                .contains("travel_plan_poll_vote_selections")
                // 이름과 참여 여부는 그 방의 참여자 표에서 함께 읽는다
                .contains("travel_plan_members");
        assertThat(mapper)
                // 채팅과 투표는 표가 따로다
                .doesNotContain("travel_plan_chat_messages")
                // 계정 표는 들여다보지 않는다
                .doesNotContain("FROM users")
                .doesNotContain("JOIN users")
                .doesNotContain("user_email")
                .doesNotContain("nickname");
    }

    @Test
    void aNewPollIsStoredWithEveryColumnSpelledOut() throws IOException {
        String insert = between(mapperXml(), "<insert id=\"insertPoll\"", "</insert>");

        assertThat(insert)
                .contains("INSERT INTO travel_plan_polls")
                .contains("created_by_member_id")
                .contains("selection_type")
                .contains("result_visibility")
                .contains("close_type")
                .contains("status")
                // 방금 만든 투표의 번호를 알아야 선택지를 붙일 수 있다
                .contains("useGeneratedKeys=\"true\"")
                .contains("keyProperty=\"id\"");
    }

    @Test
    void anOptionKeepsThePlaceItWasTypedIn() throws IOException {
        String insert = between(mapperXml(), "<insert id=\"insertOption\"", "</insert>");

        assertThat(insert)
                .contains("INSERT INTO travel_plan_poll_options")
                .contains("poll_id, content, display_order");
    }

    @Test
    void openPollsComeBackOldestFirstWithTheCreatorsRoomName() throws IOException {
        String mapper = mapperXml();
        String select = between(mapper, "<select id=\"findOpenPolls\"", "</select>");

        assertThat(select)
                .contains("FROM travel_plan_polls")
                .contains("p.travel_plan_id = #{travelPlanId}")
                .contains("p.status = 'OPEN'")
                .contains("ORDER BY p.created_at ASC");
        // 만든 사람 이름은 그 방의 표시 이름이다
        assertThat(between(mapper, "<sql id=\"pollColumns\">", "</sql>"))
                .contains("m.display_name AS created_by_display_name");
        // 나갔거나 내보내진 사람이 만든 투표도 그대로 보여야 한다
        assertThat(mapper)
                .contains("LEFT JOIN travel_plan_members m ON m.id = p.created_by_member_id");
        assertThat(between(mapper, "<sql id=\"pollColumns\">", "</sql>"))
                .doesNotContain("status = 'ACTIVE'");
    }

    @Test
    void bothCountsAreTakenOnTheSameBasis() throws IOException {
        String mapper = mapperXml();

        /*
          참여 인원과 선택지별 표 수를 같은 기준으로 센다.
          지금 방에 남아 있는 사람만 세어야 표 수가 참여 인원을 넘지 않는다.
        */
        for (String id : new String[]{
                "countVotedMembers", "countVotedMembersByPollIds", "countSelectionsByPollIds"}) {
            assertThat(between(mapper, "<select id=\"" + id + "\"", "</select>"))
                    .as("%s", id)
                    .contains("travel_plan_members")
                    .contains("m.status = 'ACTIVE'");
        }
    }

    @Test
    void onePersonKeepsOneVoteRowAndOnlyTheChoicesMove() throws IOException {
        String mapper = mapperXml();
        String schema = schemaReference();

        // (poll_id, member_id) UNIQUE 라 사람마다 한 줄이다
        assertThat(between(schema, "CREATE TABLE `travel_plan_poll_votes`", ") ENGINE=InnoDB"))
                .contains("UNIQUE KEY `uk_travel_plan_poll_votes_poll_member`"
                        + " (`poll_id`,`member_id`)");
        // 같은 선택지를 두 번 넣지 못한다
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_poll_vote_selections`", ") ENGINE=InnoDB"))
                .contains("UNIQUE KEY `uk_travel_plan_poll_vote_selections_vote_option`"
                        + " (`vote_id`,`option_id`)");

        // 선택을 바꿀 때 지우는 것은 선택뿐이다. 투표 줄은 건드리지 않는다
        assertThat(between(mapper, "<delete id=\"deleteSelectionsByVoteId\"", "</delete>"))
                .contains("DELETE FROM travel_plan_poll_vote_selections")
                .contains("WHERE vote_id = #{voteId}");
        assertThat(mapper).doesNotContain("DELETE FROM travel_plan_poll_votes");
    }

    @Test
    void aPollCanOnlyBeClosedOnce() throws IOException {
        String update = between(mapperXml(), "<update id=\"closePoll\"", "</update>");

        /*
          직접 마감·전원 투표·시간 만료가 동시에 닿을 수 있다.
          아직 열려 있을 때만 반영되게 해 한 번만 성공하고, 알림도 한 번만 나가게 한다.
        */
        assertThat(update)
                .contains("UPDATE travel_plan_polls")
                .contains("status = 'CLOSED'")
                .contains("close_reason = #{closeReason}")
                .contains("closed_at = CURRENT_TIMESTAMP")
                .contains("AND status = 'OPEN'");
    }

    @Test
    void nothingClosesAPollByTheClockAnyMore() throws IOException {
        String mapper = mapperXml();

        // 시각으로 끝나는 투표는 없앴다. 마감 시각을 보는 조회도 남기지 않는다
        assertThat(mapper)
                .doesNotContain("deadline_at &lt;=")
                .doesNotContain("close_type = 'DEADLINE'");
        // 새 투표는 마감 시각을 아예 채우지 않는다
        assertThat(between(mapper, "<insert id=\"insertPoll\"", "</insert>"))
                .contains("deadline_at");
    }

    @Test
    void deletingAPollLeavesNothingBehind() throws IOException {
        String mapper = mapperXml();
        String schema = schemaReference();

        // 방 소속까지 걸어 다른 방의 투표를 지울 수 없게 한다
        assertThat(between(mapper, "<delete id=\"deletePoll\"", "</delete>"))
                .contains("DELETE FROM travel_plan_polls")
                .contains("WHERE id = #{id}")
                .contains("AND travel_plan_id = #{travelPlanId}");

        /*
          선택지·표·고른 선택은 FK 의 CASCADE 로 함께 사라진다.
          그래서 지우는 문장이 하나여도 남는 행이 없다.
        */
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_poll_options`", ") ENGINE=InnoDB"))
                .contains("FOREIGN KEY (`poll_id`) REFERENCES `travel_plan_polls` (`id`)"
                        + " ON DELETE CASCADE");
        assertThat(between(schema,
                "CREATE TABLE `travel_plan_poll_votes`", ") ENGINE=InnoDB"))
                .contains("FOREIGN KEY (`poll_id`) REFERENCES `travel_plan_polls` (`id`)"
                        + " ON DELETE CASCADE");
        String selections = between(schema,
                "CREATE TABLE `travel_plan_poll_vote_selections`", ") ENGINE=InnoDB");
        assertThat(selections)
                .contains("FOREIGN KEY (`vote_id`) REFERENCES `travel_plan_poll_votes` (`id`)"
                        + " ON DELETE CASCADE")
                .contains("FOREIGN KEY (`option_id`)"
                        + " REFERENCES `travel_plan_poll_options` (`id`) ON DELETE CASCADE");
    }

    @Test
    void aDepartingMemberOnlyLosesVotesInRunningPolls() throws IOException {
        String delete = between(mapperXml(),
                "<delete id=\"deleteVotesOfMemberInOpenPolls\"", "</delete>");

        assertThat(delete)
                .contains("FROM travel_plan_poll_votes v")
                .contains("p.travel_plan_id = #{travelPlanId}")
                .contains("v.member_id = #{memberId}")
                // 이미 끝난 투표의 결과는 그대로 남아야 한다
                .contains("p.status = 'OPEN'");
        // 고른 선택은 selections.vote_id CASCADE 로 함께 사라진다
        assertThat(mapperXml())
                .doesNotContain("DELETE FROM travel_plan_poll_vote_selections\n        WHERE"
                        + " vote_id IN");
    }

    @Test
    void aChoiceFromAnotherPollCanBeSpotted() throws IOException {
        String select = between(mapperXml(), "<select id=\"countOwnedOptions\"", "</select>");

        // 보낸 개수와 다르면 다른 투표의 선택지가 섞인 것이다
        assertThat(select)
                .contains("FROM travel_plan_poll_options")
                .contains("WHERE poll_id = #{pollId}")
                .contains("id IN");
    }

    @Test
    void finishedPollsAreFoundByTheirOwnStatus() throws IOException {
        String select = between(mapperXml(), "<select id=\"findClosedPolls\"", "</select>");

        assertThat(select)
                .contains("p.travel_plan_id = #{travelPlanId}")
                .contains("p.status = 'CLOSED'");
    }

    @Test
    void theChatTimelineReadsPollsByIdJustLikeMessages() throws IOException {
        String mapper = mapperXml();

        // 만들어졌다는 사실이 기준이라 지금 진행 중인지 끝났는지는 보지 않는다
        String recent = between(mapper, "<select id=\"findRecentPolls\"", "</select>");
        assertThat(recent)
                .contains("ORDER BY p.id DESC")
                .contains("LIMIT #{limit}")
                .doesNotContain("p.status");

        // 앞 페이지도 채팅과 같은 방식으로 끊는다
        String before = between(mapper, "<select id=\"findPollsBefore\"", "</select>");
        assertThat(before)
                .contains("p.id &lt; #{beforePollId}")
                .contains("ORDER BY p.id DESC")
                .doesNotContain("OFFSET");
    }

    @Test
    void optionsForManyPollsAreReadInOneGo() throws IOException {
        String select = between(mapperXml(), "<select id=\"findOptionsByPollIds\"", "</select>");

        // 투표 수만큼 조회가 나가지 않게 한 문장으로 읽는다
        assertThat(select)
                .contains("WHERE poll_id IN")
                .contains("<foreach")
                .contains("ORDER BY poll_id ASC, display_order ASC, id ASC");
    }

    private String schemaReference() throws IOException {
        return Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
    }

    private String mapperXml() throws IOException {
        try (InputStream input = getClass()
                .getResourceAsStream("/mapper/TravelPlanPollMapper.xml")) {
            assertThat(input).as("TravelPlanPollMapper.xml").isNotNull();
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
