package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여행 계획 확정으로 들어가는 자리.
 *
 * <p>이번 단계는 확정할 수 있는지 묻는 것까지다.
 * 실제로 확정하거나 최종본을 만드는 길은 아직 없다.
 */
class TravelPlanFinalizeUiContractTest {

    // ── 진입점 ──────────────────────────────────────────────

    @Test
    void onlyTheOwnerSeesTheWayIn() throws IOException {
        String ownerActions = ownerActionsHtml();

        // 조각 전체가 방장일 때만 나온다. 멤버에게는 통째로 비어서 온다
        assertThat(between(ownerActions, "th:with=\"viewerIsOwner=", "<th:block th:if="))
                .contains("travelPlan.currentMember.role.name() == 'OWNER'");
        assertThat(ownerActions).contains("th:if=\"${viewerIsOwner}\"");
        assertThat(ownerActions).contains(">여행 계획 확정</button>");
        // 상세 화면에는 방장 전용 markup 이 남아 있지 않다(출처가 하나다)
        assertThat(detailHtml()).doesNotContain("data-travel-plan-finalize-open");
    }

    @Test
    void itLeadsTheTopActionRowAheadOfTheCommonOnes() throws IOException {
        String detail = detailHtml();
        String topActions = between(detail, "class=\"travel-plan-top-actions\"",
                "class=\"travel-plan-notice\"");

        // 한 줄 안에서 방장 액션이 먼저, 그 다음이 참여자/채팅이다
        assertThat(topActions)
                .contains("data-travel-plan-owner-actions")
                .contains("travel-plan-common-actions");
        assertThat(topActions.indexOf("data-travel-plan-owner-actions"))
                .as("방장 액션이 공통 액션보다 앞에 온다")
                .isLessThan(topActions.indexOf("travel-plan-common-actions"));
        assertThat(ownerActionsHtml()).contains("data-travel-plan-finalize");
        // 크게 튀는 위험 버튼으로 두지 않는다. 다른 보조 액션과 같은 크기다
        String toggle = between(cssFile(), ".travel-plan-finalize-toggle {", "}");
        assertThat(toggle)
                .contains("padding: 4px 10px")
                .contains("font-size: 13px");
        assertThat(between(cssFile(), ".travel-plan-members-toggle {", "}"))
                .contains("padding: 4px 10px")
                .contains("font-size: 13px");
    }

    @Test
    void aLineSeparatesWhatOnlyTheOwnerCanDo() throws IOException {
        // 구분선이 방장 조각 안에 있어 멤버 화면에서는 선까지 함께 사라진다
        assertThat(ownerActionsHtml()).contains("class=\"travel-plan-action-divider\"");
        assertThat(detailHtml()).doesNotContain("travel-plan-action-divider");
        assertThat(between(cssFile(), ".travel-plan-action-divider {", "}"))
                .contains("width: 1px")
                .contains("background: var(--tp-plan-line-strong)");
    }

    @Test
    void aMemberSeesNoManagementAreaAtAll() throws IOException {
        // 조각이 통째로 비어서 오므로 그 자리에 아무 것도 남지 않는다
        assertThat(between(ownerActionsHtml(), "th:fragment=\"ownerActions", "</th:block>"))
                .contains("th:if=\"${viewerIsOwner}\"");
        // 빈 자리가 칸을 차지하지 않게 gap 만 가진 flex 다
        assertThat(between(cssFile(), ".travel-plan-owner-actions {", "}"))
                .contains("display: flex")
                .doesNotContain("padding")
                .doesNotContain("border");
        // 상세 화면에는 방장 전용 markup 이 남아 있지 않다
        assertThat(detailHtml())
                .doesNotContain("data-travel-plan-invite-toggle")
                .doesNotContain("data-travel-plan-finalize-open");
    }

    // ── 확인 창 ─────────────────────────────────────────────

    @Test
    void pressingItAsksBeforeAnythingHappens() throws IOException {
        String ownerActions = ownerActionsHtml();
        String finalizeJs = finalizeJs();

        assertThat(ownerActions)
                .contains("data-travel-plan-finalize-modal")
                .contains("여행 계획을 확정할까요?")
                .contains("확정하면 이후 일정은 수정할 수 없습니다.")
                .contains("현재 작성된 일정이 최종 여행 계획으로 저장됩니다.")
                .contains("data-travel-plan-finalize-cancel")
                .contains("data-travel-plan-finalize-confirm");
        // 열기 전까지 떠 있지 않다
        assertThat(between(ownerActions, "class=\"travel-plan-finalize-modal\"", ">"))
                .contains("hidden");
        // 누르자마자 확정하지 않는다. 창을 먼저 연다
        assertThat(between(finalizeJs, "openButton?.addEventListener", ";"))
                .contains("openModal()");
    }

