package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅창 안에서 투표 만들기.
 *
 * <p>연결은 하나뿐이고, 사용자가 쓴 글은 글자로만 그리며,
 * 아직 만들지 않은 투표 참여는 흉내조차 내지 않는다.
 */
class TravelPlanPollUiContractTest {

    // ── 진입점 ──────────────────────────────────────────────

    @Test
    void thereIsOnlyOneWayIntoTheVoteCentre() throws IOException {
        String detail = detailHtml();
        String composer = between(detail, "class=\"travel-plan-chat-form\"", "</form>");

        /*
          들어가는 길은 채팅 머리글의 [투표 N] 하나뿐이다.
          도구가 하나뿐인 [+] 는 같은 곳으로 가는 길을 하나 더 만들고
          누르는 단계만 늘려서 두지 않는다.
        */
        assertThat(detail).contains("data-travel-plan-poll-entry");
        assertThat(composer)
                .doesNotContain("data-travel-plan-chat-tool")
                .doesNotContain("data-travel-plan-poll-open")
                .doesNotContain("투표 만들기");
        // 입력줄에는 이모지 / 입력칸 / 전송만 남는다
        assertThat(composer)
                .contains("data-travel-plan-chat-emoji-toggle")
                .contains("data-travel-plan-chat-input")
                .contains("data-travel-plan-chat-send");
        assertThat(cssFile())
                .doesNotContain(".travel-plan-chat-tools")
                .doesNotContain(".travel-plan-chat-tool-menu")
                .doesNotContain(".travel-plan-chat-tool-action");
        // 이모지 버튼이 쓰는 크기는 그대로 남는다
        assertThat(cssFile()).contains(".travel-plan-chat-tool {");
    }

    @Test
    void theEntryPointIsOpenToEveryActiveMemberNotJustTheOwner() throws IOException {
        String detail = detailHtml();

        // 방장 전용 조건이 붙지 않는다. 참여자면 누구나 만든다
        assertThat(between(detail, "class=\"travel-plan-poll-entry\"",
                "data-travel-plan-poll-entry"))
                .doesNotContain("OWNER");
        assertThat(between(detail, "class=\"travel-plan-poll-modal\"",
                "data-travel-plan-poll-modal"))
                .contains("travelPlan.currentMember != null")
                .doesNotContain("OWNER");
    }

    // ── 모달 ────────────────────────────────────────────────

    @Test
    void theModalHasEverythingAPollNeeds() throws IOException {
        String detail = detailHtml();

        for (String hook : new String[]{
                "data-travel-plan-poll-modal", "data-travel-plan-poll-form",
                "data-travel-plan-poll-question", "data-travel-plan-poll-options",
                "data-travel-plan-poll-add", "data-travel-plan-poll-selection",
                "data-travel-plan-poll-back", "data-travel-plan-poll-submit",
                "data-travel-plan-poll-error"}) {
            assertThat(detail).as("modal has %s", hook).contains(hook);
        }
        // 열기 전까지 떠 있지 않다
        assertThat(between(detail, "class=\"travel-plan-poll-modal\"", ">")).contains("hidden");
        // 질문 길이는 DB 컬럼(varchar 200)에 맞춘다
        assertThat(between(detail, "class=\"travel-plan-poll-question\"",
                "data-travel-plan-poll-question"))
                .contains("maxlength=\"200\"");
    }

    @Test
    void hidingTheModalActuallyHidesIt() throws IOException {
        String css = cssFile();

        // display 를 정해 두면 브라우저 기본 [hidden] 규칙을 덮어써 계속 보인다
        assertThat(between(css, ".travel-plan-poll-modal {", "}")).contains("display: flex");
        assertThat(css).contains(".travel-plan-poll-modal[hidden] {\n    display: none;\n}");
    }

    @Test
    void theModalStaysInsideNarrowScreens() throws IOException {
        String panel = between(cssFile(), ".travel-plan-poll-modal-panel {", "}");

        assertThat(panel)
                .contains("width: min(420px, 100%)")
                .contains("max-height: min(600px, calc(100vh - 40px))")
                .contains("box-sizing: border-box");
        // 선택지 입력칸도 남은 폭 안에서만 커진다
        assertThat(between(cssFile(), ".travel-plan-poll-question,", "}"))
                .contains("box-sizing: border-box")
                .contains("max-width: 100%")
                .contains("min-width: 0");
    }

