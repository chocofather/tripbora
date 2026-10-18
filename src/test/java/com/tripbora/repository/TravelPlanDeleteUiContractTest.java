package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 방장이 진행 중인 방을 통째로 지우는 길.
 *
 * <p>참여자를 한 명씩 내보내는 것과 다른 일이다.
 * 방 row 하나가 사라지고, 딸린 데이터는 DB 의 CASCADE 가 정리한다.
 */
class TravelPlanDeleteUiContractTest {

    // ── 진입점 ──────────────────────────────────────────────

    @Test
    void closingTheRoomIsOfferedWhereTheOwnerManagesTheRoom() throws IOException {
        String ownerActions = ownerActionsHtml();

        // 조각 전체가 방장일 때만 나온다. 멤버에게는 통째로 비어서 온다
        assertThat(ownerActions).contains("th:if=\"${viewerIsOwner}\"");
        assertThat(between(ownerActions, "travel-plan-plan-delete-entry", "</div>"))
                .contains("data-travel-plan-plan-delete-open")
                .contains("계획 삭제")
                .contains("<svg");
    }

    @Test
    void theWayInSitsInTheActionRowRatherThanInsideAPopoverPanel() throws IOException {
        String ownerActions = ownerActionsHtml();

        /*
          여기가 아니면 눌리지 않는다.

          팝오버 패널은 바깥을 눌러야 닫히도록 안쪽 클릭에 stopPropagation 을
          걸어 둔다. 그 안에 두면 클릭이 문서까지 오지 못해, 문서에서 듣는
          이 화면의 동작이 열리지 않는다(진입점을 그리로 옮기면 다시 먹통이 된다).

          줄에 선다는 것은 이 순서로 확인한다 —— 초대 팝오버가 끝난 뒤,
          공통 액션과 나누는 구분선 앞.
        */
        int invitePanel = ownerActions.indexOf("data-travel-plan-invite-panel");
        int trigger = ownerActions.indexOf("data-travel-plan-plan-delete-open");
        int divider = ownerActions.indexOf("travel-plan-action-divider");
        assertThat(trigger).isGreaterThan(invitePanel);
        assertThat(trigger).isLessThan(divider);
    }

    @Test
    void theParticipantPopoverKeepsNothingAboutDeletingTheRoom() throws IOException {
        String members = membersHtml();

        // 방을 지우는 것은 참여자 관리가 아니다. 팝오버에는 흔적도 남기지 않는다
        assertThat(members)
                .doesNotContain("travel-plan-plan-delete")
                .doesNotContain("여행 계획 삭제");
        // 나가기는 그대로 멤버 본인에게만 남는다
        assertThat(between(members, "travel-plan-member-leave-form", "</form>"))
                .contains("th:unless=\"${viewerIsOwner}\"");
    }

    @Test
    void theWayInStaysLowKeyBesideTheOtherTopActions() throws IOException {
        String css = cssFile();
        String rule = between(css, ".travel-plan-plan-delete {", "\n}");

        // 옆의 초대와 크기·모서리는 같다
        assertThat(rule)
                .contains("padding: 4px 10px")
                .contains("border-radius: 4px")
                .contains("font-size: 13px");
        // 큰 빨간 버튼이 아니다. 평소에는 옅은 글자 하나뿐이다
        assertThat(rule)
                .contains("background: none")
                .contains("border: 1px solid transparent")
                .contains("color: #9a7d78");
        // 뜻이 분명해지는 것은 손을 얹었을 때뿐이다
        assertThat(between(css, ".travel-plan-plan-delete:hover {", "\n}"))
                .contains("color: #b42318");
        // 줄이 접히는 화면에서도 다른 액션과 같이 움직인다
        assertThat(between(css, ".travel-plan-plan-delete-entry {", "\n}"))
                .contains("flex: 0 0 auto");
        // 들여쓴 좁은 화면 규칙이 아니라 기본 규칙을 본다
        assertThat(between(css, "\n.travel-plan-top-actions {", "\n}"))
                .contains("flex-wrap: wrap");
    }