    @Test
    void hidingTheConfirmationActuallyHidesIt() throws IOException {
        String css = cssFile();

        /*
          display 를 정해 두면 브라우저 기본 [hidden] 규칙을 덮어써 계속 보인다.
          확정 창과 삭제 창이 같은 모양이라 규칙을 나눠 갖는다.
        */
        assertThat(between(css,
                ".travel-plan-finalize-modal,\n.travel-plan-plan-delete-modal {", "\n}"))
                .contains("display: flex");
        assertThat(css).contains(".travel-plan-finalize-modal[hidden],\n"
                + ".travel-plan-plan-delete-modal[hidden] {\n    display: none;\n}");
    }

    @Test
    void theConfirmationCanBeBackedOutOf() throws IOException {
        String finalizeJs = finalizeJs();

        assertThat(finalizeJs).contains("data-travel-plan-finalize-cancel");
        assertThat(between(finalizeJs, "document.addEventListener(\"keydown\"", "});"))
                // 방장이 바뀌면 창도 갈리므로 그때의 창을 그때 본다
                .contains("event.key !== \"Escape\" || !modal || modal.hidden")
                .contains("closeModal()");
        assertThat(between(finalizeJs, "modal.addEventListener(\"click\"", "});"))
                .contains("if (event.target === modal) closeModal()");
    }

    @Test
    void aRunningPollIsNeverMentionedAsAProblem() throws IOException {
        String detail = detailHtml();
        String finalizeJs = finalizeJs();

        // 투표는 계획을 정하는 데 도우려는 것이지 확정의 조건이 아니다
        assertThat(between(ownerActionsHtml(), "class=\"travel-plan-finalize-modal\"",
                "data-travel-plan-finalize-confirm"))
                .doesNotContain("투표");
        /*
          물어보고 경고하는 대목에 투표가 끼어들지 않는다.
          (완료된 뒤 투표 센터를 화면에서 내리는 것은 확정 조건과 다른 이야기다)
        */
        for (String decides : new String[]{"async function check()", "function renderWarning(",
                "function renderReady()", "async function finalizePlan()"}) {
            assertThat(between(finalizeJs, decides, "\n    }"))
                    .as("%s", decides)
                    .doesNotContain("poll")
                    .doesNotContain("투표");
        }
    }

    // ── 확정할 수 있는지는 서버가 본다 ──────────────────────

    @Test
    void theScreenAsksTheServerInsteadOfDecidingForItself() throws IOException {
        String finalizeJs = finalizeJs();

        assertThat(between(finalizeJs, "async function check()", "\n    }"))
                .contains("/finalize/check")
                .contains("method: \"POST\"")
                .contains("csrfHeaders()")
                .contains("payload.activeEditorExists")
                .contains("renderWarning(payload.activeEditorDisplayNames || [])");
        // 누가 편집 중인지 화면이 스스로 판단하지 않는다
        assertThat(finalizeJs)
                .doesNotContain("is-editing")
                .doesNotContain("is-remote-editing")
                .doesNotContain("travelPlanRealtime");
    }

