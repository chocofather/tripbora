package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 완료된 여행 화면.
 *
 * <p>읽기 전용이다. 고칠 수 있는 것이 하나도 없어야 한다.
 * 완료된 여행은 최종본에서만 읽고 원본 방을 다시 들여다보지 않는다.
 */
class TravelPlanFinalUiContractTest {

    // ── 목록 ────────────────────────────────────────────────

    @Test
    void theListSeparatesFinishedTripsFromRunningOnes() throws IOException {
        String list = listHtml();

        assertThat(list)
                .contains("완료된 여행")
                .contains("completedTravelPlans")
                // 끝난 여행이라는 것을 한눈에 알 수 있게
                .contains("travel-plan-card-completed");
        // 진행 중 아래 자기 구역에 놓인다. 한 목록에 섞이지 않는다
        assertThat(list).contains("data-plan-filter=\"active\"", "data-plan-filter=\"completed\"");
    }

    @Test
    void anEmptyFinishedSectionSaysSoWithoutTakingOverTheScreen() throws IOException {
        String list = listHtml();

        // 한 건도 없어도 구역은 남고 짧은 한 줄만 적힌다
        assertThat(resource("/static/js/travel-plan-list.js")).contains("아직 완료된 여행이 없어요.");
    }

    @Test
    void theFinishedListStaysShort() throws IOException {
        String card = between(listHtml(),
                "class=\"travel-plan-card-link is-completed\"", "</li>");

        // 제목 · 기간 · 참여 인원 · 완료 표시까지다
        assertThat(card)
                .contains("plan.title")
                .contains("plan.startDate")
                .contains("plan.endDate")
                .contains("plan.memberCount")
                .contains(">완료</span>");
        // 목록에서 일정까지 펼치지 않는다
        assertThat(card).doesNotContain("data-travel-plan-item", "data-travel-plan-slot", "th:each=\"day :");
    }

    @Test
    void aFinishedCardLeadsToItsOwnReadOnlyPage() throws IOException {
        assertThat(listHtml())
                .contains("@{|/travel-plans/${plan.travelPlanId}/final|}");
    }

    // ── 상세 ────────────────────────────────────────────────

    @Test
    void theFinishedTripShowsWhatWasDecided() throws IOException {
        String detail = finalDetailHtml();

        assertThat(detail)
                .contains("finalPlan.snapshot.title")
                .contains("finalPlan.snapshot.startDate")
                .contains("finalPlan.members")
                .contains("finalPlan.days")
                .contains("finalPlan.itemsByDayId")
                .contains("finalPlan.alternativesByItemId");
        // 완성된 계획이라는 것이 드러난다
        assertThat(detail).contains("완성된 여행계획");
    }

    @Test
    void theAlternativesAreShownWhenTheSnapshotHasThem() throws IOException {
        String detail = finalDetailHtml();

        assertThat(detail)
                .contains("alt.alternativeOrder == 1 ? 'B' : 'C'")
                .contains("alt.conditionLabel")
                .contains("alt.content");
        // 편집 화면과 달리 접었다 펴는 버튼이 없다. 늘 그대로 보인다
        assertThat(detail).doesNotContain("data-travel-plan-alt-toggle");
    }

    @Test
    void nothingOnThePageCanBeEdited() throws IOException {
        String detail = finalDetailHtml();

        /*
          적힌 내용은 하나도 고칠 수 없어야 한다.
          입력칸도, 추가 슬롯도, ⋯ 메뉴도 두지 않는다.
        */
        assertThat(detail)
                .doesNotContain("<textarea")
                .doesNotContain("<input")
                .doesNotContain("data-travel-plan-slot")
                .doesNotContain("data-travel-plan-item-form")
                .doesNotContain("data-travel-plan-menu-button");

        /*
          보내는 곳은 하나뿐이고, 그것도 내용을 고치는 것이 아니라
          이 여행을 내 목록에서 치우는 길이다.
        */
        assertThat(countOf(detail, "<form")).isEqualTo(1);
        assertThat(countOf(detail, "<button")).isEqualTo(1);
        assertThat(between(detail, "<form", "</form>")).contains("/final/delete|}");
    }

    @Test
    void nothingLiveIsAttachedToTheFinishedTrip() throws IOException {
        String detail = finalDetailHtml();

        // 채팅·투표·초대·실시간 편집은 끝난 여행에 없다
        assertThat(detail)
                .doesNotContain("data-travel-plan-chat")
                .doesNotContain("data-travel-plan-poll")
                .doesNotContain("data-travel-plan-invite")
                .doesNotContain("data-travel-plan-finalize")
                .doesNotContain("채팅")
                .doesNotContain("투표")
                .doesNotContain("초대");
        // 실시간 연결도 편집 스크립트도 싣지 않는다
        assertThat(detail)
                .doesNotContain("travel-plan-realtime.js")
                .doesNotContain("travel-plan-scheduler.js")
                .doesNotContain("travel-plan-chat.js")
                .doesNotContain("travel-plan-poll.js")
                .doesNotContain("stomp");
        // data-plan-id 가 없어 편집 스크립트가 붙을 자리도 없다
        assertThat(detail).doesNotContain("data-plan-id");
    }

