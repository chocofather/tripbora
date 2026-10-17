package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A 일정 변경을 다른 화면에 반영하는 흐름의 계약.
 * WebSocket 은 "이 DAY 를 다시 읽어라" 는 신호만 나르고 저장 경로는 그대로 HTTP 다.
 */
class TravelPlanScheduleSyncContractTest {

    @Test
    void everyDayHasAStableWrapperToSwap() throws IOException {
        String fragment = fragmentHtml();

        assertThat(fragment)
                .contains("data-travel-plan-day-id=${day.id}")
                .contains("data-travel-plan-day")
                // 전체를 한 번에 맞출 때 갈아 끼울 바깥 상자도 있다
                .contains("data-travel-plan-days");
        assertThat(detailHtml()).contains("data-travel-plan-days");
    }

    @Test
    void theFirstRenderAndTheLiveRefreshShareOneMarkup() throws IOException {
        String detail = detailHtml();
        String fragment = fragmentHtml();

        // 화면이 갈라지지 않도록 DAY markup 은 fragment 한 곳에만 있다
        assertThat(fragment)
                .contains("th:fragment=\"scheduleDay(")
                .contains("th:fragment=\"scheduleDays(");
        assertThat(detail)
                .contains("~{travelplan/fragments/schedule-day :: scheduleDay(")
                // 같은 markup 을 복제해 두지 않는다
                .doesNotContain("class=\"travel-plan-line is-item\"")
                .doesNotContain("data-travel-plan-slot-form");

        String controller = source("controller/travelplan/TravelPlanController.java");
        assertThat(controller)
                .contains("travelplan/fragments/schedule-day :: scheduleDay(")
                .contains("travelplan/fragments/schedule-day :: scheduleDays(");
    }