    @Test
    void theRouteIsProtectedLikeTheOtherWritingOnes() throws IOException {
        String security = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 확정 검사 POST 도 다른 쓰기 요청과 같은 기본 CSRF 정책을 받는다
        assertThat(security)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    @Test
    void beingWarnedLeavesTheConfirmationOpenToDecide() throws IOException {
        String check = between(finalizeJs(), "async function check()", "\n    }");

        // 알려 주기만 하고 창을 닫지 않는다. 판단은 방장이 한다
        assertThat(check).doesNotContain("closeModal()");
        assertThat(check).contains("if (confirmButton) confirmButton.disabled = false");
    }

    // ── 편집 중인 사람이 있을 때 ────────────────────────────

    @Test
    void whoIsWritingIsNamedRatherThanTheDoorBeingShut() throws IOException {
        String detail = detailHtml();
        String finalizeJs = finalizeJs();

        assertThat(ownerActionsHtml()).contains("data-travel-plan-finalize-warning");
        assertThat(between(finalizeJs, "function editingSentence(names)", "\n    }"))
                // 이름 뒤의 "님" 은 한 명일 때와 여럿일 때가 달라 따로 붙인다
                .contains("${first}님")
                .contains("이 현재 일정을 편집 중입니다.")
                .contains("지금 완료하면 저장하지 않은 편집 내용은 사라질 수 있습니다.");
        // 이름도 사용자가 정한 값이라 글자로만 넣는다
        assertThat(finalizeJs)
                .contains("warning.textContent = editingSentence(names)")
                .doesNotContain("innerHTML");
    }

    @Test
    void severalWritersAreSaidShortly() throws IOException {
        // 한 명이면 그 이름, 여럿이면 첫 사람과 나머지 수로 줄여 쓴다
        assertThat(between(finalizeJs(), "function editingSentence(names)", "\n    }"))
                .contains("rest.length === 0 ? `${first}님` : `${first}님 외 ${rest.length}명`");
    }

    @Test
    void theOwnerCanGoAheadAnywayOrBackOut() throws IOException {
        String finalizeJs = finalizeJs();

        // 경고가 뜨면 버튼이 "그래도 완료" 가 된다
        assertThat(between(finalizeJs, "function renderWarning(names)", "\n    }"))
                .contains("force = true")
                .contains("confirmButton.textContent = \"그래도 완료\"");
        // 취소는 그대로 남아 있다
        assertThat(ownerActionsHtml()).contains("data-travel-plan-finalize-cancel");
        // 아무도 쓰고 있지 않으면 원래 문구로 돌아간다
        assertThat(between(finalizeJs, "function renderReady()", "\n    }"))
                .contains("force = false")
                .contains("confirmButton.textContent = \"여행 계획 확정\"");
    }

    @Test
    void whatTheOwnerDecidedIsCarriedAsItsOwnFlag() throws IOException {
        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanFinalizeService.java"),
                StandardCharsets.UTF_8);

        /*
          그냥 완료와 알고도 하는 완료를 서버가 구분할 수 있어야 한다.
          다음 단계의 실제 완료가 이 자리를 그대로 쓴다.
        */
        assertThat(service)
                .contains("requireFinalizable(Principal principal, Long travelPlanId,")
                .contains("boolean force")
                .contains("if (!force &&");
    }

    // ── 이번 단계에서 하지 않는 것 ──────────────────────────

    @Test
    void pressingItActuallyFinalisesAndCarriesWhatWasDecided() throws IOException {
        String finalizeJs = finalizeJs();

        assertThat(between(finalizeJs, "async function finalizePlan()", "\n    }"))
                .contains("/travel-plans/${planId}/finalize?force=${force}")
                .contains("method: \"POST\"")
                .contains("csrfHeaders()")
                // 성공하면 이 화면은 목록으로 돌아간다
                .contains("leaveToList()");
        // 실패하면 창을 열어 둔 채 사유만 알린다
        assertThat(between(finalizeJs, "async function finalizePlan()", "\n    }"))
                .contains("showError(payload?.message)");
    }

    @Test
    void everyoneInTheRoomGoesBackToTheListTheSameMoment() throws IOException {
        String finalizeJs = finalizeJs();

        /*
          확정한 방장도, 그 방을 열어 두고 있던 다른 참여자도 목록으로 돌아간다.
          최종본으로 바로 보내지 않는다. 완료된 여행은 목록에서 눌러 들어간다.
        */
        String leave = between(finalizeJs, "function leaveToList()", "\n    }");
        assertThat(leave)
                .contains("window.location.href = \"/travel-plans\"")
                .doesNotContain("/final");
        // 같은 알림이 두 번 와도 한 번만 움직인다
        assertThat(leave).contains("if (leaving) return");
        // 옮겨 가는 사이에도 고칠 수 없다
        assertThat(leave).contains("markCompleted()");
        assertThat(finalizeJs)
                .contains("document.addEventListener(\"travelplan:plan-completed\","
                        + " () => leaveToList())");
        assertThat(resource("/static/js/travel-plan-realtime.js"))
                .contains("payload.type === \"PLAN_COMPLETED\"");
    }