    @Test
    void theModalOpensWithTwoEmptyOptionRows() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "function resetForm()", "\n    }"))
                .contains("optionList.replaceChildren(optionRow(\"\"), optionRow(\"\"))");
        assertThat(poll).contains("const MIN_OPTIONS = 2");
    }

    @Test
    void optionRowsAreAddedAndRemovedWithinTheLimits() throws IOException {
        String poll = pollJs();

        assertThat(poll).contains("const MAX_OPTIONS = 10");
        // 두 개까지는 남겨 둔다
        assertThat(between(poll, "remove.addEventListener(\"click\"", "});"))
                .contains("if (rows().length <= MIN_OPTIONS) return");
        // 상한에 닿으면 더 만들 수 없다
        assertThat(between(poll, "addButton?.addEventListener(\"click\"", "});"))
                .contains("if (rows().length >= MAX_OPTIONS) return");
        assertThat(between(poll, "function renderOptionControls()", "\n    }"))
                .contains("addButton.hidden = current.length >= MAX_OPTIONS");
    }

    @Test
    void theRowPositionIsNeverTreatedAsADatabaseId() throws IOException {
        String poll = pollJs();

        // 화면의 몇 번째 줄인지는 저장할 때의 순서로만 쓴다
        assertThat(between(poll, "function readForm()", "\n    }"))
                .contains("options: rows().map(")
                .doesNotContain("id:");
        assertThat(poll).doesNotContain("data-option-id");
    }

    @Test
    void closingTheModalThrowsAwayWhatWasTyped() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "function closeModal()", "\n    }"))
                .contains("modal.hidden = true")
                .contains("resetForm()");
        // ESC 우선순위는 채팅에서 조정하고 같은 닫기 동작만 호출한다.
        assertThat(poll).contains("document.addEventListener(\"travelplan:poll-close\", () => closeModal())");
        // 바깥의 어두운 곳을 눌러도 닫힌다
        assertThat(between(poll, "modal.addEventListener(\"click\"", "});"))
                .contains("if (event.target === modal) closeModal()");
    }

    // ── 저장 ────────────────────────────────────────────────

    @Test
    void theModalSavesThroughHttpNotTheWebSocket() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "async function submit()", "\n    }"))
                .contains("fetch(`/travel-plans/${planId}/polls`")
                .contains("method: \"POST\"");
        // 투표 만들기를 WebSocket 으로 보내지 않는다
        assertThat(poll)
                .doesNotContain("new StompJs.Client")
                .doesNotContain("new WebSocket")
                .doesNotContain("client.publish");
    }

    @Test
    void theSaveCarriesTheCsrfTokenAndTheRouteRequiresIt() throws IOException {
        String poll = pollJs();
        String security = Files.readString(
                Path.of("src/main/java/com/tripbora/config/SecurityConfig.java"),
                StandardCharsets.UTF_8);

        assertThat(between(poll, "function csrfHeaders()", "\n    }"))
                .contains("meta[name=\\\"_csrf\\\"]")
                .contains("meta[name=\\\"_csrf_header\\\"]");
        // 기본 CSRF 정책이 GET 외 모든 요청을 보호하므로 주소 목록이 없다
        assertThat(security)
                .doesNotContain("requireCsrfProtectionMatcher")
                .doesNotContain("ignoringRequestMatchers")
                .doesNotContain("csrf(AbstractHttpConfigurer::disable)");
    }

    @Test
    void nothingInTheBodyDecidesWhoTheCreatorIs() throws IOException {
        String poll = pollJs();

        // 보내는 것은 질문·선택 방식·선택지뿐이다
        assertThat(between(poll, "function readForm()", "\n    }"))
                .doesNotContain("memberId")
                .doesNotContain("creator")
                .doesNotContain("status");
        // 폼이 받을 수 있는 것은 만들 때 정하는 값뿐이다.
        // 작성자·방·진행 상태를 실을 자리가 없다
        assertThat(com.tripbora.dto.TravelPlanPollCreateForm.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .containsExactlyInAnyOrder(
                        "question", "selectionType", "options", "resultVisibility");
    }

    @Test
    void aFailedSaveKeepsTheModalAndWhatWasTyped() throws IOException {
        String submit = between(pollJs(), "async function submit()", "\n    }");

        assertThat(submit).contains("showError(payload?.message)");
        // 실패했을 때는 만들기 화면에 그대로 머문다. 목록으로 넘어가지 않는다
        assertThat(submit.indexOf("showError(payload?.message)"))
                .isLessThan(submit.indexOf("showListView()"));
        // 연타로 두 번 저장되지 않는다
        assertThat(submit)
                .contains("if (submitting) return")
                .contains("submitButton.disabled = true");
    }

    @Test
    void aSuccessfulSaveDoesNotReloadThePage() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "async function submit()", "\n    }"))
                .contains("onPollCreated()");
        assertThat(poll)
                .doesNotContain("location.reload")
                .doesNotContain("location.href =");
    }

    @Test
    void koreanBeingAssembledDoesNotSubmitTheModal() throws IOException {
        String poll = pollJs();

        // 조합 중의 Enter 는 글자를 확정하는 것이지 제출이 아니다
        assertThat(between(poll, "form?.addEventListener(\"keydown\"", "});"))
                .contains("window.travelPlanIme.isComposing(event, composing)")
                .contains("event.preventDefault()");
        assertThat(poll)
                .contains("compositionstart")
                .contains("compositionend")
                // 조합 알고리즘을 직접 만들지 않는다
                .doesNotContain("charCodeAt")
                .doesNotContain("0xAC00");
    }

    // ── 진행 중인 투표 ──────────────────────────────────────

    @Test
    void theChatNoLongerCarriesAFixedPollShelf() throws IOException {
        String detail = detailHtml();

        // 투표가 채팅 세로 공간을 차지하지 않는다. 채팅창은 다시 대화만 담는다
        assertThat(detail)
                .doesNotContain("data-travel-plan-poll-area")
                .doesNotContain("진행 중인 투표</");
        assertThat(cssFile()).doesNotContain(".travel-plan-poll-area");

        // 채팅 머리글 -> 대화 -> 입력창. 그 사이에 투표 자리는 없다
        assertThat(detail.indexOf("travel-plan-chat-header"))
                .isLessThan(detail.indexOf("data-travel-plan-chat-body"));
    }

    @Test
    void theHeaderKeepsASmallWayIntoThePollCentre() throws IOException {
        String detail = detailHtml();
        String header = between(detail, "class=\"travel-plan-chat-header-actions\"", "</header>");

        // 크고 강한 배지가 아니라 작은 보조 액션이다
        assertThat(header)
                .contains("data-travel-plan-poll-entry")
                .contains("data-travel-plan-poll-count");
        // 진행 중인 투표가 없으면 숫자는 보이지 않지만 진입점은 남는다
        assertThat(between(detail, "travel-plan-poll-entry-count\"", ">")).contains("hidden");
        assertThat(between(header, "data-travel-plan-poll-entry>", "</button>")).contains("투표");
    }

    @Test
    void manyPollsScrollInsideThePollCentreInsteadOfGrowingForever() throws IOException {
        String body = between(cssFile(), ".travel-plan-poll-body {", "}");

        assertThat(body)
                .contains("overflow-y: auto")
                .contains("overflow-x: hidden");
        // 창 자체도 화면 밖으로 자라지 않는다
        assertThat(between(cssFile(), ".travel-plan-poll-modal-panel {", "}"))
                .contains("max-height");
    }

    // ── 목록과 만들기가 한 창 안에 ──────────────────────────

    @Test
    void bothViewsLiveInTheOneModalWithoutStacking() throws IOException {
        String detail = detailHtml();
        String poll = pollJs();

        assertThat(detail)
                .contains("data-travel-plan-poll-list-view")
                .contains("data-travel-plan-poll-create-view");
        // 투표 쪽 창은 하나뿐이다. 창 위에 창을 겹치지 않는다
        assertThat(countOf(detail, "class=\"travel-plan-poll-modal\"")).isEqualTo(1);
        // 두 화면이 같은 자리에서 서로 바뀐다
        assertThat(between(poll, "function showCreateView()", "\n    }"))
                .contains("listView.hidden = true")
                .contains("createView.hidden = false");
        assertThat(between(poll, "function showListView()", "\n    }"))
                .contains("listView.hidden = false")
                .contains("createView.hidden = true");
    }

    @Test
    void theCentreAlwaysOpensOnTheList() throws IOException {
        String poll = pollJs();

        // 머리글의 [투표 N] 이 여는 곳은 언제나 목록이다
        assertThat(poll).contains("entry?.addEventListener(\"click\", () => openModal())");
        assertThat(between(poll, "function openModal()", "\n    }"))
                .contains("showListView()")
                .doesNotContain("showCreateView()");
        // 새 투표는 그 창 안의 [투표 만들기] 에서 이어 간다
        assertThat(poll).contains("data-travel-plan-poll-create-open")
                .contains("showCreateView()");
    }

    @Test
    void theListHasTabsForRunningAndFinishedPolls() throws IOException {
        String detail = detailHtml();
        String poll = pollJs();

        assertThat(detail)
                .contains("data-travel-plan-poll-tab=\"OPEN\"")
                .contains("data-travel-plan-poll-tab=\"CLOSED\"")
                .contains("진행 중")
                .contains("지난 투표");
        // 마감 기능이 아직 없어 비어 있는 것이 정상이다. 가짜 데이터를 만들지 않는다
        assertThat(poll).contains("CLOSED: \"지난 투표가 아직 없어요.\"");
        assertThat(poll).contains("/polls/${path}");
    }

    @Test
    void afterCreatingItGoesBackToTheListWithoutClosing() throws IOException {
        String submit = between(pollJs(), "async function submit()", "\n    }");

        // 방금 만든 것을 바로 볼 수 있게 목록으로 돌아간다
        assertThat(submit)
                .contains("activeTab = \"OPEN\"")
                .contains("showListView()")
                .contains("onPollCreated()");
        // 창 자체를 닫아 버리지 않는다
        assertThat(submit).doesNotContain("closeModal()");
    }

    @Test
    void theRunningListCardStaysCompact() throws IOException {
        String poll = pollJs();
        String card = between(poll, "function pollNode(poll)", "return item;");

        // 질문 · 작성자 · 참여 현황까지다
        assertThat(card)
                .contains("cardTitle.textContent = poll.title")
                .contains("author.textContent = poll.createdByDisplayName")
                .contains("${poll.votedMemberCount} / ${poll.activeMemberCount}명 투표");
        // 목록에서는 선택지를 펼치지 않는다. 그것은 상세의 몫이다
        assertThat(card)
                .doesNotContain("poll.options")
                .doesNotContain("option.content");
    }

    @Test
    void theFinishedListCardShowsOnlyTheResult() throws IOException {
        String card = between(pollJs(), "function pollNode(poll)", "return item;");

        assertThat(card)
                .contains("poll.status === \"CLOSED\"")
                .contains("label.textContent = \"결과\"")
                .contains("poll.winnerSummary || \"투표 결과 없음\"");
        // 끝난 투표에는 참여 인원을 쓰지 않는다
        assertThat(card.indexOf("poll.winnerSummary"))
                .isLessThan(card.indexOf("명 투표"));
    }

    @Test
    void theCardOpensTheDetailInTheSameModal() throws IOException {
        String poll = pollJs();
        String detail = detailHtml();

        assertThat(between(poll, "function pollNode(poll)", "return item;"))
                .contains("card.addEventListener(\"click\", () => openDetail(poll.id))");
        // 창을 하나 더 띄우지 않는다. 같은 창 안에서 화면만 바뀐다
        assertThat(detail).contains("data-travel-plan-poll-detail-view");
        assertThat(countOf(detail, "class=\"travel-plan-poll-modal\"")).isEqualTo(1);
        assertThat(between(poll, "function showDetailView()", "\n    }"))
                .contains("listView.hidden = true")
                .contains("detailView.hidden = false");
    }

    // ── 투표하기 ────────────────────────────────────────────

    @Test
    void theDetailUsesRadiosOrCheckboxesToMatchThePoll() throws IOException {
        String choice = between(pollJs(), "function optionChoiceOf(poll, option)", "return row;");

        assertThat(choice)
                .contains("poll.selectionType === \"MULTIPLE\" ? \"checkbox\" : \"radio\"")
                // 이미 고른 것은 미리 체크되어 있다
                .contains("poll.selectedOptionIds || []")
                // 결과를 바로 볼 수 있는 투표라 지금까지의 표도 함께 보여 준다
                .contains("${option.voteCount}표");
    }

    @Test
    void aFinishedPollIsReadOnly() throws IOException {
        String poll = pollJs();
        String result = between(poll, "function resultRowOf(option)", "return row;");

        // 끝난 투표에는 고르는 칸도 [투표하기] 도 없다
        assertThat(result)
                .doesNotContain("input")
                .doesNotContain("radio")
                .doesNotContain("checkbox");
        assertThat(between(poll, "if (poll.status === \"CLOSED\") {", "} else {"))
                .doesNotContain("투표하기");
    }

    @Test
    void votingGoesThroughHttpAndKeepsTheModalOpen() throws IOException {
        String vote = between(pollJs(), "async function vote(pollId)", "\n    }");

        assertThat(vote)
                .contains("/polls/${pollId}/vote")
                .contains("method: \"POST\"")
                .contains("csrfHeaders()")
                // 저장한 뒤에도 상세에 그대로 머문다
                .contains("renderDetail(await response.json())")
                .doesNotContain("closeModal()");
        // 연타로 두 번 보내지 않는다
        assertThat(vote).contains("if (voting) return");
    }

    @Test
    void theScreenNeverGuessesTheNumbers() throws IOException {
        String poll = pollJs();

        /*
          저장 응답과 내 알림이 겹쳐 표가 두 번 오르면 안 된다.
          숫자는 언제나 서버가 준 값을 그대로 그린다.
        */
        assertThat(poll)
                .doesNotContain("voteCount + 1")
                .doesNotContain("voteCount++")
                .doesNotContain("votedMemberCount + 1");
        assertThat(between(poll, "if (payload.type !== \"POLL_VOTED\") return;", "});"))
                .contains("loadTab(\"OPEN\", true)")
                .contains("refreshDetail()");
    }

    @Test
    void aFailedVoteSaysWhyAndLeavesTheChoicesAlone() throws IOException {
        String vote = between(pollJs(), "async function vote(pollId)", "\n    }");

        assertThat(vote).contains("showVoteError(payload?.message)");
        assertThat(vote.indexOf("showVoteError(payload?.message)"))
                .isLessThan(vote.indexOf("renderDetail("));
    }

    @Test
    void nobodyIsToldWhoVotedForWhat() throws IOException {
        String poll = pollJs();

        // 보여 주는 것은 내 선택 · 선택지별 합계 · 참여 인원까지다
        assertThat(poll)
                .doesNotContain("voters")
                .doesNotContain("votedBy")
                .doesNotContain("voterNames");
    }

    // ── 마감 ────────────────────────────────────────────────

    @Test
    void theCreateFormNoLongerAsksHowThePollShouldEnd() throws IOException {
        String detail = detailHtml();
        String poll = pollJs();

        /*
          마감 방식은 고르는 것이 아니라 정해진 규칙이다.
          고르는 것이 아니므로 만들기 폼에서 자리를 차지하지 않는다
          (규칙 자체는 그대로다 — 모두 투표하면 자동 마감, 그 전에도 직접 마감).
        */
        assertThat(detail)
                .doesNotContain("data-travel-plan-poll-close-type")
                .doesNotContain("data-travel-plan-poll-deadline")
                .doesNotContain("마감 시간 지정")
                .doesNotContain("참여자가 모두 투표하면 자동으로 마감돼요.")
                .doesNotContain("travel-plan-poll-note");
        assertThat(poll)
                .doesNotContain("renderDeadlineField")
                .doesNotContain("closeType")
                .doesNotContain("deadlineAt");
        assertThat(cssFile())
                .doesNotContain(".travel-plan-poll-deadline")
                .doesNotContain(".travel-plan-poll-note");
    }

    @Test
    void howResultsAreSharedIsStillAsked() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("data-travel-plan-poll-visibility")
                .contains("value=\"REALTIME\"")
                .contains("value=\"AFTER_CLOSE\"");
        // 기본은 실시간 공개다
        assertThat(between(detail, "value=\"REALTIME\"", ">")).contains("checked");
    }

    @Test
    void whatIsSentWhenCreatingIsOnlyWhatTheAuthorChose() throws IOException {
        String readForm = between(pollJs(), "function readForm()", "\n    }");

        assertThat(readForm)
                .contains("question")
                .contains("selectionType")
                .contains("options")
                .contains("resultVisibility");
        // 마감 방식이 오가지 않으니 앞뒤가 어긋날 자리도 없다
        assertThat(readForm)
                .doesNotContain("closeType")
                .doesNotContain("deadlineAt");
    }

    @Test
    void onlyTheCreatorSeesTheCloseAction() throws IOException {
        String poll = pollJs();

        // 보일지 말지는 서버가 정해 준다. 화면이 스스로 판단하지 않는다
        assertThat(between(poll, "function renderDetail(poll)", "detailBody.replaceChildren"))
                .contains("if (poll.closable)")
                .contains("closeButton.textContent = \"투표 마감\"");
        assertThat(between(poll, "async function closePoll(pollId)", "\n    }"))
                .contains("/polls/${pollId}/close")
                .contains("method: \"POST\"")
                .contains("csrfHeaders()");
    }

    @Test
    void aHiddenResultIsNotEvenSentUntilThePollEnds() throws IOException {
        String poll = pollJs();
        String dto = Files.readString(
                Path.of("src/main/java/com/tripbora/dto/"
                        + "TravelPlanPollOptionResultDto.java"),
                StandardCharsets.UTF_8);

        // 0 으로 내리면 "아무도 안 골랐다" 로 읽힌다. 아예 담지 않는다
        assertThat(dto).contains("Integer voteCount").contains("hidden(");
        assertThat(between(poll, "function optionChoiceOf(poll, option)", "return row;"))
                .contains("if (option.voteCount != null)");
        // 표는 가려도 참여 인원은 보여 준다
        assertThat(between(poll, "function detailHeadOf(poll)", "return head;"))
                .contains("명 참여")
                .contains("if (!poll.resultsVisible)");
    }

    @Test
    void everyoneSeesThePollMoveWhenItCloses() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "if (payload.type === \"POLL_CLOSED\") {", "return;"))
                .contains("refreshLists()")
                .contains("refreshDetail()");
    }

    @Test
    void bothCountsChangeWithoutOpeningTheOtherTab() throws IOException {
        String poll = pollJs();
        String refresh = between(poll, "function refreshLists()", "\n    }");

        /*
          마감되면 진행 중이 하나 줄고 지난 투표가 하나 는다.
          보고 있는 탭만 읽으면 반대쪽 숫자가 그 탭을 열어 볼 때까지 옛 값으로 남는다.
        */
        assertThat(refresh)
                .contains("loadTab(\"OPEN\", true)")
                .contains("loadTab(\"CLOSED\", true)");
        // 어느 탭을 보고 있는지에 따라 한쪽만 읽지 않는다
        assertThat(refresh)
                .doesNotContain("activeTab")
                .doesNotContain("loadedTabs.delete");
    }

    @Test
    void theCountsComeFromTheServerAndNeverFromCounting() throws IOException {
        String poll = pollJs();

        // 탭 숫자는 서버가 준 값을 그대로 넣은 것이다
        assertThat(between(poll, "function renderTabs()", "\n    }"))
                .contains("String(pollCounts[name])")
                .contains("String(pollCounts.OPEN)");
        assertThat(between(poll, "async function loadCounts()", "\n    }"))
                .contains("pollCounts.OPEN = payload.open || 0")
                .contains("pollCounts.CLOSED = payload.closed || 0");
        /*
          그래서 같은 POLL_CLOSED 를 두 번 받아도, 탭을 여러 번 오가도
          숫자가 두 번 오르지 않는다.
        */
        assertThat(poll)
                .doesNotContain("length + 1")
                .doesNotContain("Count + 1")
                .doesNotContain("Count - 1")
                .doesNotContain("Count++")
                .doesNotContain("Count--")
                .doesNotContain("pollCounts[name] +=")
                .doesNotContain("pollCounts.OPEN +=")
                .doesNotContain("pollCounts.CLOSED +=");
    }

    @Test
    void bothCountsAreRightFromTheMomentTheCentreOpens() throws IOException {
        String poll = pollJs();

        /*
          숫자를 목록에서만 얻으면 열어 보지 않은 탭이 0 으로 남는다.
          그래서 열 때 보고 있는 탭의 목록과 두 탭의 숫자를 함께 읽는다.
        */
        String open = between(poll, "function openModal()", "\n    }");
        assertThat(open)
                .contains("loadTab(activeTab, true)")
                .contains("loadCounts()");
        // 기본 탭은 그대로 진행 중이다
        assertThat(poll).contains("let activeTab = \"OPEN\"");
    }

    @Test
    void theNumbersDoNotWaitForATabToBeClicked() throws IOException {
        String poll = pollJs();

        // 숫자는 탭 클릭과 상관없는 자기 상태로 관리된다
        assertThat(poll).contains("const pollCounts = { OPEN: 0, CLOSED: 0 }");
        // 탭을 누를 때 하는 일은 목록을 읽는 것뿐이다
        assertThat(between(poll, "tab.addEventListener(\"click\"", "});"))
                .contains("loadTab(activeTab, false)")
                .doesNotContain("pollCounts");
    }

    @Test
    void countingDoesNotDragTheWholeFinishedListAlong() throws IOException {
        String poll = pollJs();
        String mapper = resource("/mapper/TravelPlanPollMapper.xml");

        // 숫자만 필요할 때는 숫자만 센다
        assertThat(between(poll, "async function loadCounts()", "\n    }"))
                .contains("/polls/counts")
                .doesNotContain("polls || []");
        assertThat(between(mapper, "<select id=\"countPollsByStatus\"", "</select>"))
                .contains("COUNT(*)")
                .contains("GROUP BY status");
    }

    @Test
    void closingAPollMovesBothNumbersAtOnce() throws IOException {
        String poll = pollJs();

        // 마감되면 진행 중이 하나 줄고 지난 투표가 하나 는다. 둘 다 서버에서 다시 읽는다
        assertThat(between(poll, "function refreshLists()", "\n    }"))
                .contains("loadCounts()");
        assertThat(between(poll, "function onPollCreated()", "\n    }"))
                .contains("loadCounts()");
    }

    @Test
    void editingAPollIsStillNotPartOfThis() throws IOException {
        // 수정·최종 일정 연동은 다음 단계다
        assertThat(pollJs()).doesNotContain("투표 수정");
        assertThat(detailHtml()).doesNotContain("투표 수정");
    }

    // ── 지우기 ──────────────────────────────────────────────

    @Test
    void onlyTheCreatorSeesTheDeleteAction() throws IOException {
        String poll = pollJs();

        // 보일지 말지는 서버가 정해 준다. 화면이 스스로 판단하지 않는다
        assertThat(between(poll, "function renderDetail(poll)", "detailBody.replaceChildren"))
                .contains("if (poll.deletable)");
        assertThat(between(poll, "function deleteActionOf(poll)", "return remove;"))
                .contains("remove.textContent = \"투표 삭제\"")
                .contains("deletePoll(poll.id)");
    }

    @Test
    void deletingIsAskedAboutFirstAndOnlyLivesInTheDetail() throws IOException {
        String poll = pollJs();

        // 되돌릴 수 없으므로 한 번 물어본다
        assertThat(between(poll, "async function deletePoll(pollId)", "\n    }"))
                .contains("window.confirm(")
                .contains("/polls/${pollId}/delete")
                .contains("method: \"POST\"")
                .contains("csrfHeaders()");
        // 목록 카드에는 지우기를 두지 않는다
        assertThat(between(poll, "function pollNode(poll)", "return item;"))
                .doesNotContain("delete")
                .doesNotContain("삭제");
    }

    @Test
    void aDeletedPollLeavesEveryScreenAtOnce() throws IOException {
        String poll = pollJs();

        assertThat(between(poll, "if (payload.type === \"POLL_DELETED\") {", "return;"))
                .contains("removePoll(payload.pollId)");
        /*
          같은 번호로 두 번 와도 이미 없는 것을 지우려 할 뿐이라 안전하다.
          숫자와 목록은 서버에서 다시 읽어 맞춘다.
        */
        assertThat(between(poll, "function removePoll(pollId)", "\n    }"))
                .contains("String(openedPollId) === String(pollId)")
                .contains("showListView()")
                .contains("refreshLists()");
    }

    @Test
    void pollTextIsOnlyEverPutOnScreenAsText() throws IOException {
        String poll = pollJs();

        // 질문과 선택지도 사용자가 쓴 글이다
        assertThat(poll)
                .doesNotContain("innerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("document.write");
    }

    // ── 실시간 ──────────────────────────────────────────────

    @Test
    void aNewPollAppearsWithoutOpeningTheChatAgain() throws IOException {
        String poll = pollJs();

        assertThat(poll).contains("POLL_CREATED");
        assertThat(between(poll, "realtime()?.subscribePolls(", "});"))
                .contains("onPollCreated()");
    }

    @Test
    void thePollChannelRidesOnTheOneExistingConnection() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime).contains("`/topic/travel-plans/${planId}/polls`");
        assertThat(countOf(realtime, "new StompJs.Client")).isEqualTo(1);
        // 투표는 받기만 한다. 보내는 목적지는 없다
        assertThat(realtime).doesNotContain("/app/travel-plans/${planId}/polls");
    }

    @Test
    void theSamePollIsNeverShownTwice() throws IOException {
        String poll = pollJs();

        /*
          저장 응답과 방 알림 둘 다 도착해도 카드는 하나다.
          알림에 실려 온 값을 목록에 끼우지 않고 서버에서 다시 읽기 때문이다.
        */
        assertThat(between(poll, "function onPollCreated()", "\n    }"))
                .contains("loadTab(\"OPEN\", true)");
        assertThat(poll).doesNotContain("pollsByTab.OPEN.unshift");
    }

    @Test
    void theChatNoticeIsAlsoShownOnlyOncePerPoll() throws IOException {
        String chat = resource("/static/js/travel-plan-chat.js");
        String created = between(chat, "function onPollCreated(poll)", "\n    }");

        // 알림 줄도 pollId 로 가른다
        assertThat(created).contains("noticeNodeOf(poll.id)");
        assertThat(chat).contains("node.setAttribute(\"data-poll-notice-id\", item.pollId)");
    }

    @Test
    void theNoticeSaysWhoMadeItAndWhatItAsks() throws IOException {
        String chat = resource("/static/js/travel-plan-chat.js");
        String notice = between(chat, "function pollNoticeNode(item)", "return node;");

        assertThat(notice)
                .contains("님이 새 투표를 만들었어요.")
                .contains("pollTitle.textContent = item.pollTitle")
                // 말풍선이 아니라 알림 줄이다
                .contains("travel-plan-chat-notice");
        // 사용자가 쓴 글이라 글자로만 넣는다
        assertThat(notice).doesNotContain("innerHTML");
        // 누르면 투표 센터가 열린다
        assertThat(notice).contains("travelplan:poll-center-open");
    }

    @Test
    void theNoticeComesBackFromTheServerNotJustFromTheLiveEvent() throws IOException {
        String chat = resource("/static/js/travel-plan-chat.js");

        /*
          실시간으로 받은 순간에만 보이고 다시 열면 사라지면 안 된다.
          기록도 같은 타임라인에서 오고 같은 줄을 그린다.
        */
        assertThat(chat)
                .contains("chat/timeline")
                .contains("item.type === \"POLL_CREATED\" ? pollNoticeNode(item)");
        assertThat(between(chat, "async function loadRecent()", "\n    }"))
                .contains("payload.items.map(itemNode)");
    }

    @Test
    void openPollsAreFetchedWhenTheChatIsOpenedNotOnPageLoad() throws IOException {
        String poll = pollJs();
        String detail = detailHtml();

        assertThat(poll)
                .contains("travelplan:chat-opened")
                .contains("/polls/${path}");
        // 상세 화면에 투표를 미리 싣지 않는다
        assertThat(detail).doesNotContain("th:each=\"poll");
        // 채팅창을 열 때 머리글 숫자를 한 번 맞춘다. 숫자만 있으면 되므로 목록은 읽지 않는다
        assertThat(poll).contains(
                "document.addEventListener(\"travelplan:chat-opened\", () => loadCounts());");
        // 탭 목록은 한 번 읽어 두고 다시 묻지 않는다
        assertThat(between(poll, "async function loadTab(name, force)", "\n    }"))
                .contains("if (loadedTabs.has(name) && !force)");
    }

    @Test
    void whatWasMissedWhileDisconnectedIsFetchedAgain() throws IOException {
        assertThat(between(pollJs(), "realtime()?.onReconnected(", "});"))
                .contains("loadedTabs.clear()")
                .contains("loadTab(activeTab, true)");
    }

    // ── 색 계층 ─────────────────────────────────────────────

    @Test
    void eachAreaHasItsOwnSurfaceInsteadOfOneBeigeForEverything() throws IOException {
        String css = cssFile();
        String tokens = between(css, ":root {", "}");

        // 색을 여기저기 박아 두지 않고 이름 하나로 모아 둔다
        for (String token : new String[]{
                "--tp-page:", "--tp-paper:", "--tp-chat-surface:", "--tp-chat-composer:",
                "--tp-poll-surface:", "--tp-poll-accent:"}) {
            assertThat(tokens).as("색 이름 %s", token).contains(token);
        }
        // 채팅과 투표가 같은 한 가지 바탕으로 뭉개지지 않는다
        assertThat(between(css, ".travel-plan-chat-panel {", "}"))
                .contains("var(--tp-chat-surface)");
        assertThat(between(css, ".travel-plan-poll-modal-panel {", "}"))
                .contains("var(--tp-poll-surface)");
        // 대화와 입력줄도 아주 조금 다르다
        assertThat(between(css, ".travel-plan-chat-form {", "}"))
                .contains("var(--tp-chat-composer)");
    }

    @Test
    void thePollAccentIsUsedOnlyWhereSomethingNeedsPointingOut() throws IOException {
        String css = cssFile();

        // 배지 / 탭 밑줄 / 선택지 번호 정도에만 쓴다
        assertThat(between(css, ".travel-plan-poll-entry-count {", "}"))
                .contains("var(--tp-poll-accent)");
        assertThat(between(css, ".travel-plan-poll-tab.is-active {", "}"))
                .contains("border-bottom-color: var(--tp-poll-accent)");
        assertThat(between(css, ".travel-plan-poll-choice-count {", "}"))
                .contains("var(--tp-poll-accent)");
        // 창 전체를 파랗게 칠하지 않는다. 카드 안은 흰 종이 그대로다
        assertThat(between(css, ".travel-plan-poll-card {", "}"))
                .contains("background: #fff");
    }

    @Test
    void theMainButtonUsesTheSameAccentAsTheRestOfThePlanner() throws IOException {
        String css = cssFile();
        String buttons = between(css, ".travel-plan-poll-cancel,\n.travel-plan-poll-submit {", "}");

        // 투표만 다른 색을 새로 만들지 않는다. 여행계획 화면의 accent 를 그대로 쓴다
        assertThat(css).contains(
                ".travel-plan-poll-submit {\n"
                        + "    border-color: var(--tp-plan-accent);\n"
                        + "    background: var(--tp-plan-accent);\n"
                        + "    color: #fff;\n"
                        + "}");
        // 투표 창에는 갈색 버튼을 남기지 않는다
        assertThat(between(css, "/* ───── 투표 센터 ───── */", "/* ───── 여행 계획 확정 ───── */"))
                .doesNotContain("#6f6350");
        // 물러나는 쪽은 흰 바탕에 옅은 선뿐이다
        assertThat(between(css, ".travel-plan-poll-cancel {", "}"))
                .contains("background: #fff")
                .contains("color: var(--tp-plan-ink-soft)");
        // 크기는 과하지 않게 둔다
        assertThat(buttons)
                .contains("padding: 7px 14px")
                .contains("border-radius: 8px")
                .contains("font-size: 13px");
    }

    @Test
    void theCreateFormReadsLikeAQuickFormNotASettingsPage() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        // 질문 칸은 그냥 "질문" 이다. 큰 문구를 따로 두지 않는다
        assertThat(detail).contains(">질문</label>").doesNotContain("어떤 걸 정해볼까요?");
        assertThat(css).doesNotContain(".travel-plan-poll-lead");

        // 고르는 것은 한 줄짜리 두 칸뿐이다. 큰 카드가 아니다
        assertThat(detail)
                .contains("travel-plan-poll-radio-row")
                .doesNotContain("travel-plan-poll-radio-body")
                .doesNotContain("travel-plan-poll-radio-hint");
        assertThat(between(css, ".travel-plan-poll-radio {", "}"))
                .contains("border: 0")
                .contains("min-height: 36px");
        // 선택 상태를 진한 테두리로 알리지 않는다
        assertThat(between(css, ".travel-plan-poll-radio:has(input:checked) {", "}"))
                .contains("background: var(--tp-plan-accent-soft)")
                .doesNotContain("border");
    }

    @Test
    void theTwoChoicesAreOneSegmentedControlNotBareRadios() throws IOException {
        String css = cssFile();
        String detail = detailHtml();

        // 두 칸이 한 덩어리로 붙는다
        assertThat(between(css, ".travel-plan-poll-radio-row {", "}"))
                .contains("display: flex")
                .contains("border: 1px solid var(--tp-plan-line)")
                .contains("border-radius: 8px")
                .contains("overflow: hidden");
        assertThat(css).contains(
                ".travel-plan-poll-radio + .travel-plan-poll-radio {\n"
                        + "    border-left: 1px solid var(--tp-plan-line);\n"
                        + "}");
        /*
          라디오 동그라미는 눈에서만 감춘다. 지우거나 display:none 으로 두면
          키보드로 옮겨 다닐 수 없고 폼이 값을 읽는 방법도 달라진다.
        */
        assertThat(between(css, ".travel-plan-poll-radio input {", "}"))
                .contains("position: absolute")
                .contains("clip-path: inset(50%)")
                .doesNotContain("display: none");
        assertThat(detail)
                .contains("type=\"radio\"")
                .contains("value=\"SINGLE\"")
                .contains("value=\"MULTIPLE\"")
                .contains("value=\"REALTIME\"")
                .contains("value=\"AFTER_CLOSE\"");
        // 키보드로 왔을 때 지금 어디에 있는지 보인다
        assertThat(css).contains(".travel-plan-poll-radio:has(input:focus-visible)");
        // 결과 공개 문구
        assertThat(detail).contains(">실시간 공개</span>").contains(">종료 후 공개</span>");
    }

    @Test
    void theModalIsOnlyAsTallAsWhatIsInIt() throws IOException {
        String css = cssFile();

        /*
          목록 칸이 늘어나면 투표가 하나도 없을 때도 큰 빈 상자가 남는다.
          내용만큼만 자라고, 화면이 모자랄 때만 그 안에서 넘긴다.
        */
        assertThat(between(css, ".travel-plan-poll-body {", "}"))
                .contains("flex: 0 1 auto")
                .contains("overflow-y: auto");
        assertThat(between(css, ".travel-plan-poll-modal-panel {", "}"))
                .contains("max-height: min(600px, calc(100vh - 40px))")
                .doesNotContain("height: 600px");
        // 빈 상태도 한 줄이면 된다
        assertThat(between(css, ".travel-plan-poll-empty {", "}")).contains("margin: 18px 0");
    }

    @Test
    void theModalChromeIsWhiteRatherThanTinted() throws IOException {
        String css = cssFile();

        // 머리글과 탭 줄에 색을 깔지 않는다
        assertThat(between(css, ".travel-plan-poll-modal-header {", "}"))
                .contains("background: #fff")
                .contains("border-bottom: 1px solid var(--tp-plan-line)");
        assertThat(between(css, ".travel-plan-poll-tabs {", "}")).contains("background: #fff");
        assertThat(between(css, ".travel-plan-poll-footer {", "}")).contains("background: #fff");
        // 탭은 상자가 아니라 글자다. 고른 것만 밑줄로 알린다
        assertThat(between(css, ".travel-plan-poll-tab {", "}"))
                .contains("border: none")
                .contains("background: none");
    }

    @Test
    void makingAPollIsOfferedAsAPlainAction() throws IOException {
        String detail = detailHtml();

        // 목록 아래의 진입점. 큰 갈색 덩어리 버튼을 두지 않는다
        assertThat(between(detail, "class=\"travel-plan-poll-footer\"", "</footer>"))
                .contains(">새 투표 만들기</button>")
                .doesNotContain("+ 새 투표 만들기");
        assertThat(detail).contains("data-travel-plan-poll-modal-title>여행 투표</h2>");
        assertThat(pollJs()).contains("title.textContent = \"여행 투표\"")
                .contains("title.textContent = \"새 투표 만들기\"");
        // 선택지 추가는 작은 글자 액션이다
        assertThat(between(cssFile(), ".travel-plan-poll-add {", "}"))
                .contains("border: none")
                .contains("background: none")
                .contains("color: var(--tp-plan-accent)");
    }

    @Test
    void thePollCentreSitsAboveTheChatAndCoversIt() throws IOException {
        String css = cssFile();

        // 채팅 패널(40)보다 위라 채팅의 작은 자식창처럼 보이지 않는다
        assertThat(between(css, ".travel-plan-chat-panel {", "}")).contains("z-index: 40");
        assertThat(between(css, ".travel-plan-poll-modal {", "}"))
                .contains("z-index: 60")
                .contains("position: fixed")
                .contains("inset: 0")
                // 뒤의 페이지와 채팅을 함께 덮어 그동안 눌리지 않게 한다
                .contains("background: rgba(47, 52, 56, 0.32)");
    }

    @Test
    void theSegmentedControlStaysOneRowOnTheNarrowestPhone() throws IOException {
        String css = cssFile();

        /*
          360px 화면에서도 한 칸이 143px 이고 가장 긴 "여러 개 선택" 은
          ✓ 와 여백까지 110px 안쪽이다. 위아래로 쌓으면 한 덩어리로 고르는
          컨트롤이 다시 라디오 목록처럼 보인다.
        */
        assertThat(css).as("세로로 쌓던 360px 규칙은 두지 않는다")
                .doesNotContain("@media (max-width: 360px)");
        assertThat(between(css, ".travel-plan-poll-radio-row {", "\n}"))
                .doesNotContain("flex-direction: column");
        assertThat(between(css, ".travel-plan-poll-radio {", "\n}"))
                // 두 칸이 남는 폭을 똑같이 나눠 갖는다
                .contains("flex: 1 1 0")
                .contains("min-width: 0");
    }

    @Test
    void aLongOptionNameDoesNotPushTheVoteCountOut() throws IOException {
        String css = cssFile();

        /*
          줄 안의 칸은 접히기 전 제 너비를 먼저 요구한다. 바닥을 풀어 두지
          않으면 긴 선택지 이름이 "12표" 를 창 밖으로 밀어낸다.
        */
        assertThat(between(css, ".travel-plan-poll-result-row > span:first-child {", "\n}"))
                .contains("min-width: 0");
        assertThat(between(css, ".travel-plan-poll-choice-count {", "\n}"))
                .as("표 수는 줄어들지 않는 쪽이다")
                .contains("flex: 0 0 auto");
        // 끝난 투표 카드의 "결과 ○○○" 도 같은 자리다
        assertThat(between(css, ".travel-plan-poll-card-meta > span {", "\n}"))
                .contains("min-width: 0");
    }

    @Test
    void whatUsersTypedFoldsInsteadOfWideningTheModal() throws IOException {
        String css = cssFile();

        // 질문·이름·결과가 모두 한자리에서 접힌다
        assertThat(between(css, ".travel-plan-poll-card-body {", "\n}"))
                .contains("min-width: 0")
                .contains("overflow-wrap: break-word");
        assertThat(between(css, ".travel-plan-poll-detail {", "\n}"))
                .contains("overflow-wrap: break-word")
                .contains("overflow-y: auto");
    }

    @Test
    void theModalFitsTheVisibleHeightOnAPhone() throws IOException {
        String panel = between(cssFile(), ".travel-plan-poll-modal-panel {", "\n}");

        // 창은 화면 가운데에 선다. vh 로만 재면 낮은 화면에서 위아래가 밀려난다
        assertThat(panel).contains("max-height: min(600px, calc(100dvh - 40px))");
        assertThat(panel.indexOf("100dvh")).as("dvh 가 vh 뒤에 온다")
                .isGreaterThan(panel.indexOf("100vh"));
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    private String pollJs() throws IOException {
        return resource("/static/js/travel-plan-poll.js");
    }

    private String realtimeJs() throws IOException {
        return resource("/static/js/travel-plan-realtime.js");
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

    private String between(String source, String start, String end) {
        int startIndex = source.indexOf(start);
        int endIndex = source.indexOf(end, startIndex + start.length());
        assertThat(startIndex).as("start %s", start).isGreaterThanOrEqualTo(0);
        assertThat(endIndex).as("end %s", end).isGreaterThan(startIndex);
        return source.substring(startIndex, endIndex);
    }
}