    @Test
    void theWayInIsNotAsLoudAsFinishingTheTrip() throws IOException {
        String css = cssFile();

        // 이 줄에서 색을 채우는 것은 확정 하나뿐이다
        assertThat(between(css, ".travel-plan-finalize-toggle {", "\n}"))
                .contains("background: var(--tp-plan-accent-soft)");
        assertThat(between(css, ".travel-plan-plan-delete {", "\n}"))
                .doesNotContain("var(--tp-plan-accent-soft)");
    }

    // ── 확인 창 ─────────────────────────────────────────────

    @Test
    void nothingIsDeletedUntilItHasBeenAskedOnce() throws IOException {
        String ownerActions = ownerActionsHtml();
        String modal = between(ownerActions, "travel-plan-plan-delete-modal", "</div>\n    </div>");

        assertThat(modal)
                .contains("여행 계획을 삭제할까요?")
                .contains("함께 작성한 일정, 채팅, 투표 등 모든 내용이 삭제되며")
                .contains("삭제한 계획은 복구할 수 없습니다.")
                .contains("취소")
                .contains("여행 계획 삭제");
        // 브라우저 기본 confirm 이 아니라 이 화면의 창을 쓴다
        assertThat(between(ownerActions, "travel-plan-plan-delete-modal", "</th:block>"))
                .doesNotContain("confirm(");
    }

    @Test
    void onlyTheDeletingButtonCarriesTheWeight() throws IOException {
        String ownerActions = ownerActionsHtml();
        String css = cssFile();

        // 취소는 다른 창과 같은 중립 버튼을 그대로 쓴다
        assertThat(ownerActions).contains("class=\"travel-plan-poll-cancel\"\n"
                + "                  data-travel-plan-plan-delete-cancel");
        // 지우는 쪽만 destructive 로 둔다. 다만 큰 빨간 덩어리는 아니다
        assertThat(between(css, ".travel-plan-plan-delete-confirm {", "\n}"))
                .contains("background: #fdf4f3")
                .contains("color: #b42318");
    }

    @Test
    void theTriggerAndTheConfirmWindowAlwaysComeAndGoTogether() throws IOException {
        String ownerActions = ownerActionsHtml();

        /*
          방장이 바뀌면 이 조각이 통째로 갈린다. 진입점만 옮겨 가고 창이
          다른 곳에 남으면 넘겨받은 사람에게는 열 창이 없고,
          넘겨준 사람 화면에는 쓸 일 없는 창이 남는다.
        */
        assertThat(ownerActions)
                .contains("data-travel-plan-plan-delete-open")
                .contains("data-travel-plan-plan-delete-modal");
        // 창은 한 화면에 하나뿐이다
        assertThat(countOf(ownerActions, "data-travel-plan-plan-delete-modal")).isEqualTo(1);
        assertThat(membersHtml()).doesNotContain("travel-plan-plan-delete-modal");
        assertThat(detailHtml()).doesNotContain("travel-plan-plan-delete-modal");
    }

    @Test
    void theButtonKeepsWorkingAfterTheOwnerFragmentIsSwappedOut() throws IOException {
        String delete = deleteJs();

        /*
          조각이 갈리면 그 안의 요소는 전부 새것이 된다.
          한 번 찾아 두고 거기에 동작을 붙여 두면 갈린 뒤에는 눌리지 않는다.
          문서에서 한 번만 듣고 눌린 자리를 그때 확인한다 —— 다시 붙일 것이 없고,
          여러 번 갈려도 동작이 겹쳐 쌓이지 않는다.
        */
        assertThat(delete).contains("document.addEventListener(\"click\"");
        assertThat(between(delete, "document.addEventListener(\"click\"", "});"))
                .contains("closest(\"[data-travel-plan-plan-delete-open]\")");
        // 창도 열 때마다 그때의 창을 다시 찾는다
        assertThat(between(delete, "function modalOf()", "\n    }"))
                .contains("document.querySelector(\"[data-travel-plan-plan-delete-modal]\")");
        // 조각이 갈릴 때 다시 붙이는 자리가 없어야 한다(있으면 겹쳐 쌓인다)
        assertThat(delete).doesNotContain("travelplan:owner-actions-updated");
    }