    @Test
    void theRoomStopsBeingEditableWithoutAReload() throws IOException {
        String finalizeJs = finalizeJs();
        String css = cssFile();

        assertThat(between(finalizeJs, "function markCompleted()", "\n    }"))
                .contains("planner.classList.add(\"is-completed\")")
                .contains("여행 계획이 완료되었어요.");
        // 다시 읽어 오지 않는다. 이 화면은 그대로 잠기고 목록으로 넘어간다
        assertThat(finalizeJs).doesNotContain("location.reload");
        // 다른 사람도 같은 처리를 받는다
        assertThat(finalizeJs).contains("travelplan:plan-completed");
        // 완료된 뒤에는 줄을 눌러도 편집기가 열리지 않는다
        assertThat(css).contains(".travel-plan-paper.is-completed");
    }

    @Test
    void whatWeWereDoingTogetherLeavesTheScreenTheSameMoment() throws IOException {
        String finalizeJs = finalizeJs();

        /*
          서버는 이미 전부 거부한다. 여기서 하는 일은
          눌러 보고 오류로 알게 두지 않는 것이다.
        */
        String closes = between(finalizeJs, "const COMPLETED_CLOSES = [", "];");
        for (String gone : new String[]{
                // 채팅 진입점과 열려 있는 채팅창
                "[data-travel-plan-chat]", "[data-travel-plan-chat-panel]",
                // 투표 센터
                "[data-travel-plan-poll-modal]",
                // 초대 버튼과 팝오버
                "[data-travel-plan-invite]",
                // 완료 버튼과 확인 창
                "[data-travel-plan-finalize]", "[data-travel-plan-finalize-modal]"}) {
            assertThat(closes).as("완료 뒤에도 남는 협업 진입점: %s", gone).contains(gone);
        }

        // 열려 있던 것도 함께 닫힌다
        String body = between(finalizeJs, "function markCompleted()", "\n    }");
        assertThat(body)
                .contains("COMPLETED_CLOSES.forEach")
                .contains("element.hidden = true")
                .contains("aria-expanded");
    }

    @Test
    void theClosingReusesTheHiddenRuleAlreadyInPlace() throws IOException {
        String css = cssFile();

        /*
          [hidden] 이 실제로 먹는지 확인한다.
          진입점 셋은 display 를 정해 두지 않아 브라우저 기본 규칙이 그대로 통하고,
          따로 뜨는 창들은 각자 [hidden] 규칙을 이미 갖고 있다.
          (display 를 정해 둔 요소는 [hidden] 만으로 감춰지지 않는다)
        */
        for (String entry : new String[]{
                ".travel-plan-chat", ".travel-plan-invite", ".travel-plan-finalize"}) {
            assertThat(between(css, "\n" + entry + " {", "}"))
                    .as("%s", entry).doesNotContain("display:");
        }
        for (String floating : new String[]{
                ".travel-plan-chat-panel", ".travel-plan-poll-modal",
                ".travel-plan-finalize-modal"}) {
            assertThat(css).as("%s", floating).contains(floating + "[hidden]");
        }
    }

    @Test
    void everyoneInTheRoomGetsTheSameReadOnlyScreen() throws IOException {
        String finalizeJs = finalizeJs();

        /*
          확정 버튼은 방장에게만 있다.
          읽기 전용 전환이 그 버튼을 찾는 자리보다 뒤에 있으면
          멤버 화면에서는 아무 일도 일어나지 않는다.
        */
        assertThat(between(ownerActionsHtml(), "th:with=\"viewerIsOwner=", "<th:block th:if="))
                .contains("role.name() == 'OWNER'");

        int listener = finalizeJs.indexOf("travelplan:plan-completed");
        int ownerOnly = finalizeJs.indexOf("function bindOwnerControls()");
        assertThat(listener).isGreaterThan(0);
        assertThat(ownerOnly).as("방장 화면에만 있는 준비").isGreaterThan(0);
        assertThat(listener).as("읽기 전용 전환은 방장 여부를 가리기 전에 붙는다")
                .isLessThan(ownerOnly);
        // 그 준비 안에서만 방장 여부로 갈라진다
        assertThat(between(finalizeJs, "function bindOwnerControls()", "\n    }"))
                .contains("if (!root || !modal || !planId)");
    }

    // ── 완료와 일정 저장이 겹칠 때 ─────────────────────────

