package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 플래너 안의 대안(B/C) 화면 계약.
 * 대안은 A 아래에 들여쓰기된 메모처럼 붙고, 별도 페이지나 카드가 되지 않는다.
 */
class TravelPlanAlternativeUiContractTest {

    @Test
    void theRemovedItemMenuDoesNotExposeAnAlternativeAddAction() throws IOException {
        String detail = detailHtml();

        assertThat(detail).doesNotContain("data-travel-plan-menu-list")
                .doesNotContain("data-travel-plan-alt-add")
                .doesNotContain(">대안 추가</button>");
    }

    @Test
    void theAddActionDisappearsOnceTwoAlternativesExist() throws IOException {
        String detail = detailHtml();

        // 화면에서도 막고 Service 에서도 막는다
        assertThat(detail)
                .contains("altCount=${alternatives == null ? 0 : alternatives.size()}")
                // 속성 값 안에서는 파서가 걸리지 않도록 lt 를 쓴다
                .contains("th:if=\"${altCount lt 2}\"");
        String service = Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanService.java"),
                StandardCharsets.UTF_8);
        assertThat(service)
                .contains("MAX_ALTERNATIVES = 2")
                .contains("existing >= MAX_ALTERNATIVES");
    }

    @Test
    void theCountIsAQuietLineThatFoldsTheAlternativesOpen() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        assertThat(detail)
                .contains("'대안 ' + ${altCount} + '개'")
                .contains("th:if=\"${altCount > 0}\"")
                .contains("data-travel-plan-alt-toggle")
                .contains("aria-expanded=\"false\"")
                // 상세는 펼쳤을 때만 보인다
                .contains("class=\"travel-plan-alt-list\" hidden");

        // 옅은 글자 한 줄이지 버튼 박스가 아니다
        String toggle = between(css, ".travel-plan-alt-toggle {", "}");
        assertThat(toggle)
                .contains("border: 0")
                .contains("background: none")
                .doesNotContain("border-radius");
    }

    @Test
    void alternativesSitUnderTheItemAsIndentedNotesRatherThanCards() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        // A 의 본문 칸 안에 들어가 A 와 같은 높이의 카드가 되지 않는다
        int body = detail.indexOf("class=\"travel-plan-line-body\"");
        int block = detail.indexOf("class=\"travel-plan-alt-block\"");
        int reorder = detail.indexOf("data-travel-plan-reorder-forms");
        assertThat(body).isGreaterThan(0);
        assertThat(block).isGreaterThan(body);
        assertThat(block).isLessThan(reorder);

        // 들여쓰기와 얇은 세로선으로만 위계를 만든다
        String list = between(css, ".travel-plan-alt-list {", "}");
        assertThat(list)
                .contains("padding-left: 12px")
                .contains("border-left: 1px solid")
                .doesNotContain("border-radius")
                .doesNotContain("box-shadow");

        // 글자도 A 보다 한 단계 작다
        assertThat(between(css, ".travel-plan-line-content {", "}")).contains("font-size: 15px");
        assertThat(between(css, ".travel-plan-alt-content {", "}"))
                .contains("font-size: 14px")
                // 자유 텍스트의 줄바꿈은 그대로 보여 준다
                .contains("white-space: pre-line");
        assertThat(between(css, ".travel-plan-alt-condition {", "}")).contains("font-size: 12px");
    }

    @Test
    void eachAlternativeShowsItsSlotLetterAndOptionalCondition() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("th:each=\"alt : ${alternatives}\"")
                // 1 = B, 2 = C
                .contains("${alt.alternativeOrder == 1 ? 'B' : 'C'}")
                .contains("class=\"travel-plan-alt-mark\"")
                // 조건은 있을 때만 한 줄 더 나온다
                .contains("th:unless=\"${#strings.isEmpty(alt.conditionLabel)}\"")
                .contains("th:text=\"${alt.conditionLabel}\"")
                .contains("th:text=\"${alt.content}\"");
    }

    @Test
    void anAlternativeIsEditedInPlaceAndCarriesItsOwnVersion() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("data-travel-plan-alt")
                .contains("data-travel-plan-alt-view")
                .contains("data-travel-plan-alt-form")
                .contains("data-version=${alt.version}")
                .contains("<input type=\"hidden\" name=\"version\" th:value=\"${alt.version}\">")
                .contains("/alternatives/${alt.id}/update|}")
                // 조건과 내용 두 칸이라 작은 저장/취소를 함께 둔다
                .contains(">저장</button>")
                .contains(">취소</button>")
                .contains("maxlength=\"100\"");
        // 기본 상태에서는 편집기가 닫혀 있고, 별도 페이지나 modal 로 가지 않는다
        assertThat(detail).contains("class=\"travel-plan-alt-editor\" method=\"post\" hidden");
        // 대안 편집기는 전부 DAY fragment 안에 있다. 그 안에는 별도 창이 없다
        assertThat(resource("/templates/travelplan/fragments/schedule-day.html"))
                .doesNotContain("modal").doesNotContain("dialog");
    }

    @Test
    void theViewAndTheEditorShareOneFullWidthContentColumn() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        // 저장된 B/C 와 새 대안 모두 같은 본문 칸(.travel-plan-alt-body) 안에서 열린다.
        // 폼이 flex 자식으로 남으면 입력칸의 기본 폭(cols=20)이 칸의 폭이 되어 일찍 줄바꿈된다
        assertThat(countOf(detail, "class=\"travel-plan-alt-body\"")).isEqualTo(2);
        for (String editor : new String[]{
                "data-travel-plan-alt-form", "data-travel-plan-alt-new-form"}) {
            assertThat(detail.indexOf(editor))
                    .as("%s sits inside a content column", editor)
                    .isGreaterThan(detail.indexOf("class=\"travel-plan-alt-body\""));
        }

        // 라벨 옆 남은 폭을 전부 쓴다
        assertThat(between(css, ".travel-plan-alt-body {", "}"))
                .contains("flex: 1")
                .contains("min-width: 0");

        // 보기와 편집기가 같은 폭 규칙을 쓴다
        for (String selector : new String[]{
                ".travel-plan-alt-view {", ".travel-plan-alt-editor {"}) {
            assertThat(between(css, selector, "}")).as("%s", selector)
                    .contains("box-sizing: border-box")
                    .contains("width: 100%")
                    .contains("min-width: 0")
                    // 글자 수 기준으로 좁히는 고정 max-width 는 두지 않는다
                    .contains("max-width: 100%");
        }
    }

    @Test
    void theTextareaFillsTheColumnAndOnlyGrowsDownwards() throws IOException {
        String css = cssFile();
        String script = resource("/static/js/travel-plan-scheduler.js");

        assertThat(between(css, ".travel-plan-alt-editor input[type=\"text\"],", "}"))
                .contains("box-sizing: border-box")
                .contains("width: 100%")
                .contains("max-width: 100%")
                .contains("min-width: 0");
        // 묶음 규칙 뒤에 오는 textarea 전용 규칙을 본다
        assertThat(betweenLast(css, ".travel-plan-alt-editor textarea {", "}"))
                // 세로로만 늘어나고 가로 스크롤은 만들지 않는다
                .contains("resize: none")
                .contains("overflow: hidden")
                .contains("overflow-wrap: break-word")
                // 보기 상태와 같은 글자 크기여야 폭이 같아 보인다
                .contains("font-size: 14px");
        assertThat(between(css, ".travel-plan-alt-content {", "}")).contains("font-size: 14px");

        // auto-resize 는 높이만 건드린다
        String resize = between(script, "function autoResize", "}");
        assertThat(resize)
                .contains("textarea.style.height = \"auto\"")
                .contains("textarea.scrollHeight")
                .doesNotContain("width");
        // 편집을 열 때와 입력할 때 모두 높이를 맞춘다.
        // 입력 처리는 A 일정과 같은 공통 helper 한 곳에 있다
        assertThat(between(script, "function bindEditableField", "field.addEventListener(\"keydown\""))
                .contains("if (field.tagName === \"TEXTAREA\") autoResize(field);");
        assertThat(script).contains("form.hidden = false");
    }

    @Test
    void theNewAlternativeSlotOpensInsideThePlannerToo() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        assertThat(detail)
                .contains("data-travel-plan-alt-new")
                .contains("data-travel-plan-alt-new-form")
                .contains("name=\"conditionLabel\"")
                .contains("placeholder=\"조건 (선택)\"")
                .contains("placeholder=\"대안 일정 입력...\"")
                // 다음 자리는 남은 개수로 정해진다
                .contains("${altCount == 0 ? 'B' : 'C'}")
                .contains("/items/${item.id}/alternatives|}");
        // 열기 전에는 자리를 차지하지 않는다
        assertThat(between(css, ".travel-plan-alt.is-new {", "}")).contains("display: none");
    }

    @Test
    void eachAlternativeCanBeDeletedOnItsOwn() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        assertThat(detail)
                .contains("/alternatives/${alt.id}/delete|}")
                .contains(">대안 삭제</button>")
                .contains("confirm('이 대안을 삭제할까요?')");
        // ⋯ 처럼 hover 전에는 조용히 있는다
        assertThat(between(css, ".travel-plan-alt .travel-plan-alt-remove {", "}"))
                .contains("color: transparent");
        assertThat(css).contains(".travel-plan-alt:hover .travel-plan-alt-remove");
    }

    @Test
    void alternativesNeverOfferMoveActionsOfTheirOwn() throws IOException {
        String detail = detailHtml();

        // 이동은 A 에만 있다. B/C 는 parent 를 따라다닌다
        String block = between(detail, "class=\"travel-plan-alt-block\"", "data-travel-plan-reorder-forms");
        assertThat(block)
                .doesNotContain("move-up")
                .doesNotContain("move-down")
                .doesNotContain("targetDayId")
                .doesNotContain("위로 이동")
                .doesNotContain("아래로 이동");
    }

    @Test
    void theEditorDeleteIncludesAlternativesWhenTheyExist() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("data-item-editor-delete formnovalidate")
                .contains(">삭제</button>")
                .contains("th:formaction=\"${altCount > 0}")
                .contains("/items/${item.id}/delete|}")
                .contains("/items/${item.id}/delete-group|}")
                .contains("이 일정과 대안을 함께 삭제할까요?")
                .contains("이 일정을 삭제할까요?")
                .doesNotContain(">일정만 삭제</button>")
                .doesNotContain(">전체 일정 삭제</button>");
    }

    @Test
    void theScriptKeepsOneEditorAcrossItemsAndAlternatives() throws IOException {
        String script = resource("/static/js/travel-plan-scheduler.js");

        assertThat(script)
                .contains("let activeAlt = null")
                // A 쪽을 열면 대안 편집기가 닫히고, 그 반대도 같다
                .contains("closeAlt()")
                .contains("closeActive()")
                .contains("if (activeAlt === node) return")
                // Enter 저장 / Shift+Enter 줄바꿈 / Esc 취소
                .contains("event.key === \"Enter\" && !event.shiftKey")
                .contains("event.key === \"Escape\"")
                .contains("data-travel-plan-alt-cancel")
                .contains("requestSubmit()");
        // 연결은 실시간 쪽이 들고 있다. 편집 스크립트는 소켓을 직접 열지 않는다
        assertThat(script)
                .doesNotContain("WebSocket")
                .doesNotContain("SockJS")
                .doesNotContain("StompJs")
                .doesNotContain("setInterval");
    }

    @Test
    void theAlternativeEndpointsAreCsrfProtected() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 대안 추가/수정/삭제 POST 는 기본 CSRF 정책이 보호한다 (주소 목록을 두지 않는다)
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
        // 인가 정책은 그대로 anyRequest().authenticated() 를 쓴다
        assertThat(securityConfig).doesNotContain("/travel-plans/**");
    }

    @Test
    void thisStageStillHasNoTagOrLiveEditingUi() throws IOException {
        String detail = detailHtml();

        for (String notYet : new String[]{"태그", "최종 확정"}) {
            assertThat(detail).as("아직 없는 기능: %s", notYet).doesNotContain(notYet);
        }
        // 투표는 만들기까지 들어왔다. 참여·결과는 아직이다
        assertThat(detail)
                .contains("투표 만들기")
                .doesNotContain("투표하기")
                .doesNotContain("투표 결과");
    }

    /**
     * 플래너가 실제로 그려 내는 markup 전부.
     * DAY 한 구역은 fragment 로 빠져 있고 처음 그릴 때와 실시간 갱신이 같은 파일을 쓴다.
     */
    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html")
                + resource("/templates/travelplan/fragments/schedule-day.html");
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

    private int countOf(String source, String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    /** 같은 선택자가 묶음 규칙에도 들어 있을 때 마지막(전용) 규칙을 집는다. */
    private String betweenLast(String source, String start, String end) {
        int startIndex = source.lastIndexOf(start);
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        return between(source.substring(startIndex), start, end);
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