    @Test
    void theRoomIsClosedByAFormNotByALink() throws IOException {
        String ownerActions = ownerActionsHtml();

        assertThat(ownerActions)
                .contains("method=\"post\"")
                .contains("th:action=\"@{|/travel-plans/${travelPlan.plan.id}/delete|}\"");
        // 토큰이 붙는 form 이라야 남이 대신 눌러 줄 수 없다
        // (POST 는 기본 CSRF 정책이 보호한다. 예외를 두지 않는다)
        assertThat(securityConfig())
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    // ── 서버 ────────────────────────────────────────────────

    @Test
    void theOwnerIsCheckedAgainOnTheServer() throws IOException {
        String service = source("service/travelplan/TravelPlanDeleteService.java");

        // 화면에 버튼이 보였다는 것은 근거가 아니다
        assertThat(service)
                .contains("TravelPlanRole.OWNER")
                .contains("TravelPlanStatus.ACTIVE.name()");
        // 사유는 나누지 않는다. 권한이 없으면 그 방이 있는지조차 알리지 않는다
        assertThat(service).contains("HttpStatus.NOT_FOUND");
    }

    @Test
    void theChildTablesAreLeftToTheDatabase() throws IOException {
        String service = source("service/travelplan/TravelPlanDeleteService.java");
        String schema = Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);

        // 자식 테이블을 하나씩 지우지 않는다. 방 row 하나만 지운다
        assertThat(service).contains("deletePlanByIdAndStatus");
        assertThat(service)
                .doesNotContain("markMemberLeft")
                .doesNotContain("markMemberRemoved")
                .doesNotContain("Chat")
                .doesNotContain("Poll");

        // 함께 사라지는 것은 DB 가 보장한다
        for (String cascade : new String[]{
                "`fk_travel_plan_members_plan` FOREIGN KEY (`travel_plan_id`)"
                        + " REFERENCES `travel_plans` (`id`) ON DELETE CASCADE",
                "`fk_travel_plan_invitations_plan` FOREIGN KEY (`travel_plan_id`)"
                        + " REFERENCES `travel_plans` (`id`) ON DELETE CASCADE",
                "`fk_travel_plan_days_plan` FOREIGN KEY (`travel_plan_id`)"
                        + " REFERENCES `travel_plans` (`id`) ON DELETE CASCADE",
                "`fk_travel_plan_polls_plan` FOREIGN KEY (`travel_plan_id`)"
                        + " REFERENCES `travel_plans` (`id`) ON DELETE CASCADE",
                "`fk_travel_plan_chat_messages_plan` FOREIGN KEY (`travel_plan_id`)"
                        + " REFERENCES `travel_plans` (`id`) ON DELETE CASCADE"}) {
            assertThat(schema).as("%s", cascade).contains(cascade);
        }
    }

    @Test
    void nobodyIsMovedOutOfTheRoomOneByOne() throws IOException {
        String service = source("service/travelplan/TravelPlanDeleteService.java");
        String listener = source("service/travelplan/TravelPlanDeletedListener.java");

        // 사람마다 내보내거나 연결을 끊지 않는다. 방이 없어지면 모두가 함께 빠진다
        assertThat(service).doesNotContain("MembershipChangedEvent");
        assertThat(listener)
                .doesNotContain("revoked")
                .doesNotContain("ACCESS_REVOKED")
                .doesNotContain("RoomSessionRegistry");
    }

    // ── 실시간 ──────────────────────────────────────────────