    @Test
    void finalisingAndSavingAScheduleStandInOneLine() throws IOException {
        String planService = source("service/travelplan/TravelPlanService.java");
        String finalizeService = source("service/travelplan/TravelPlanFinalizeService.java");

        /*
          둘 다 같은 방 row 를 잠그고 시작한다.
          저장이 먼저 끝나면 그 변경까지 최종본에 담기고,
          완료가 먼저면 저장은 상태가 바뀐 뒤라 거부된다.
        */
        assertThat(planService)
                .contains("private PlanAccess requireActiveAccessForWrite(")
                .contains("findPlanByIdAndStatusForUpdate(");
        assertThat(finalizeService).contains("findPlanByIdAndStatusForUpdate(");

        // 고치는 길은 모두 잠그고 읽는 쪽을 쓴다. 읽기만 하는 세 곳은 그대로 둔다
        // (방 상세 / DAY 상세 / 참여자 명단)
        assertThat(countOf(planService, "requireActiveAccessForWrite(userId, travelPlanId)"))
                .isEqualTo(12);
        assertThat(countOf(planService, "requireActiveAccess(userId, travelPlanId)"))
                .isEqualTo(3);
    }

    @Test
    void aCompletedRoomTurnsEveryWritingPathAway() throws IOException {
        String roomAccess = source("service/travelplan/TravelPlanRoomAccess.java");
        String planService = source("service/travelplan/TravelPlanService.java");

        /*
          완료된 방은 더 이상 ACTIVE 가 아니다.
          일정·채팅·투표·실시간 편집이 모두 같은 조건을 지나므로 한 번에 막힌다.
        */
        assertThat(roomAccess).contains("TravelPlanStatus.ACTIVE.name()");
        assertThat(planService).contains("TravelPlanStatus.ACTIVE.name()");
    }

    @Test
    void theFinalCopyIsWrittenOnceAndOnlyOnce() throws IOException {
        String schema = Files.readString(
                Path.of("docs/db/tripbora_schema_reference.md"), StandardCharsets.UTF_8);
        String finalizeService = source("service/travelplan/TravelPlanFinalizeService.java");

        // 마지막 방어는 DB 다. 한 방에 최종본은 하나뿐이다
        assertThat(schema).contains("UNIQUE KEY `uk_travel_plan_final_snapshots_plan`"
                + " (`travel_plan_id`)");
        // 그 전에 알아보기 쉽게 한 번 끊는다
        assertThat(finalizeService).contains("existsByPlanId(travelPlanId)");
        // 상태도 지금 상태가 기대한 그대로일 때만 옮긴다
        assertThat(source("repository/travelplan/TravelPlanMapper.java"))
                .contains("updatePlanStatus(");
    }

    @Test
    void nothingIsSweptAwayUntilTheFinalisingSticks() throws IOException {
        String finalizeService = source("service/travelplan/TravelPlanFinalizeService.java");
        String listener = source("service/travelplan/TravelPlanCompletedListener.java");

        // 완료 안에서는 알림만 남긴다
        assertThat(finalizeService).contains("new TravelPlanCompletedEvent(travelPlanId)");
        assertThat(finalizeService).doesNotContain("releaseAllByPlan");
        // 실제로 걷어 내는 것은 커밋이 끝난 뒤다
        assertThat(listener)
                .contains("TransactionPhase.AFTER_COMMIT")
                .contains("releaseAllByPlan(travelPlanId)")
                .contains("PLAN_COMPLETED");
    }

    @Test
    void thePollsAndTalkAreNotCopiedIntoTheFinalCopy() throws IOException {
        String finalizeService = source("service/travelplan/TravelPlanFinalizeService.java");

        // 투표 결과를 최종 일정에 옮겨 적지 않는다
        assertThat(finalizeService)
                .doesNotContain("Poll")
                .doesNotContain("Chat");
    }

    @Test
    void aCompletedRoomScreenIsStillNotPartOfThis() throws IOException {
        // 완료된 여행 전용 화면과 숨김/삭제는 다음 단계다
        assertThat(detailHtml())
                .doesNotContain("완료된 여행")
                .doesNotContain("숨기기");
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    /**
     * 방장에게만 있는 상단 액션(확정 / 초대 / 확정 확인 창).
     * 방장이 바뀌면 통째로 갈리므로 상세 화면이 아니라 이 조각에 있다.
     */
    private String ownerActionsHtml() throws IOException {
        return resource("/templates/travelplan/fragments/owner-actions.html");
    }

    private String finalizeJs() throws IOException {
        return resource("/static/js/travel-plan-finalize.js");
    }

    private String cssFile() throws IOException {
        return resource("/static/css/travel-plan.css");
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(
                Path.of("src/main/java/com/tripbora/" + relativePath),
                StandardCharsets.UTF_8);
    }

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
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
