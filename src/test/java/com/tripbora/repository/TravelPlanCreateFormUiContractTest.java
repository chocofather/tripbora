package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공동 여행계획 생성 폼 계약.
 * 검증은 서버가 최종 기준이고 HTML 제약은 보조다.
 */
class TravelPlanCreateFormUiContractTest {

    @Test
    void formPostsEveryFieldTheServiceNeeds() throws IOException {
        String create = createHtml();

        assertThat(create)
                .contains("th:object=\"${travelPlanCreateForm}\"")
                .contains("th:action=\"@{/travel-plans}\"")
                .contains("method=\"post\"")
                .contains("th:field=\"*{title}\"")
                .contains("th:field=\"*{startDate}\"")
                .contains("th:field=\"*{endDate}\"")
                .contains("th:field=\"*{displayName}\"")
                .contains("<button type=\"submit\"")
                .contains("공동 여행계획 만들기");
        // 이번 단계에 없는 입력은 만들지 않는다
        assertThat(create)
                .doesNotContain("representativeImage")
                .doesNotContain("enctype");
    }

    @Test
    void clientSideConstraintsMirrorTheServerRules() throws IOException {
        String create = createHtml();

        assertThat(create)
                .contains("maxlength=\"150\"")
                .contains("maxlength=\"50\"")
                .contains("type=\"date\"");
        // 네 입력 모두 required
        assertThat(countOf(create, "required")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void theCreateFormWearsTheSameColoursAsTheRestOfTheFeature() throws IOException {
        String css = resource("/static/css/travel-plan.css");
        // 생성 폼 규칙만 본다(그 뒤로는 목록·상세 구역이다)
        String form = between(css, "/*\n  공동 여행계획 생성 폼.", "/* ───── 함께 계획하기 목록");

        /*
          새 색을 따로 만들지 않는다.
          목록·상세가 쓰는 --tp-plan-* 를 그대로 가져다 쓴다.
        */
        assertThat(form)
                .contains("var(--tp-plan-line)")
                .contains("var(--tp-plan-ink)")
                .contains("var(--tp-plan-accent)");
        // 갈색·베이지가 남아 있지 않다
        assertThat(form)
                .doesNotContain("#6f6350")
                .doesNotContain("#fdfbf7")
                .doesNotContain("#e3dcd1")
                .doesNotContain("#ded5c8")
                .doesNotContain("#3f3426")
                .doesNotContain("#8b8378");

        // 카드는 흰 종이에 얇은 선. 두꺼운 테두리나 진한 그림자를 두지 않는다
        assertThat(between(css, ".travel-plan-form {", "}"))
                .contains("background: #fff")
                .contains("border: 1px solid var(--tp-plan-line)");
        // 눌러야 할 것 하나만 색을 채운다
        assertThat(between(css, ".travel-plan-form-submit {", "}"))
                .contains("background: var(--tp-plan-accent)")
                .contains("color: #fff");
        // 지금 쓰고 있는 칸에만 옅은 sage 가 돈다
        assertThat(css).contains(".travel-plan-form-field input:focus,");
        assertThat(between(css, ".travel-plan-form-field input:focus,", "}"))
                .contains("border-color: var(--tp-plan-accent)");
    }

    @Test
    void noInputCanPushTheFormWiderThanTheScreen() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        /*
          날짜 칸은 브라우저가 안쪽에 연·월·일 칸을 따로 그려 제 나름의 최소 폭을 갖는다.
          내려 두지 않으면 좁은 화면에서 그 폭만큼 부모를 밀어낸다.
        */
        assertThat(between(css, ".travel-plan-form-field input[type=\"text\"],", "}"))
                .contains("box-sizing: border-box")
                .contains("width: 100%")
                .contains("min-width: 0");
        // 두 날짜 칸도 자기 폭을 고집하지 않는다
        assertThat(between(css, ".travel-plan-period-item {", "}"))
                .contains("flex: 1 1 0")
                .contains("min-width: 0");
        // 좁아지면 위아래로 선다 (기존 breakpoint 를 그대로 쓴다)
        assertThat(between(css, "@media (max-width: 560px) {", "\n}"))
                .contains(".travel-plan-period-inputs")
                .contains("flex-direction: column");
    }

    @Test
    void oneFieldsErrorDoesNotShoveTheOtherDateOutOfLine() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        /*
          아래를 기준으로 맞추면 한쪽에만 오류 문구가 붙었을 때
          그 높이만큼 반대쪽 칸이 통째로 내려가 두 줄이 어긋난다.
        */
        assertThat(between(css, ".travel-plan-period-inputs {", "}"))
                .contains("align-items: flex-start")
                .doesNotContain("align-items: flex-end");
        // ~ 는 오류 문구가 붙어도 입력칸 가운데 그 자리에 남는다
        assertThat(between(css, ".travel-plan-period-separator {", "}"))
                .contains("align-self: flex-start");
    }

