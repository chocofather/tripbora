package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러 본체의 겉모습.
 *
 * <p>일정 구조와 동작은 그대로 두고, 색·간격·상단 액션의 자리만 본다.
 * 갈색 하나로 모든 요소를 통일하지 않는다.
 */
class TravelPlanSchedulerUiContractTest {

    // ── 색 ──────────────────────────────────────────────────

    @Test
    void theDayHeadingsAndRulesAreNeutralRatherThanBrown() throws IOException {
        String css = cssFile();

        // DAY 번호가 이 구역의 강조점이다
        assertThat(between(css, ".travel-plan-day-number {", "}"))
                .contains("color: var(--tp-plan-accent-strong)");
        assertThat(between(css, ".travel-plan-day-date {", "}"))
                .contains("color: var(--tp-plan-ink-faint)");
        assertThat(between(css, ".travel-plan-lines {", "}"))
                .contains("border-top: 1px solid var(--tp-plan-line)");
        assertThat(between(css, ".travel-plan-line {", "}"))
                .contains("border-bottom: 1px solid var(--tp-plan-line)");
    }

    @Test
    void theDayNumberAndItsDateReadAsOneHeading() throws IOException {
        String heading = between(cssFile(), ".travel-plan-day-heading {", "}");

        // 아주 옅은 바탕 한 겹으로 둘을 묶는다
        assertThat(heading).contains("background: var(--tp-plan-accent-soft)");
        // 아래 줄에 그대로 얹히므로 카드로 떠 보이지 않는다
        assertThat(heading)
                .contains("border-radius: 4px 4px 0 0")
                .doesNotContain("box-shadow")
                .doesNotContain("border:");
        // 날짜는 작은 알약 하나로만 둔다
        assertThat(between(cssFile(), ".travel-plan-day-date {", "}"))
                .contains("border-radius: 999px")
                .contains("font-size: 12px");
    }