    @Test
    void theFinishedTripIsReadFromTheFinalCopyAlone() throws IOException {
        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanFinalReadService.java"),
                StandardCharsets.UTF_8);

        // 원본 방을 다시 조합하지 않는다
        assertThat(service)
                .doesNotContain("TravelPlanItemMapper")
                .doesNotContain("TravelPlanAlternativeMapper")
                .doesNotContain("TravelPlanMapper travelPlanMapper");
        assertThat(service).contains("TravelPlanFinalMapper");
    }

    @Test
    void onlyThePeopleWhoWereThereCanOpenIt() throws IOException {
        String mapper = mapperXml();

        // 볼 자격은 조회 조건 안에 들어 있다
        assertThat(between(mapper, "<select id=\"findSnapshotByPlanAndUser\"", "</select>"))
                .contains("JOIN travel_plan_final_members m ON m.snapshot_id = s.id")
                .contains("m.user_id = #{userId}");
        // 목록도 마찬가지다
        assertThat(between(mapper, "<select id=\"findSnapshotsByUserId\"", "</select>"))
                .contains("m.user_id = #{userId}");
    }

    @Test
    void theOldEditingUrlLeadsToTheFinishedTrip() throws IOException {
        String controller = Files.readString(
                Path.of("src/main/java/com/tripbora/controller/travelplan/"
                        + "TravelPlanController.java"),
                StandardCharsets.UTF_8);

        // 완료된 여행에 함께했던 사람은 목록에서 다시 찾지 않아도 된다
        assertThat(controller)
                .contains("TravelPlanAccessNotice.COMPLETED_PARTICIPANT")
                .contains("\"redirect:/travel-plans/\" + travelPlanId + \"/final\"");
    }

    @Test
    void clearingAFinishedTripAsksFirstAndSaysItIsMineAlone() throws IOException {
        String detail = finalDetailHtml();

        assertThat(detail)
                .contains("/final/delete|}")
                .contains("class=\"travel-plan-final-delete\"")
                .contains(">\n            삭제\n          </button>");
        // 바로 POST 하지 않고, 남에게는 영향이 없다는 것까지 알린 뒤에 보낸다
        assertThat(detail)
                .contains("이 완료된 여행을 내 목록에서 삭제할까요?")
                .contains("다른 참여자의 기록에는 영향을 주지 않습니다.");
    }

    @Test
    void theListDoesNotPutADeleteButtonOnEveryCard() throws IOException {
        // 지우기는 상세까지 들어와야 보인다
        assertThat(listHtml())
                .doesNotContain("숨기기")
                .doesNotContain("삭제")
                .doesNotContain("/final/delete");
    }

    @Test
    void theDeleteActionStaysLowKeyRatherThanABigRedButton() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 참여자 쪽 낮은 위계 규칙을 그대로 함께 쓴다. 새 버튼 모양을 만들지 않는다
        assertThat(between(css, "/* 낮은 위계로 둔다", "}"))
                .contains(".travel-plan-final-delete")
                .contains("background: none")
                .contains("font-size: 12px")
                .contains("border: 0");
    }

    @Test
    void theRecycleBinIsStillNotPartOfThis() throws IOException {
        // 되돌리는 길은 이번 단계에 없다
        assertThat(finalDetailHtml())
                .doesNotContain("복구")
                .doesNotContain("휴지통");
        assertThat(listHtml())
                .doesNotContain("복구")
                .doesNotContain("휴지통");
    }

    @Test
    void everythingPeopleTypedFoldsInsteadOfWideningThePaper() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        /*
          최종본은 작성 화면과 같은 줄·대안 규칙을 그대로 쓴다.
          한쪽만 고치면 다른 쪽에서 같은 문제가 되살아나므로 함께 묶어 둔다.
        */
        for (String rule : new String[]{
                ".travel-plan-line-content {",
                ".travel-plan-alt-content {",
                ".travel-plan-alt-condition {"}) {
            assertThat(between(css, rule, "\n}")).as("%s", rule)
                    .contains("overflow-wrap: break-word");
        }
        // 줄 안의 본문 칸은 최소 폭 바닥이 풀려 있어야 접힌다
        for (String rule : new String[]{
                ".travel-plan-line-body {", ".travel-plan-alt-body {"}) {
            assertThat(between(css, rule, "\n}")).as("%s", rule).contains("min-width: 0");
        }
        // 완료 시점의 참여자 줄도 마찬가지다
        assertThat(between(css, ".travel-plan-final-members {", "\n}"))
                .contains("overflow-wrap: break-word");
    }

    @Test
    void theFinalPaperGrowsDownwardsWithoutAScrollBoxOfItsOwn() throws IOException {
        String paper = between(resource("/static/css/travel-plan.css"),
                ".travel-plan-paper {", "\n}");

        // 90일짜리 여행도 문서가 그대로 길어진다. 안에서 따로 넘기지 않는다
        assertThat(paper)
                .contains("max-width: 900px")
                .doesNotContain("height:")
                .doesNotContain("overflow");
    }

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private String listHtml() throws IOException {
        return resource("/templates/travelplan/list.html");
    }

    private String finalDetailHtml() throws IOException {
        return resource("/templates/travelplan/final-detail.html");
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