    @Test
    void theSubmitButtonStaysItsOwnSizeOnSmallScreens() throws IOException {
        String narrow = between(resource("/static/css/travel-plan.css"),
                "@media (max-width: 560px) {", "\n}");

        // 좁다고 화면을 가로지르는 큰 덩어리 버튼으로 바꾸지 않는다
        assertThat(narrow).doesNotContain("travel-plan-form-submit");
    }

    @Test
    void bothDatesOnlyTakeAFourDigitYear() throws IOException {
        String create = createHtml();

        // 시작일과 종료일 둘 다. 서버도 같은 범위를 검증한다
        assertThat(countOf(create, "min=\"1000-01-01\" max=\"9999-12-31\"")).isEqualTo(2);
        assertThat(Files.readString(
                Path.of("src/main/java/com/tripbora/service/travelplan/"
                        + "TravelPlanService.java"), StandardCharsets.UTF_8))
                .contains("MIN_PLAN_YEAR = 1000")
                .contains("MAX_PLAN_YEAR = 9999");
        // 고르는 방식은 그대로 둔다. 직접 만든 달력을 쓰지 않는다
        assertThat(create).doesNotContain("datepicker");
    }

    @Test
    void thePeriodLabelsAreNotSaidTwice() throws IOException {
        String period = between(createHtml(),
                "class=\"travel-plan-period-inputs\"", "travel-plan-form-hint");

        // 위의 '여행 기간' 아래에서는 짧게만 적는다
        assertThat(period)
                .contains(">시작일<")
                .contains(">종료일<")
                .doesNotContain("여행 시작일")
                .doesNotContain("여행 종료일");
        // 묶는 이름은 그대로 남는다
        assertThat(createHtml()).contains("여행 기간");
    }

    @Test
    void theNinetyDayLimitIsStillSpelledOut() throws IOException {
        assertThat(createHtml())
                .contains("시작일과 종료일을 포함해 최대 90일까지 만들 수 있어요.");
    }

    @Test
    void everyValidatedFieldCanShowItsOwnErrorNextToTheInput() throws IOException {
        String create = createHtml();

        for (String field : new String[]{"title", "startDate", "endDate", "displayName"}) {
            assertThat(create).as("error slot for %s", field)
                    .contains("#fields.hasErrors('" + field + "')")
                    .contains("th:errors=\"*{" + field + "}\"");
        }
        // 전역 오류 박스가 필드 오류를 대체하지 않는다
        assertThat(create).contains("#fields.hasGlobalErrors()");
    }

    @Test
    void formReusesTheSiteLayoutAndShowsTheSuccessMessage() throws IOException {
        String create = createHtml();

        assertThat(create)
                .contains("~{layout/main :: layout(~{::body}, ~{::headFragment})}")
                .contains("/css/travel-plan.css")
                .contains("${travelPlanMessage}")
                .contains("이 여행계획 방에서 다른 참여자에게 표시되는 이름입니다.");
    }