    @Test
    void theDetailTitleDoesNotRepeatThePlanStatus() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .doesNotContain("travel-plan-detail-status")
                .contains("id=\"travel-plan-detail-title\"")
                .contains("class=\"tp-planner-period\"");
    }

    @Test
    void anItemRowLightsUpUnderThePointerOnly() throws IOException {
        String css = cssFile();

        // 짧고 과하지 않은 전환
        assertThat(between(css, ".travel-plan-line {", "}"))
                .contains("transition: background-color 0.12s ease");
        // 손가락으로 쓰는 화면에는 hover 를 걸지 않는다
        assertThat(css).contains("@media (hover: hover) {");
        assertThat(between(css, "@media (hover: hover) {", "\n}"))
                .contains(".travel-plan-line.is-item:hover");
        /*
          ⋯ 는 hover 에서 드러나므로, hover 가 없는 화면에서는
          늘 보이게 두어야 일정을 옮기거나 지울 길이 남는다.
        */
        assertThat(between(css, "@media (hover: none) {", "\n}"))
                .contains(".travel-plan-item-menu-button");
    }

    @Test
    void onlyFinishingTheTripCarriesColourInTheTopRow() throws IOException {
        String css = cssFile();

        // 이 줄에서 색을 띠는 버튼은 하나뿐이다
        assertThat(between(css, ".travel-plan-finalize-toggle {", "}"))
                .contains("background: var(--tp-plan-accent-soft)")
                .contains("color: var(--tp-plan-accent-strong)");
        // 같은 줄의 나머지는 모두 같은 중립 ghost 다
        for (String toggle : new String[]{
                ".travel-plan-invite-toggle {",
                ".travel-plan-members-toggle {"}) {
            assertThat(between(css, toggle, "}")).as("%s", toggle)
                    .contains("background: #fff")
                    .contains("color: var(--tp-plan-ink-faint)")
                    .contains("padding: 4px 10px")
                    .contains("font-size: 13px");
        }
    }

    @Test
    void theMainScheduleStaysNeutralWhileItsAlternativesCarryTheAccent() throws IOException {
        String css = cssFile();

        // A 일정은 기본 neutral
        assertThat(between(css, ".travel-plan-line-content {", "}"))
                .contains("color: var(--tp-plan-ink)");
        // B/C 는 같은 accent 계열을 아주 연하게 쓴다
        assertThat(between(css, ".travel-plan-alt-mark {", "}"))
                .contains("color: var(--tp-plan-accent)");
        assertThat(between(css, ".travel-plan-alt-list {", "}"))
                .contains("border-left: 1px solid var(--tp-plan-accent-line)");
        assertThat(between(css, ".travel-plan-alt-toggle {", "}"))
                .contains("color: var(--tp-plan-accent)");
    }

    // ── + 일정 추가 ─────────────────────────────────────────

    @Test
    void addingAScheduleReadsAsAnActionNotAPlaceholder() throws IOException {
        String css = cssFile();
        String hint = between(css, ".travel-plan-slot-hint {", "}");

        // 약한 accent 를 띤 글자 액션이다
        assertThat(hint)
                .contains("color: var(--tp-plan-accent)")
                .contains("font-weight: 600");
        // 테두리를 두른 큰 버튼 박스를 만들지 않는다
        assertThat(hint).doesNotContain("border:");
        // + 만 조금 더 또렷한 표시를 가진다
        assertThat(between(css, ".travel-plan-slot-plus {", "}"))
                .contains("border-radius: 50%")
                .contains("background: var(--tp-plan-accent-soft)");
        // 누를 수 있다는 것이 hover/focus 에서 더 분명해진다
        assertThat(css).contains(".travel-plan-line.is-slot:focus-within .travel-plan-slot-hint");
        assertThat(between(css, ".travel-plan-line.is-slot:hover .travel-plan-slot-hint,", "}"))
                .contains("background: var(--tp-plan-accent-soft)")
                .contains("color: var(--tp-plan-accent-strong)");
        // 기존 슬롯 안에서 타입별 추가 액션을 제공한다.
        assertThat(scheduleDayHtml())
                .contains("data-add-item-type=\"SCHEDULE\"")
                .contains("data-add-item-type=\"ACCOMMODATION\"")
                .contains("data-add-item-type=\"TRANSPORT\"")
                .contains("data-add-item-type=\"FLIGHT\"");
    }

    // ── 빈 DAY 간격 ─────────────────────────────────────────

    @Test
    void anEmptyDayNoLongerTakesUpSoMuchHeight() throws IOException {
        String css = cssFile();

        // 일정이 없는 DAY 는 머리글 + 추가 슬롯 한 줄이 전부다
        assertThat(between(css, ".travel-plan-line.is-slot {", "}"))
                .contains("padding: 7px 8px");
        // 일정이 있는 줄은 그대로 넉넉하다
        assertThat(between(css, ".travel-plan-line {", "}")).contains("padding: 14px 8px");
        // DAY 사이는 좁히되 구분은 남긴다
        assertThat(between(css, ".travel-plan-day {", "}")).contains("margin-bottom: 32px");
    }

    // ── 상단 액션 / 머리글 ──────────────────────────────────

    @Test
    void theOwnersActionsComeFirstAndTheCommonOnesFollow() throws IOException {
        String detail = detailHtml();
        String topActions = between(detail, "class=\"travel-plan-top-actions\"",
                "class=\"travel-plan-notice\"");

        // [확정][초대] | [참여자]  — 채팅은 이 줄이 아니라 떠 있는 버튼이다
        assertThat(topActions)
                .contains("data-travel-plan-owner-actions")
                .contains("data-travel-plan-members-toggle")
                .doesNotContain("data-travel-plan-chat-toggle");
        assertThat(topActions.indexOf("data-travel-plan-owner-actions"))
                .isLessThan(topActions.indexOf("data-travel-plan-members-toggle"));
        assertThat(ownerActionsHtml())
                .contains(">여행 계획 확정</button>")
                .contains(">초대</button>");
        assertThat(ownerActionsHtml().indexOf(">여행 계획 확정</button>"))
                .isLessThan(ownerActionsHtml().indexOf(">초대</button>"));
        // 두 묶음은 각각 자기 gap 을 가진 flex 다
        assertThat(between(cssFile(), ".travel-plan-owner-actions {", "}"))
                .contains("display: flex");
        assertThat(between(cssFile(), ".travel-plan-common-actions {", "}"))
                .contains("display: flex");
    }

    @Test
    void theTitleKeepsTheTripLengthBesideIt() throws IOException {
        String header = between(detailHtml(), "class=\"travel-plan-detail-header\"", "</header>");

        assertThat(header)
                .contains("travel-plan-detail-heading")
                .contains("travel-plan-detail-period")
                // 며칠짜리 여행인지 제목 옆에 함께 적는다
                .contains("travel-plan-detail-length")
                .contains("|${dayCount - 1}박 ${dayCount}일|");
        assertThat(between(cssFile(), ".travel-plan-detail-header {", "}"))
                .contains("border-bottom: 1px solid var(--tp-plan-line)");
    }

    /** 방장이 바뀌었을 때 조각만 갈아 끼우는 구조는 그대로여야 한다. */
    @Test
    void movingTheOwnerActionsDidNotBreakTheLiveSwap() throws IOException {
        String realtime = resource("/static/js/travel-plan-realtime.js");

        // 자리를 찾는 방법이 위치가 아니라 표시라 옮겨도 그대로 동작한다
        assertThat(realtime)
                .contains("document.querySelector(\"[data-travel-plan-owner-actions]\")")
                .contains("owner-actions/fragment")
                .contains("travelplan:owner-actions-updated");
        assertThat(detailHtml()).contains("data-travel-plan-owner-actions");
    }

    // ── 좁은 화면 ───────────────────────────────────────────

    @Test
    void nothingInTheSheetCanPushTheScreenWider() throws IOException {
        String css = cssFile();

        /*
          flex 칸의 기본 최소 폭은 내용 크기다.
          띄어쓰기 없는 긴 글이나 입력칸 기본 폭이 그대로 줄의 최소 폭이 되면
          종이 밖으로 밀려 나가 페이지에 가로 스크롤이 생긴다.
        */
        assertThat(between(css, ".travel-plan-line-body {", "}")).contains("min-width: 0");
        assertThat(between(css, ".travel-plan-slot-form {", "}")).contains("min-width: 0");
        assertThat(between(css, ".travel-plan-alt-body {", "}")).contains("min-width: 0");
        // 편집기의 입력칸도 마찬가지다
        assertThat(between(css, ".travel-plan-item-editor textarea {", "}"))
                .contains("min-width: 0")
                .contains("box-sizing: border-box")
                .contains("width: 100%");
        assertThat(between(css, ".travel-plan-slot-form textarea {", "}"))
                .contains("min-width: 0");
        // 긴 여행 제목과 남이 작성 중이라는 글자도 줄어들 수 있다
        assertThat(between(css, ".travel-plan-detail-header h1 > span {", "}"))
                .contains("min-width: 0");
        assertThat(between(css, ".travel-plan-remote-note {", "}")).contains("min-width: 0");
        // 넘치는 것을 가리는 것이 아니라 줄어들게 둔다
        assertThat(between(css, ".travel-plan-page {", "}")).doesNotContain("overflow-x: hidden");
    }

    @Test
    void theFloatingChatButtonNeverSitsOnTheLastSchedule() throws IOException {
        String css = cssFile();

        /*
          떠 있는 채팅 버튼은 오른쪽 아래를 늘 차지한다.
          아래 여백이 그보다 얕으면 마지막 DAY 의 "일정 추가" 가 그 밑에 깔린다.
        */
        assertThat(between(css, ".travel-plan-page {", "}")).contains("padding: 32px 20px 88px");
        assertThat(between(css, "@media (max-width: 720px) {", "\n}"))
                .contains("padding: 20px 12px 88px");
    }

    @Test
    void theEditorKeepsTheSameInkAsTheLineItReplaces() throws IOException {
        String css = cssFile();

        // 눌러서 편집기가 열려도 글자색이 달라지지 않는다
        assertThat(between(css, ".travel-plan-line-content {", "}"))
                .contains("color: var(--tp-plan-ink)");
        assertThat(between(css, ".travel-plan-item-editor textarea {", "}"))
                .contains("color: var(--tp-plan-ink)");
        assertThat(between(css, ".travel-plan-slot-form textarea {", "}"))
                .contains("color: var(--tp-plan-ink)");
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    private String scheduleDayHtml() throws IOException {
        return resource("/templates/travelplan/fragments/schedule-day.html");
    }

    private String ownerActionsHtml() throws IOException {
        return resource("/templates/travelplan/fragments/owner-actions.html");
    }

    private String cssFile() throws IOException {
        return resource("/static/css/travel-plan.css");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String between(String source, String start, String end) {
        int from = source.indexOf(start);
        assertThat(from).as("start marker %s", start).isNotNegative();
        int to = source.indexOf(end, from + start.length());
        assertThat(to).as("end marker %s", end).isNotNegative();
        return source.substring(from, to);
    }
}