    @Test
    void theRoomIsToldOnlyAfterTheDeleteIsSaved() throws IOException {
        String service = source("service/travelplan/TravelPlanDeleteService.java");
        String listener = source("service/travelplan/TravelPlanDeletedListener.java");

        // 서비스 안에서는 알림만 남긴다
        assertThat(service).contains("new TravelPlanDeletedEvent(travelPlanId)");
        assertThat(service).doesNotContain("SimpMessagingTemplate");
        // 실제로 내보내는 것은 커밋이 끝난 뒤다
        assertThat(listener)
                .contains("TransactionPhase.AFTER_COMMIT")
                .contains("PLAN_DELETED")
                .contains("releaseAllByPlan(travelPlanId)");
    }

    @Test
    void theDeleteNoticeGoesDownTheSameLineTheRoomIsAlreadyListeningTo() throws IOException {
        String send = between(source("service/travelplan/TravelPlanDeletedListener.java"),
                "simpMessagingTemplate.convertAndSend(", "));");

        // 완료와 같은 통로. 방을 연 사람은 이미 듣고 있어 새로 만들 것이 없다
        assertThat(send).contains("TravelPlanScheduleDestinations.topic(travelPlanId)");
        // 다만 뜻이 다르므로 실어 보내는 이름은 따로 둔다
        assertThat(send)
                .contains("\"PLAN_DELETED\"")
                .doesNotContain("PLAN_COMPLETED");
    }

    @Test
    void everyoneElseInTheRoomIsSentBackToTheList() throws IOException {
        String realtime = realtimeJs();
        String delete = deleteJs();

        // 알림이 오면 화면에 알린다
        assertThat(between(realtime, "PLAN_DELETED", "return;"))
                .contains("travelplan:plan-deleted");
        // 그 알림을 받은 화면은 목록으로 나간다
        assertThat(between(delete, "travelplan:plan-deleted", "});"))
                .contains("window.location.href = \"/travel-plans\"");
        // 같은 알림이 두 번 와도 한 번만 움직인다
        assertThat(delete).contains("if (leaving) return;");
    }

    @Test
    void theCompletedRoomStillGoesItsOwnWay() throws IOException {
        String realtime = realtimeJs();

        // 완료는 완료대로 남아 있어야 한다. 삭제가 그 길을 가져가지 않는다
        assertThat(realtime)
                .contains("travelplan:plan-completed")
                .contains("travelplan:plan-deleted");
    }

    @Test
    void theScreenLoadsTheDeleteScriptAlongsideTheOthers() throws IOException {
        assertThat(detailHtml()).contains("/js/travel-plan-delete.js");
    }

    // ── 나가기와의 구분 ──────────────────────────────────────

    @Test
    void leavingTheRoomIsStillItsOwnSeparateThing() throws IOException {
        String memberService = source("service/travelplan/TravelPlanMemberService.java");

        // 나가기는 여전히 status 만 바꾼다. 방을 지우지 않는다
        assertThat(memberService)
                .contains("markMemberLeft")
                .doesNotContain("deletePlanByIdAndStatus");
        // 방장이 혼자 나가는 길을 새로 만들지 않았다
        assertThat(memberService).contains("방장은 바로 나갈 수 없습니다.");
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    private String membersHtml() throws IOException {
        return resource("/templates/travelplan/fragments/members.html");
    }

    private String ownerActionsHtml() throws IOException {
        return resource("/templates/travelplan/fragments/owner-actions.html");
    }

    private String cssFile() throws IOException {
        return resource("/static/css/travel-plan.css");
    }

    private String realtimeJs() throws IOException {
        return resource("/static/js/travel-plan-realtime.js");
    }

    private String deleteJs() throws IOException {
        return resource("/static/js/travel-plan-delete.js");
    }

    private String securityConfig() throws IOException {
        return source("config/SecurityConfig.java");
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(
                Path.of("src/main/java/com/tripbora/" + relativePath),
                StandardCharsets.UTF_8);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