    @Test
    void theClientListensOnItsOwnRoomScheduleTopic() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime)
                .contains("/topic/travel-plans/${planId}/schedule")
                .contains("/topic/travel-plans/${planId}/presence")
                .contains("payload.affectedDayIds");
    }

    @Test
    void oneConnectionCarriesBothSubscriptions() throws IOException {
        String realtime = realtimeJs();

        // 브라우저 하나가 /ws 에 두 번 붙지 않는다
        assertThat(countOf(realtime, "new StompJs.Client")).isEqualTo(1);
        assertThat(countOf(realtime, "client.activate()")).isEqualTo(1);
        // presence / 참여자 명단 / 내 접근 회수 / schedule / editor / 내 잠금 응답 /
        // 채팅 / 내 채팅 응답 / 투표가 모두 한 연결 위에 있다
        assertThat(countOf(realtime, "client.subscribe(")).isEqualTo(9);
    }

    @Test
    void aChangedDayIsFetchedBackInsteadOfReloadingThePage() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime)
                .contains("/travel-plans/${planId}/days/${dayId}/fragment")
                .contains("target.replaceWith(fresh)");
        // 정상 이벤트 처리에 새로고침을 쓰지 않는다
        assertThat(realtime)
                .doesNotContain("location.reload")
                .doesNotContain("window.location =");
    }

    @Test
    void aSlowOldResponseCannotOverwriteANewerScreen() throws IOException {
        String realtime = realtimeJs();

        // DAY 마다 마지막 요청 번호를 기억하고, 최신 응답만 반영한다
        assertThat(realtime)
                .contains("dayRequests")
                .contains("dayRequests.set(String(dayId), sequence)")
                .contains("if (dayRequests.get(String(dayId)) !== sequence) return");
    }

    @Test
    void aDayBeingEditedIsNotReplacedUnderTheUser() throws IOException {
        String realtime = realtimeJs();

        // 편집 중이면 미뤄 두었다가 편집이 끝난 뒤에 반영한다
        assertThat(realtime)
                .contains("function isEditing(")
                .contains("pendingDays.add(String(dayId))")
                .contains("travelplan:editor-idle")
                .contains("pendingResync");
        // 응답이 도착한 시점에도 다시 확인한다
        assertThat(countOf(realtime, "pendingDays.add(String(dayId))")).isEqualTo(2);
    }

    @Test
    void theEditorTellsWhenItIsDoneWithoutExposingItsInternals() throws IOException {
        String scheduler = schedulerJs();

        // 실시간 쪽이 편집기 내부 변수를 들여다보지 않도록 DOM 이벤트로만 알린다
        assertThat(scheduler)
                .contains("travelplan:editor-idle")
                .contains("function notifyEditorIdle()")
                .contains("if (activeLine || activeAlt) return");
        // 실시간 연결은 여전히 편집 스크립트 밖에 있다
        assertThat(scheduler)
                .doesNotContain("StompJs")
                .doesNotContain("WebSocket");
    }

    @Test
    void refreshedMarkupGetsItsBehaviourBack() throws IOException {
        String realtime = realtimeJs();
        String scheduler = schedulerJs();

        // 갈아 끼운 요소를 함께 알려 주고
        assertThat(realtime).contains("travelplan:schedule-updated")
                .contains("detail: { root: fresh }");
        // 편집 쪽이 그 부분에만 다시 동작을 붙인다
        assertThat(scheduler)
                .contains("function bindScheduleRoot(root)")
                .contains("bindScheduleRoot(planner)")
                .contains("travelplan:schedule-updated");
    }

    @Test
    void aReconnectCatchesUpInOneRequestNotOnePerDay() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime)
                .contains("/travel-plans/${planId}/schedule/fragment")
                .contains("connectedBefore")
                // 처음 그린 화면은 이미 최신이라 따라잡을 것이 없다
                .contains("if (connectedBefore) {")
                .contains("resyncSchedule()");
    }

    @Test
    void savingStillGoesThroughTheExistingHttpForms() throws IOException {
        String realtime = realtimeJs();
        String fragment = fragmentHtml();

        /*
          WebSocket 으로 보내는 것은 접속 인사, 작성 중 상태(잠금/임시내용/해제/동기화),
          그리고 채팅뿐이다. 일정 저장 계열 destination 은 없다.
          (채팅은 대화 자체가 DB 에 남아야 하는 것이라 WebSocket 으로 보낸다.
           일정은 지금까지처럼 기존 HTTP POST 폼 그대로다)
        */
        assertThat(countOf(realtime, "client.publish(")).isEqualTo(6);
        assertThat(realtime)
                .contains("/app/travel-plans/${planId}/presence/join")
                .contains("/app/travel-plans/${planId}/editor/lock")
                .contains("/app/travel-plans/${planId}/editor/draft")
                .contains("/app/travel-plans/${planId}/editor/unlock")
                .contains("/app/travel-plans/${planId}/editor/sync")
                .contains("/app/travel-plans/${planId}/chat/${action}");
        // 일정을 WebSocket 으로 저장하지 않는다
        assertThat(realtime)
                .doesNotContain("/app/travel-plans/${planId}/items")
                .doesNotContain("/app/travel-plans/${planId}/days");
        // 기존 POST 폼은 그대로다
        assertThat(fragment)
                .contains("/items/${item.id}/update|}")
                .contains("/items/${item.id}/move-up|}")
                .contains("/items/${item.id}/move-down|}")
                .contains("hidden data-travel-plan-reorder-forms")
                .contains("method=\"post\"");
    }

    @Test
    void alternativeChangesAreBroadcastOnTheirOwnDay() throws IOException {
        String service = source("service/travelplan/TravelPlanService.java");

        // A 와 같은 경로로 알린다. 대안 전용 알림 통로를 따로 만들지 않는다
        assertThat(between(service, "public void addAlternative(", "public void updateAlternative("))
                .contains("publishScheduleChange(travelPlanId, dayId")
                .contains("ALTERNATIVE_ADDED");
        assertThat(between(service, "public void updateAlternative(", "public void deleteAlternative("))
                .contains("publishScheduleChange(travelPlanId, dayId")
                .contains("ALTERNATIVE_UPDATED");
        assertThat(between(service, "public void deleteAlternative(", "private void shiftSecondAlternativeUp("))
                .contains("publishScheduleChange(travelPlanId, dayId")
                .contains("ALTERNATIVE_DELETED");
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    private String fragmentHtml() throws IOException {
        return resource("/templates/travelplan/fragments/schedule-day.html");
    }

    private String realtimeJs() throws IOException {
        return resource("/static/js/travel-plan-realtime.js");
    }

    private String schedulerJs() throws IOException {
        return resource("/static/js/travel-plan-scheduler.js");
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