    @Test
    void planCreationPostIsCsrfProtectedLikeTheOtherMemberFeatures() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // /diaries POST 와 같은 방식으로 기본 CSRF 정책의 보호를 받는다
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
        // 공동여행용 별도 인가 규칙은 추가하지 않는다 (anyRequest().authenticated() 사용)
        assertThat(securityConfig).doesNotContain("/travel-plans/**");
    }

    @Test
    void listShowsTitlePeriodMemberCountAndTheOwnerBadge() throws IOException {
        String list = resource("/templates/travelplan/list.html");

        assertThat(list)
                .contains("~{layout/main :: layout(~{::body}, ~{::headFragment})}")
                .contains("함께 계획하기")
                .contains("th:each=\"plan : ${travelPlans}\"")
                .contains("th:text=\"${plan.title}\"")
                .contains("${#temporals.format(plan.startDate, 'yyyy.MM.dd')}")
                .contains("${#temporals.format(plan.endDate, 'yyyy.MM.dd')}")
                .contains("${plan.dayCount}")
                .contains("'참여 ' + ${plan.memberCount} + '/8명'")
                .contains("plan.role.name() == 'OWNER'")
                .contains("th:href=\"@{|/travel-plans/${plan.travelPlanId}|}\"")
                .contains("~{travelplan/fragments/create-inline :: createInline}")
                .doesNotContain("th:href=\"@{/travel-plans/new}\"");

        // 대표 이미지 데이터가 없다. 빈 자리를 만들어 두지 않는다
        assertThat(list).doesNotContain("representativeImageUrl");

        // 빈 상태
        assertThat(list)
                .contains("th:if=\"${#lists.isEmpty(travelPlans)}\"")
                .contains("아직 함께 계획 중인 여행이 없어요.")
                .contains("새로운 여행계획을 만들어 보세요.");
    }

    @Test
    void thePlannerShowsThePlanHeaderAndEveryDay() throws IOException {
        String detail = plannerHtml();

        assertThat(detail)
                .contains("~{layout/main :: layout(~{::body}, ~{::headFragment})}")
                .contains("th:text=\"${travelPlan.plan.title}\"")
                .contains("${#temporals.format(travelPlan.plan.startDate, 'yyyy.MM.dd')}")
                .contains("th:each=\"day : ${travelPlan.days}\"")
                .contains("class=\"tp-detail-day-tile\"")
                .contains("${#temporals.format(day.planDate, 'MM.dd(E)')}")
                .contains("${travelPlanMessage}")
                .doesNotContain("class=\"travel-plan-back-link\"");
    }

    @Test
    void thePlannerDoesNotSendTheUserToTheDayScreen() throws IOException {
        String detail = plannerHtml();

        // PC 메인 화면에서는 DAY 상세로 넘어가는 기본 동선을 두지 않는다
        assertThat(detail).doesNotContain("/days/${day.id}|}\"")
                .doesNotContain("travel-plan-day-link");
        // DAY 안에서 바로 편집한다
        assertThat(detail).contains("data-travel-plan-slot");
    }

    @Test
    void nothingIsOpenForTypingUntilASlotIsClicked() throws IOException {
        String detail = plannerHtml();

        // 슬롯 폼은 기본적으로 hidden 이고, 저장에 실패한 자리에서만 열려 온다
        assertThat(detail)
                .contains("th:hidden=\"${!dayOpen}\"")
                .contains("${openDayId != null and openDayId == day.id}");
        // 타입별 추가 버튼이 기존 슬롯을 열며 실제 저장/취소는 닫힌 폼 안에 둔다.
        assertThat(detail)
                .contains("data-add-item-type=\"SCHEDULE\"")
                .contains("data-item-editor-cancel")
                .doesNotContain("data-travel-plan-add-toggle");
        // 취소는 닫혀 있는 대안 편집기(기존 B/C 1 + 새 대안 1)와
        // 닫혀 있는 투표 만들기 창 안에만 있다
        // (확정 확인 창은 방장 전용 조각으로 옮겨 갔다)
        assertThat(countOf(detail, ">취소</button>")).isEqualTo(5);
        // 방장 전용 조각의 확인 창 둘(확정 / 방 삭제)에 하나씩 있다
        assertThat(countOf(ownerActionsHtml(), ">취소</button>")).isEqualTo(2);
        // 모든 textarea 는 닫힌 폼·패널 안에 있다
        // (추가 슬롯 1 + 일정 수정 1 + 대안 2 + 닫혀 있는 채팅 입력 1)
        assertThat(countOf(detail, "<textarea")).isEqualTo(5);
        assertThat(countOf(detail, "th:hidden=\"${!dayOpen}\"")).isEqualTo(1);
        assertThat(countOf(detail, "class=\"travel-plan-item-editor\" method=\"post\" hidden"))
                .isEqualTo(1);
        // 따로 뜨는 창은 투표 센터와 방장 전용 확인 창 둘이고, 모두 닫힌 채로 시작한다
        assertThat(countOf(detail, "role=\"dialog\"")).isEqualTo(1);
        assertThat(detail).contains("class=\"travel-plan-poll-modal\" hidden role=\"dialog\"");
        // 방장 전용 조각의 확인 창: 확정과 방 삭제
        assertThat(countOf(ownerActionsHtml(), "role=\"dialog\"")).isEqualTo(2);
        assertThat(ownerActionsHtml())
                .contains("class=\"travel-plan-finalize-modal\" hidden role=\"dialog\"")
                .contains("class=\"travel-plan-plan-delete-modal\" hidden role=\"dialog\"");
    }

    @Test
    void onlySavedItemsBecomeNumberedLinesAndEachDayHasOneAddSlot() throws IOException {
        String detail = plannerHtml();

        // 번호가 붙은 줄은 저장된 일정에서만 만들어진다
        assertThat(detail)
                .contains("th:each=\"item, status : ${dayItems}\"")
                .contains("class=\"travel-plan-line is-item\"")
                .contains("${#numbers.formatInteger(status.count, 2)}");

        // 빈 줄을 미리 만들던 반복은 사라졌다
        assertThat(detail)
                .doesNotContain("#numbers.sequence")
                .doesNotContain("th:each=\"offset");

        // 추가 슬롯은 DAY 당 정확히 하나이고 번호가 없다
        assertThat(countOf(detail, "class=\"travel-plan-line is-slot\"")).isEqualTo(1);
        assertThat(countOf(detail, "data-travel-plan-slot>")).isEqualTo(1);
        assertThat(detail).contains(
                "<span class=\"travel-plan-line-order\" aria-hidden=\"true\"></span>");
    }

    @Test
    void theAddSlotIsQuietAndSharpensOnHover() throws IOException {
        String detail = plannerHtml();
        String css = resource("/static/css/travel-plan.css");

        assertThat(detail)
                .contains("travel-plan-slot-hint tp-item-add-type")
                .contains("data-add-item-type=\"ACCOMMODATION\"")
                .contains("data-add-item-type=\"FLIGHT\"");
        assertThat(css)
                .contains(".travel-plan-line.is-slot:hover .travel-plan-slot-hint")
                // 슬롯 번호 자리는 비워 둔다
                .contains(".travel-plan-line.is-slot .travel-plan-line-order");
    }

    @Test
    void thePlannerSitsOnItsOwnPaperAboveATintedSurface() throws IOException {
        String detail = plannerHtml();
        String css = resource("/static/css/travel-plan.css");

        // is-planning 은 좁은 화면 정리를 편집 화면에만 거는 표시다. 바탕 구조는 같다
        assertThat(detail)
                .contains("class=\"travel-plan-page is-planning\"")
                .contains("class=\"travel-plan-paper\"");

        // 흰 책상 위에 종이 한 장이 놓인 구조다
        String page = between(css, ".travel-plan-page {", "}");
        String paper = between(css, ".travel-plan-paper {", "}");
        assertThat(page).contains("background: var(--tp-plan-page)");
        assertThat(paper)
                .contains("var(--tp-plan-paper)")
                .contains("border: 1px solid")
                .contains("box-shadow")
                .contains("max-width: 900px");
        // 둥근 카드처럼 보이지 않게 한다
        assertThat(paper).contains("border-radius: 4px");
    }

    @Test
    void thePageIsNoLongerOneYellowBlock() throws IOException {
        String root = between(resource("/static/css/travel-plan.css"), ":root {", "}");

        // 바깥은 그냥 흰 책상이다
        assertThat(between(root, "--tp-plan-page:", ";")).contains("#ffffff");
        // 종이도 중립 흰색으로 두고, 선택 상태에만 라벤더를 쓴다
        assertThat(between(root, "--tp-plan-paper:", ";")).contains("#ffffff");
        assertThat(resource("/static/css/diary.css")).contains("--diary-paper-color: #fdfdfa");
        // 선은 전부 중립이다
        assertThat(between(root, "--tp-plan-line:", ";")).contains("#ecebef");
    }

    @Test
    void thePaperFeelsLikePaperWithoutShowingAPattern() throws IOException {
        String paper = between(resource("/static/css/travel-plan.css"),
                ".travel-plan-paper {", "\n}");

        // 종이 느낌이 아주 사라지지는 않는다
        assertThat(paper)
                .contains("--tp-sheet-grain:")
                .contains("--tp-sheet-edge:")
                .contains("background: var(--tp-sheet-edge), var(--tp-sheet-grain),"
                        + " var(--tp-plan-paper)");

        /*
          다만 되풀이되는 무늬는 두지 않는다.
          몇 px 주기의 결은 화면 픽셀 격자와 어긋나 지글거리거나 모아레로 보인다.
          남은 층은 전부 종이 한 장 크기의 완만한 그라데이션이다.
        */
        assertThat(paper)
                .doesNotContain("repeating-linear-gradient")
                .doesNotContain("repeating-radial-gradient")
                .doesNotContain("background-size")
                .doesNotContain("background-repeat: repeat");
        assertThat(resource("/static/css/travel-plan.css"))
                .as("여행계획 화면 어디에도 촘촘한 반복 무늬를 두지 않는다")
                .doesNotContain("repeating-linear-gradient");
    }

    @Test
    void theInviteCardIsEvenCleanerThanTheSchedulerSheet() throws IOException {
        String css = resource("/static/css/travel-plan.css");
        String invite = between(css, ".travel-plan-invite-paper {", "}");

        // 같은 종이 언어(가장자리 깊이감 + 같은 선/그림자)를 쓰되 결은 얹지 않는다
        assertThat(invite).contains("background: var(--tp-sheet-edge), #fff");
        assertThat(invite).doesNotContain("--tp-sheet-grain");
        // 선과 그림자는 스케줄러 종이에서 그대로 물려받는다
        assertThat(between(css, ".travel-plan-paper {", "\n}"))
                .contains("border: 1px solid var(--tp-plan-line)")
                .contains("box-shadow");
    }

    @Test
    void theActionRowAndTheSheetShareOneGrid() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        // 바깥 선이 같은 자리에서 시작하고 끝난다
        assertThat(between(css, ".travel-plan-page-top {", "}")).contains("max-width: 900px");
        String paper = between(css, ".travel-plan-paper {", "\n}");
        assertThat(paper).contains("max-width: 900px");
        // 안쪽 여백과 테두리가 그 폭 안에 들어간다 (없으면 종이만 더 넓어진다)
        assertThat(paper).contains("box-sizing: border-box");
    }

    @Test
    void eachDayItemAndSlotCarriesAnIdentifierForLaterLiveEditing() throws IOException {
        String detail = plannerHtml();

        assertThat(detail)
                .contains("data-plan-id=${travelPlan.plan.id}")
                .contains("th:id=\"'day-' + ${day.id}\"")
                .contains("data-day-id=${day.id}")
                .contains("data-item-id=${item.id}")
                .contains("data-display-order=${item.displayOrder}")
                .contains("data-slot-index=${itemCount + 1}");
    }

    @Test
    void theSchedulerScriptKeepsOneEditorAndHandlesTheEditingKeys() throws IOException {
        String script = resource("/static/js/travel-plan-scheduler.js");

        // 동시에 열리는 입력칸은 하나뿐이다
        assertThat(script)
                .contains("let activeLine = null")
                .contains("closeActive()")
                .contains("if (activeLine === line) {");
        // Enter 저장 / Shift+Enter 줄바꿈 / Esc 취소 / focus-out 저장
        assertThat(script)
                .contains("event.key === \"Enter\" && !event.shiftKey")
                .contains("event.key === \"Escape\"")
                .contains("\"blur\"")
                // 공백만 있으면 저장하지 않는다
                .contains("const value = textarea.value.trim()")
                .contains("if (value === \"\")")
                .contains("form.requestSubmit()");
        // 연결은 실시간 쪽이 들고 있다. 편집 스크립트는 소켓을 직접 열지 않는다
        assertThat(script)
                .doesNotContain("WebSocket")
                .doesNotContain("SockJS")
                .doesNotContain("StompJs")
                .doesNotContain("setInterval");
    }

    @Test
    void savedItemsCanBeEditedInPlaceAndCarryTheirVersion() throws IOException {
        String detail = plannerHtml();

        assertThat(detail)
                // 줄 자체가 편집기가 된다 (별도 페이지도 모달도 아니다)
                .contains("data-travel-plan-item")
                .contains("travelplan/fragments/item-card :: view")
                .contains("data-travel-plan-item-form")
                .contains("data-version=${item.version}")
                .contains("<input type=\"hidden\" name=\"version\" th:value=\"${item.version}\">")
                .contains("/items/${item.id}/update|}");
        // 기본 상태에서는 편집기가 닫혀 있다
        assertThat(detail).contains("class=\"travel-plan-item-editor\" method=\"post\" hidden");
        // 일정 편집기는 전부 DAY fragment 안에 있다. 그 안에는 별도 창이 없다
        assertThat(resource("/templates/travelplan/fragments/schedule-day.html"))
                .doesNotContain("modal").doesNotContain("dialog");
    }

    @Test
    void theEditorReplacesTheTextInPlaceInsteadOfSittingBesideIt() throws IOException {
        String detail = resource("/templates/travelplan/fragments/schedule-day.html");
        String css = resource("/static/css/travel-plan.css");

        // 보기와 편집기가 같은 칸(line-body) 안에 함께 들어 있다
        int body = detail.indexOf("class=\"travel-plan-line-body\"");
        int view = detail.indexOf("travelplan/fragments/schedule-day :: detailRow");
        int editor = detail.indexOf("data-travel-plan-item-form");
        int reorder = detail.indexOf("data-travel-plan-reorder-forms");
        assertThat(body).isGreaterThan(0);
        assertThat(view).isGreaterThan(body);
        assertThat(editor).isGreaterThan(view);
        // 편집기는 본문 안에 있고 drag 저장용 비노출 form은 그 뒤에 있다.
        assertThat(editor).isLessThan(reorder);

        // 줄 안에서 자리를 차지하는 것은 래퍼 하나뿐이다
        assertThat(between(css, ".travel-plan-line-body {", "}")).contains("flex: 1");

        // DAY 상세 화면의 .travel-plan-item-form 과 이름이 겹치면
        // 그쪽 display:flex 가 [hidden] 을 덮어써 편집기가 항상 보인다 (회귀 방지)
        assertThat(detail).doesNotContain("class=\"travel-plan-item-form\"");
        assertThat(between(css, ".travel-plan-item-editor textarea {", "}"))
                .contains("border: 0")
                .contains("background: none")
                .contains("resize: none")
                .contains("font-size: 15px")
                .contains("line-height: 1.75");
    }

    @Test
    void deleteIsInsideTheClosedEditorInsteadOfAnItemMenu() throws IOException {
        String detail = plannerHtml();

        assertThat(detail)
                .doesNotContain("data-travel-plan-menu-button")
                .doesNotContain(">⋯</button>")
                .doesNotContain("data-travel-plan-menu-list")
                .contains("data-item-editor-delete formnovalidate")
                .contains("/items/${item.id}/delete|}")
                .contains("<span>삭제</span>")
                .contains("data-delete-confirm");

        // 태그 UI 는 아직 없다
        assertThat(detail).doesNotContain("태그");
    }

    @Test
    void dragKeepsHiddenMovementFormsWithoutAMovementMenu() throws IOException {
        String detail = plannerHtml();

        assertThat(detail)
                .contains("hidden data-travel-plan-reorder-forms")
                .contains("/items/${item.id}/move-up|}")
                .contains("/items/${item.id}/move-down|}")
                // 이동도 낙관적 잠금을 쓴다
                .contains("<input type=\"hidden\" name=\"version\" th:value=\"${item.version}\">")
                .doesNotContain(">위로 이동</button>")
                .doesNotContain(">아래로 이동</button>")
                .doesNotContain("name=\"targetDayId\"")
                .doesNotContain("data-travel-plan-alt-add");

        // 카드 전체가 아니라 전용 핸들만 드래그 시작점이다. 메뉴 이동도 유지한다.
        assertThat(detail)
                .contains("class=\"tp-item-drag-handle\" draggable=\"true\"")
                .contains("data-item-drag-handle")
                .contains("aria-label=|${item.title} 순서 이동|");
    }

    @Test
    void theMoveEndpointsAreCsrfProtected() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 순서 이동 POST 도 기본 CSRF 정책이 보호한다 (주소 목록을 두지 않는다)
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    @Test
    void thePlannerHasNoActionsFromLaterStages() throws IOException {
        String detail = plannerHtml();

        // 멤버 관리·채팅·투표 만들기까지 들어왔고, 그 다음 단계는 아직이다
        for (String notYet : new String[]{"방 설정", "최종 확정", "태그"}) {
            assertThat(detail).as("아직 없는 기능: %s", notYet).doesNotContain(notYet);
        }
        // 투표는 만드는 것까지다. 참여·결과·마감은 아직 없다
        assertThat(detail)
                .contains("투표 만들기")
                .doesNotContain("투표하기")
                .doesNotContain("투표 결과")
                .doesNotContain("투표 마감");
    }

    @Test
    void theScriptKeepsOneEditorAcrossAddAndEdit() throws IOException {
        String script = resource("/static/js/travel-plan-scheduler.js");

        // 추가 슬롯과 기존 일정 수정이 동시에 열리지 않는다
        assertThat(script)
                .contains("let activeLine = null")
                .contains("if (activeLine === line) {")
                .contains("[data-travel-plan-slot-form], [data-travel-plan-item-form]")
                // Esc 는 원래 내용을 되살린다
                .contains("content.textContent.trim()")
                // 바뀐 게 없으면 UPDATE 를 보내지 않는다
                .contains("value === originalContentOf(line)")
                // 편집 중 보기 텍스트는 숨기고, 취소하면 되돌린다
                .contains("content.hidden = true")
                .contains("content.hidden = false")
                // 줄 높이는 내용에 맞춰 늘어난다
                .contains("function autoResize")
                .contains("textarea.scrollHeight");
        assertThat(script)
                .doesNotContain("WebSocket")
                .doesNotContain("SockJS")
                .doesNotContain("StompJs")
                .doesNotContain("setInterval");
    }

    @Test
    void theItemEndpointsAreCsrfProtected() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 일정 수정/삭제 POST 도 기본 CSRF 정책이 보호한다
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    @Test
    void eachDayShowsItsOwnItemsInOrder() throws IOException {
        String detail = plannerHtml();

        // DAY 별 목록을 그 DAY 의 id 로만 꺼내 서로 섞이지 않는다
        assertThat(detail)
                // DAY 별 목록은 fragment 인자로 그 DAY 것만 넘어간다
                .contains("${travelPlan.itemsByDayId.get(day.id)}")
                .contains("th:each=\"item, status : ${dayItems}\"")
                .contains("th:text=\"${item.content}\"")
                .contains("${#numbers.formatInteger(status.count, 2)}");
    }

    @Test
    void itemLinesKeepTheirLineBreaks() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        assertThat(between(css, ".travel-plan-line-content {", "}"))
                .contains("white-space: pre-line");
    }


    @Test
    void thePlanPageReadsEveryDaysItemsInOneQuery() throws IOException {
        String mapper = resource("/mapper/TravelPlanItemMapper.xml");
        String select = between(mapper, "<select id=\"findByPlanId\"", "</select>");

        // DAY 수만큼 조회가 나가지 않도록 방 단위로 한 번에 읽는다
        assertThat(select)
                .contains("FROM travel_plan_items i")
                .contains("JOIN travel_plan_days d ON d.id = i.travel_plan_day_id")
                .contains("WHERE d.travel_plan_id = #{travelPlanId}")
                .contains("ORDER BY d.day_number ASC, i.display_order ASC, i.id ASC")
                .doesNotContain("${");
    }

    @Test
    void theDayPageListsItemsAndOffersASingleFreeTextForm() throws IOException {
        String day = resource("/templates/travelplan/day-detail.html");

        assertThat(day)
                .contains("~{layout/main :: layout(~{::body}, ~{::headFragment})}")
                .contains("th:object=\"${travelPlanDay}\"")
                // DAY 제목 / 날짜 / 방으로 돌아가기
                .contains("'DAY ' + *{day.dayNumber}")
                .contains("${#temporals.format(travelPlanDay.day.planDate, 'M월 d일')}")
                .contains("th:href=\"@{|/travel-plans/${travelPlanDay.plan.id}|}\"")
                // 기존 일정 반복 + 순번
                .contains("th:each=\"item, status : *{items}\"")
                .contains("th:text=\"${item.content}\"")
                .contains("${#numbers.formatInteger(status.count, 2)}")
                // 일정이 없을 때
                .contains("${#lists.isEmpty(travelPlanDay.items)}")
                .contains("아직 등록된 일정이 없습니다.")
                // 자유 텍스트 추가 폼
                .contains("th:object=\"${travelPlanItemCreateForm}\"")
                .contains("<textarea")
                .contains("th:field=\"*{content}\"")
                // 저장 후 새 GET 에서 브라우저가 직전 입력을 되살리지 않게 한다
                .contains("autocomplete=\"off\"")
                // th:field 가 id 를 필드명으로 바꾸므로 label 도 같은 값을 가리켜야 한다
                .contains("<label class=\"travel-plan-item-form-label\" for=\"content\">")
                .contains("/days/${travelPlanDay.day.id}/items|}")
                .contains("#fields.hasErrors('content')")
                .contains(">일정 추가</button>");
    }

    @Test
    void multilineItemContentKeepsItsLineBreaks() throws IOException {
        String css = resource("/static/css/travel-plan.css");

        assertThat(between(css, ".travel-plan-item-content {", "}"))
                .contains("white-space: pre-line");
    }

    @Test
    void theDayPageHasNoActionsFromLaterStages() throws IOException {
        String day = resource("/templates/travelplan/day-detail.html");

        for (String notYet : new String[]{
                "수정", "삭제", "순서", "Plan B", "Plan C", "투표", "채팅", "초대", "최종 확정", "태그"}) {
            assertThat(day).as("아직 없는 기능: %s", notYet).doesNotContain(notYet);
        }
        // 이번 단계의 폼은 일정 추가 하나뿐이다
        assertThat(countOf(day, "<form")).isEqualTo(1);
        assertThat(countOf(day, "<button")).isEqualTo(1);
    }

    @Test
    void theItemPostIsCsrfProtected() throws IOException {
        String securityConfig = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        // 일정 추가 POST 도 기본 CSRF 정책이 보호한다
        assertThat(securityConfig)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    @Test
    void headerLinksToTheTravelPlanListNextToTheDiaryMenu() throws IOException {
        String header = resource("/templates/fragments/header.html");

        assertThat(header).contains(
                "href=\"/travel-plans\" th:text=\"#{nav.record.planTogether}\"");
        // 여행기록 메뉴 그룹 안, 나의 여행일기와 랜덤 여행 사이에 둔다
        assertThat(header.indexOf("함께 계획하기"))
                .isGreaterThan(header.indexOf("나의 여행일기"))
                .isLessThan(header.indexOf("랜덤 여행"));
        // 기존 항목은 그대로 둔다.
        // 나의 여행일기는 회원 /diaries, 비회원 체험 화면으로 갈리지만 회원 경로는 그대로다.
        assertThat(header)
                .contains("${isLoggedIn} ? @{/diaries} : @{/diaries/demo}")
                .contains("th:text=\"#{nav.record.diary}\"")
                .contains("href=\"/random-travel\" th:text=\"#{nav.record.random}\"");
    }

    private String createHtml() throws IOException {
        return resource("/templates/travelplan/create.html");
    }

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }

    private int countOf(String source, String token) {
        int count = 0;
        int index = source.indexOf(token);
        while (index >= 0) {
            count++;
            index = source.indexOf(token, index + token.length());
        }
        return count;
    }

    /**
     * 플래너가 실제로 그려 내는 markup 전부.
     * DAY 한 구역은 fragment 로 빠져 있고 처음 그릴 때와 실시간 갱신이 같은 파일을 쓴다.
     */
    private String plannerHtml() throws IOException {
        return resource("/templates/travelplan/detail.html")
                + resource("/templates/travelplan/fragments/schedule-day.html");
    }

    /**
     * 방장에게만 있는 상단 액션(확정 / 초대 / 확정 확인 창).
     * 방장이 바뀌면 통째로 갈리므로 상세 화면이 아니라 이 조각에 있다.
     */
    private String ownerActionsHtml() throws IOException {
        return resource("/templates/travelplan/fragments/owner-actions.html");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
