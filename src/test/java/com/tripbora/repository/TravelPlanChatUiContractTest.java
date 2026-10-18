package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채팅 화면의 구조.
 *
 * <p>연결은 하나뿐이고, 사용자가 쓴 글은 글자로만 그리며,
 * 아직 만들지 않은 투표 기능은 자리만 잡아 두고 흉내 내지 않는다.
 */
class TravelPlanChatUiContractTest {

    // ── 진입점과 패널 ───────────────────────────────────────

    @Test
    void thePlannerHasAChatButtonWithAnUnreadBadge() throws IOException {
        String detail = detailHtml();

        assertThat(detail)
                .contains("data-travel-plan-chat-toggle")
                .contains("data-travel-plan-chat-badge")
                // 화면 오른쪽 아래에 떠 있는 진입점 하나다
                .contains("class=\"travel-plan-chat\"");
        // 안 읽은 개수는 서버에서 받기 전까지 보이지 않는다
        assertThat(between(detail, "data-travel-plan-chat-badge", "</span>"))
                .contains("aria-label");
        assertThat(between(detail, "class=\"travel-plan-chat-badge\"", "</span>"))
                .contains("hidden");
    }

    @Test
    void theChatButtonIsOnlyForMembersOfTheRoom() throws IOException {
        String detail = detailHtml();

        // 참여자가 아니면 진입점 자체가 그려지지 않는다
        assertThat(between(detail, "class=\"travel-plan-chat\"", "data-travel-plan-chat-toggle"))
                .contains("travelPlan.currentMember != null");
        assertThat(between(detail, "class=\"travel-plan-chat-panel\"",
                "data-travel-plan-chat-panel"))
                .contains("travelPlan.currentMember != null");
    }

    @Test
    void thePanelHasEverythingAConversationNeeds() throws IOException {
        String detail = detailHtml();

        for (String hook : new String[]{
                "data-travel-plan-chat-panel", "data-travel-plan-chat-close",
                "data-travel-plan-chat-body",
                "data-travel-plan-chat-list", "data-travel-plan-chat-empty",
                "data-travel-plan-chat-jump", "data-travel-plan-chat-form",
                "data-travel-plan-chat-input", "data-travel-plan-chat-send",
                "data-travel-plan-chat-error"}) {
            assertThat(detail).as("panel has %s", hook).contains(hook);
        }
        // 패널은 눌러서 열기 전까지 떠 있지 않다
        assertThat(between(detail, "class=\"travel-plan-chat-panel\"", ">")).contains("hidden");
    }

    @Test
    void thereIsOneWayInAndOneWayOut() throws IOException {
        String detail = detailHtml();
        String chat = chatJs();
        String css = cssFile();

        /*
          떠 있는 버튼으로 열고 × 로 닫는다.
          접기라는 중간 상태를 두지 않는다 — 그 상태에서는 떠 있는 버튼도
          감춰져 있어 여닫는 길이 둘로 갈린다.
        */
        assertThat(detail)
                .contains("data-travel-plan-chat-close")
                .doesNotContain("data-travel-plan-chat-minimize");
        for (String source : new String[]{chat, css}) {
            assertThat(source).doesNotContain("is-minimized");
        }
        assertThat(between(chat, "function isOpen()", "}")).contains("!panel.hidden");
    }

    @Test
    void hidingThePanelActuallyHidesIt() throws IOException {
        String css = cssFile();

        /*
          패널에 display 를 정해 두면 브라우저 기본 [hidden] 규칙을 덮어써
          hidden 을 걸어도 계속 보인다. 그러면 닫기 버튼이 듣지 않는 것처럼 보이고,
          화면에는 열려 있는데 스크립트는 닫힌 줄 알아 실시간 표시까지 어긋난다.
        */
        assertThat(between(css, ".travel-plan-chat-panel {", "}")).contains("display: flex");
        assertThat(css).contains(".travel-plan-chat-panel[hidden] {\n    display: none;\n}");
    }

    @Test
    void theChatButtonDoesNotStealThePlannerSelector() throws IOException {
        String detail = detailHtml();

        /*
          스케줄러와 실시간은 첫 [data-plan-id] 를 종이로 본다.
          채팅 버튼이 그 앞에서 같은 이름을 달면 일정 동작이 그쪽에 붙어 버린다.
        */
        assertThat(between(detail, "class=\"travel-plan-chat\"", "</div>"))
                .contains("data-chat-plan-id")
                .doesNotContain("data-plan-id=");
        // 종이가 여전히 유일한 data-plan-id 다
        assertThat(countOf(detail, "th:attr=\"data-plan-id=")).isEqualTo(1);
        assertThat(chatJs()).contains("root.getAttribute(\"data-chat-plan-id\")");
    }

    @Test
    void closingIsARealButtonThatKeepsTheStatesInStep() throws IOException {
        String detail = detailHtml();
        String chat = chatJs();

        assertThat(detail).contains("aria-label=\"채팅 닫기\" data-travel-plan-chat-close");
        // 눌리는 것은 실제 button 이고 폼을 보내지 않는다
        assertThat(between(detail, "class=\"travel-plan-chat-header-actions\"",
                "data-travel-plan-chat-close")).contains("type=\"button\"");

        // 닫으면 패널과 상단 버튼 상태가 함께 바뀐다
        assertThat(between(chat, "function closePanel()", "\n    }"))
                .contains("panel.hidden = true")
                .contains("toggle?.setAttribute(\"aria-expanded\", \"false\")");
        assertThat(chat).contains("data-travel-plan-chat-close]\")\n        ?.addEventListener");
    }

    @Test
    void thePanelFloatsAtTheBottomRightWithoutSpillingOffNarrowScreens() throws IOException {
        String css = cssFile();
        String panel = between(css, ".travel-plan-chat-panel {", "}");

        // 버튼 자리는 한곳(:root)에서 정하고 패널과 버튼이 같이 쓴다
        String root = between(css, ":root {", "}");
        assertThat(between(root, "--tp-chat-edge:", ";")).contains("28px");
        assertThat(between(root, "--tp-chat-toggle-size:", ";")).contains("52px");
        assertThat(panel)
                .contains("position: fixed")
                // 오른쪽 아래 토글 버튼 바로 위에서 펼쳐져 버튼을 가리지 않는다
                .contains("right: var(--tp-chat-edge)")
                .contains("bottom: calc(var(--tp-chat-edge) + var(--tp-chat-toggle-space))")
                // PC 에서는 대화를 읽을 만큼 넓게, 좁은 화면에서는 남은 폭 안에서만
                .contains("width: min(420px, calc(100vw - var(--tp-chat-edge) * 2))");
        assertThat(between(css, ".travel-plan-chat {", "}"))
                .contains("right: var(--tp-chat-edge)")
                .contains("bottom: var(--tp-chat-edge)");
        // 옆으로 넘치는 대신 안에서 스크롤한다
        assertThat(between(css, ".travel-plan-chat-body {", "}"))
                .contains("overflow-y: auto")
                .contains("overflow-x: hidden");
    }

    @Test
    void chatIsEnteredFromAFloatingBubbleRatherThanTheTopRow() throws IOException {
        String detail = detailHtml();
        String css = cssFile();

        // 상단 줄에는 채팅 글자 버튼이 남아 있지 않다
        assertThat(between(detail, "class=\"travel-plan-top-actions\"",
                "class=\"travel-plan-notice\""))
                .doesNotContain("data-travel-plan-chat-toggle");
        // 말풍선 하나뿐인 둥근 버튼이다
        assertThat(detail)
                .contains("class=\"travel-plan-chat-icon travel-plan-chat-icon-open\"")
                .contains("aria-label=\"채팅 열기\"");
        assertThat(between(detail, "data-travel-plan-chat-toggle", "</button>"))
                .as("글자 '채팅' 은 버튼 안에 두지 않는다")
                .doesNotContain("채팅");
        assertThat(between(css, ".travel-plan-chat {", "}")).contains("position: fixed");
        assertThat(between(css, ".travel-plan-chat-toggle {", "}"))
                .contains("border-radius: 50%")
                .contains("background: #fff")
                .contains("color: var(--tp-plan-accent)");
        // 키보드로 왔을 때도 지금 어디에 있는지 보인다
        assertThat(css).contains(".travel-plan-chat-toggle:focus-visible");
    }

    @Test
    void theBubbleStaysAsAToggleWhileTheChatIsOpen() throws IOException {
        String css = cssFile();
        String chat = chatJs();

        /*
          열려 있어도 버튼은 사라지지 않고 같은 버튼으로 닫는다.
          모양을 바꾸는 기준은 채팅 스크립트가 이미 맞춰 두는 aria-expanded 다.
          여닫는 데 쓰는 상태를 새로 만들지 않는다.
        */
        assertThat(between(css, ".travel-plan-chat-toggle[aria-expanded=\"true\"] {", "}"))
                .doesNotContain("display: none")
                .contains("background: var(--tp-plan-accent-soft)");
        assertThat(css).contains(
                ".travel-plan-chat-toggle[aria-expanded=\"true\"] .travel-plan-chat-icon-close {\n    display: block;");
        assertThat(chat)
                .contains("toggle?.setAttribute(\"aria-expanded\", \"true\")")
                .contains("toggle?.setAttribute(\"aria-expanded\", \"false\")");
        // 버튼 이름도 지금 누르면 일어날 일을 말한다
        assertThat(between(chat, "function openPanel()", "\n    }"))
                .contains("toggle?.setAttribute(\"aria-label\", \"채팅 닫기\")");
        assertThat(between(chat, "function closePanel()", "\n    }"))
                .contains("toggle?.setAttribute(\"aria-label\", \"채팅 열기\")");
        assertThat(between(chat, "toggle?.addEventListener(\"click\"", "});"))
                .contains("openPanel()")
                .contains("closePanel()");
        // 패널을 이 모서리로 옮겨 와도 버튼이 가려지지 않도록 한 층 위에 둔다
        assertThat(between(css, ".travel-plan-chat {", "}")).contains("z-index: 41");
        assertThat(between(css, ".travel-plan-chat-panel {", "}")).contains("z-index: 40");
    }

    @Test
    void theUnreadBadgeStillComesFromTheServersCount() throws IOException {
        String chat = chatJs();

        // 개수를 화면에서 따로 세지 않는다. 서버가 준 값과 실시간 알림만 쓴다
        assertThat(chat)
                .contains("/chat/unread")
                .contains("unreadCount > 99 ? \"99+\"")
                .contains("badge.hidden = unreadCount <= 0");
        // 숫자만으로 뜻이 전해지지 않게 이름표를 함께 둔다
        assertThat(between(detailHtml(), "class=\"travel-plan-chat-badge\"", "</span>"))
                .contains("aria-label=\"안 읽은 메시지\"");
        // 강한 원색 빨강 대신 가라앉은 코럴이고, 숫자는 흰색이다
        assertThat(between(cssFile(), ".travel-plan-chat-badge {", "}"))
                .contains("background: #d96a6a")
                .contains("color: #fff")
                .contains("position: absolute");
    }

    @Test
    void thePanelKeepsItsSizeEvenWithNothingInIt() throws IOException {
        String css = cssFile();
        String panel = between(css, ".travel-plan-chat-panel {", "}");

        /*
          높이를 정해 두지 않으면 내용만큼만 자라서, 첫 메시지를 보내기 전에는
          작은 말풍선 하나짜리 팝업처럼 보인다.
        */
        assertThat(panel)
                .contains("height: 500px")
                // 화면이 낮으면 그 안으로 줄어든다(토글 버튼 위로 올라선 만큼 뺀다)
                .contains("max-height: min(calc(70vh - var(--tp-chat-toggle-space)),")
                .contains("flex-direction: column");
    }

    @Test
    void onlyTheMessageAreaScrolls() throws IOException {
        String css = cssFile();

        // 가운데 칸이 남는 높이를 전부 가져가고, 넘기는 것도 여기뿐이다
        assertThat(between(css, ".travel-plan-chat-body {", "}"))
                .contains("flex: 1 1 auto")
                .contains("min-height: 0")
                .contains("overflow-y: auto");
        // 머리글과 입력줄은 제자리에 남는다
        for (String fixed : new String[]{
                ".travel-plan-chat-header {", ".travel-plan-chat-form {"}) {
            assertThat(between(css, fixed, "}")).as("%s", fixed).contains("flex: 0 0 auto");
        }
        // 줄 목록은 눌려 접히지 않고 제 높이를 지킨다
        assertThat(between(css, ".travel-plan-chat-list {", "}")).contains("flex: 0 0 auto");
    }

    @Test
    void anEmptyChatPutsItsWordsAroundTheMiddle() throws IOException {
        String css = cssFile();

        // 빈 칸 가운데에 선다. 맨 위에 붙지 않는다
        assertThat(between(css, ".travel-plan-chat-empty {", "}"))
                .contains("margin: auto 0")
                .contains("text-align: center");
        assertThat(between(css, ".travel-plan-chat-body {", "}"))
                .contains("display: flex");
        // 기록의 맨 앞 표시는 대화 위에 그대로 남는다
        assertThat(between(css, ".travel-plan-chat-start {", "}"))
                .contains("flex: 0 0 auto")
                .doesNotContain("margin: auto");
        // 단순 말풍선 아이콘과 두 단계의 안내 문구로 빈 상태를 구분한다.
        assertThat(between(detailHtml(), "data-travel-plan-chat-empty", "</p>"))
                .contains("<svg", "aria-hidden=\"true\"", "<strong>", "<span>")
                .doesNotContain("<img");
    }

    @Test
    void thePanelUsesItsOwnWhiteSurfaceWithoutChangingSharedModalStyles() throws IOException {
        String css = resource("/static/css/travel-plan-chat.css");

        assertThat(detailHtml()).contains("/css/travel-plan-chat.css");
        assertThat(between(css, ".travel-plan-chat-panel {", "}"))
                .contains("background: #fff", "border-radius: 20px");
        assertThat(between(css, ".travel-plan-chat-panel .travel-plan-chat-header {", "}"))
                .contains("background: #fff");
        assertThat(between(css, ".travel-plan-chat-panel .travel-plan-chat-form {", "}"))
                .contains("background: #fff");
    }

    @Test
    void anEmptyRoomSaysSoGently() throws IOException {
        assertThat(detailHtml()).contains("아직 대화가 없어요.");
    }

    // ── 사용자가 쓴 글 ──────────────────────────────────────

    @Test
    void messagesAreOnlyEverPutOnScreenAsText() throws IOException {
        String chat = chatJs();

        // 채팅은 사용자 입력이다. innerHTML 로 넣으면 태그가 화면에서 실행된다
        assertThat(chat)
                .doesNotContain("innerHTML")
                .doesNotContain("insertAdjacentHTML")
                .doesNotContain("outerHTML")
                .doesNotContain("document.write");
        // 본문도 보낸 사람 이름도 글자로만 넣는다
        assertThat(chat)
                .contains("content.textContent =")
                .contains("sender.textContent =");
    }

    @Test
    void noMarkdownOrLinkPreviewSneaksIn() throws IOException {
        String chat = chatJs();

        assertThat(chat)
                .doesNotContain("marked")
                .doesNotContain("markdown")
                .doesNotContain("createElement(\"a\")");
    }

    @Test
    void lineBreaksTypedBySomeoneAreShownAsTyped() throws IOException {
        assertThat(between(cssFile(), ".travel-plan-chat-content {", "}"))
                .contains("white-space: pre-wrap")
                .contains("overflow-wrap: break-word");
    }

    // ── 보내기 ──────────────────────────────────────────────

    @Test
    void enterSendsAndShiftEnterMakesANewLine() throws IOException {
        String chat = chatJs();
        String keydown = between(chat, "input?.addEventListener(\"keydown\"", "});");

        assertThat(keydown)
                .contains("event.key === \"Enter\" && !event.shiftKey")
                .contains("send()");
    }

    @Test
    void koreanBeingAssembledDoesNotSendYet() throws IOException {
        String chat = chatJs();

        // 조합 중의 Enter 는 글자를 확정하는 것이지 전송이 아니다
        assertThat(between(chat, "input?.addEventListener(\"keydown\"", "});"))
                .contains("window.travelPlanIme.isComposing(event, composing)");
        assertThat(chat)
                .contains("compositionstart")
                .contains("compositionend")
                // 조합 알고리즘을 직접 만들지 않는다
                .doesNotContain("charCodeAt")
                .doesNotContain("0xAC00");
    }

    @Test
    void theImeRuleLivesInExactlyOnePlace() throws IOException {
        String scheduler = schedulerJs();
        String chat = chatJs();

        // 일정 편집기와 채팅이 같은 판단을 쓴다. 복사해 두고 서로 달라지지 않게 한다
        assertThat(scheduler).contains("window.travelPlanIme = {");
        assertThat(countOf(scheduler + chat, "event.keyCode === 229")).isEqualTo(1);
        assertThat(scheduler).contains("window.travelPlanIme.isComposing(event, composing)");
        assertThat(chat).contains("window.travelPlanIme.isComposing(event, composing)");
    }

    @Test
    void theSameMessageIsNotSentTwiceByAccident() throws IOException {
        String send = between(chatJs(), "function send()", "\n    }");

        assertThat(send)
                .contains("if (sending || content === \"\") return")
                .contains("sending = true")
                // 성공하면 입력창을 비운다
                .contains("input.value = \"\"");
    }

    @Test
    void aFailedSendKeepsWhatWasTyped() throws IOException {
        String chat = chatJs();
        String send = between(chat, "function send()", "\n    }");

        // 연결이 없으면 보낸 척하지 않고, 입력한 내용도 지우지 않는다
        assertThat(send).contains("showError(");
        assertThat(between(send, "if (!sent)", "}")).doesNotContain("input.value = \"\"");
        // 실패 사유는 나에게만 온다
        assertThat(chat).contains("showError(payload.message)");
    }

    @Test
    void whatIsSentIsOnlyTheText() throws IOException {
        String realtime = realtimeJs();

        // 보낸 사람은 서버가 정한다
        assertThat(between(realtime, "sendChat(content)", "\n        }"))
                .contains("publishChat(\"send\", { content })")
                .doesNotContain("memberId")
                .doesNotContain("displayName");
    }

    // ── 연결 ────────────────────────────────────────────────

    @Test
    void theChatDoesNotOpenAWebSocketOfItsOwn() throws IOException {
        String chat = chatJs();

        // STOMP client 는 travel-plan-realtime.js 하나뿐이다
        assertThat(chat)
                .doesNotContain("new StompJs.Client")
                .doesNotContain("new WebSocket")
                .doesNotContain("brokerURL");
        assertThat(countOf(realtimeJs(), "new StompJs.Client")).isEqualTo(1);
        // 연결은 그쪽이 내어 준 창구로만 쓴다
        assertThat(chat)
                .contains("realtime()?.subscribeChat(")
                .contains("realtime().sendChat(");
    }

    @Test
    void theChatChannelIsSubscribedEvenWhileThePanelIsClosed() throws IOException {
        String realtime = realtimeJs();

        // 닫혀 있어도 받아야 안 읽은 개수가 쌓인다
        assertThat(realtime).contains("`/topic/travel-plans/${planId}/chat`");
        // 처리 결과는 나에게만 오는 개인 큐로 돌아온다
        assertThat(realtime).contains("/user/queue/travel-plan-chat");
    }

    @Test
    void aMessageArrivesWithoutReloadingThePage() throws IOException {
        String chat = chatJs();

        assertThat(chat)
                .contains("MESSAGE_CREATED")
                .contains("MESSAGE_DELETED")
                .contains("appendItem(messageNode(messageItem(message))")
                // 새로고침으로 따라잡지 않는다
                .doesNotContain("location.reload")
                .doesNotContain("location.href =");
    }

    @Test
    void anOpenPanelShowsANewMessageWithoutPressingTheButtonAgain() throws IOException {
        String chat = chatJs();
        String created = between(chat, "function onMessageCreated(message)", "\n    }");

        // 받은 그 자리에서 목록에 붙인다. 다시 열거나 기록을 다시 읽지 않는다
        assertThat(created).contains("appendItem(messageNode(messageItem(message))");
        assertThat(created)
                .doesNotContain("loadRecent()")
                .doesNotContain("fetchTimeline(");

        // 상단 버튼을 다시 눌러야만 보이던 길로 되돌아가지 않는다
        assertThat(between(chat, "toggle?.addEventListener(\"click\"", "});"))
                .contains("openPanel()")
                .contains("closePanel()");
    }

    @Test
    void everyoneIncludingTheSenderGetsTheSameOneCopy() throws IOException {
        String chat = chatJs();

        // 보낸 사람도 방 알림으로 받는다. 보내면서 미리 그려 두지 않는다
        assertThat(between(chat, "function send()", "\n    }"))
                .doesNotContain("messageNode(")
                .doesNotContain("list.append(");
        // 같은 메시지가 두 번 붙지 않는다
        assertThat(between(chat, "function onMessageCreated(message)", "\n    }"))
                .contains("!nodeOf(message.id)");
    }

    // ── 메시지 생김새 ───────────────────────────────────────

    @Test
    void myMessagesStandOnTheRightAndEveryoneElsesOnTheLeft() throws IOException {
        String chat = chatJs();
        String css = cssFile();

        // 자리를 가르는 것은 class 하나뿐이다
        assertThat(chat).contains("if (isMine(message)) item.classList.add(\"is-mine\")");
        assertThat(between(css, ".travel-plan-chat-message {", "}"))
                .contains("align-items: flex-start");
        assertThat(between(css, ".travel-plan-chat-message.is-mine {", "}"))
                .contains("align-items: flex-end");
        // 오른쪽에 서는 것으로 내 말인 줄 알 수 있어 "나" 를 매번 적지 않는다
        assertThat(chat).doesNotContain("(나)");
        assertThat(css).contains(
                ".travel-plan-chat-message.is-mine .travel-plan-chat-sender");
    }

    @Test
    void theTalkIsInBubblesRatherThanFullWidthRows() throws IOException {
        String css = cssFile();

        // 패널 폭을 꽉 채우는 긴 행이 아니다
        assertThat(between(css, ".travel-plan-chat-row {", "}")).contains("max-width: 78%");
        assertThat(between(css, ".travel-plan-chat-message {", "}"))
                .doesNotContain("border-bottom");
        // 남의 말은 흰 종이, 내 말은 같은 accent 계열을 아주 옅게
        assertThat(between(css, ".travel-plan-chat-bubble {", "}"))
                .contains("background: #fff")
                .contains("border-radius: 12px");
        assertThat(between(css, ".travel-plan-chat-message.is-mine .travel-plan-chat-bubble {",
                "}")).contains("background: var(--tp-plan-accent-soft)");
        // 긴 말도 옆으로 넘치지 않고 접힌다
        assertThat(between(css, ".travel-plan-chat-content {", "}"))
                .contains("overflow-wrap: break-word");
    }

    @Test
    void theSameVoiceInARowReadsAsOneBlock() throws IOException {
        String chat = chatJs();
        String css = cssFile();

        /*
          묶는 것은 그리는 단계에서만이다.
          DB 의 메시지를 합치거나 고쳐 쓰지 않는다.
        */
        assertThat(chat)
                .contains("const GROUP_WINDOW_MS = 3 * 60 * 1000")
                .contains("function regroup()")
                .contains("node.classList.toggle(\"is-continued\", continued)");
        // 이름은 묶음의 첫 줄에만, 시각은 마지막 줄에만 남는다
        assertThat(css).contains(
                ".travel-plan-chat-message.is-continued .travel-plan-chat-sender");
        assertThat(css).contains(
                ".travel-plan-chat-message:not(.is-group-end) .travel-plan-chat-time");
        // 이어지는 줄은 바짝 붙고, 사람이 바뀌면 한 번 쉰다
        assertThat(between(css, ".travel-plan-chat-message {", "}"))
                .contains("margin-top: 14px");
        assertThat(between(css, ".travel-plan-chat-message.is-continued {", "}"))
                .contains("margin-top: 3px");
    }

    @Test
    void aDayThatChangesGetsOneSmallLine() throws IOException {
        String chat = chatJs();

        assertThat(chat)
                .contains("function dateDividerNode(")
                .contains("data-travel-plan-chat-date");
        // 다시 셀 때마다 처음부터 놓는다. 같은 줄이 두 번 생기지 않는다
        assertThat(between(chat, "function regroup()", "let previous = null;"))
                .contains("[data-travel-plan-chat-date]")
                .contains(".remove()");
        assertThat(between(cssFile(), ".travel-plan-chat-date {", "}"))
                .contains("justify-content: center");
    }

    @Test
    void aNoticeIsNotDressedUpAsSomeonesLine() throws IOException {
        String css = cssFile();

        // 가운데 놓인 차분한 알림 하나다. 말풍선이 아니다
        assertThat(between(css, ".travel-plan-chat-notice {", "}"))
                .contains("justify-content: center");
        assertThat(between(css, ".travel-plan-chat-notice-body {", "}"))
                .contains("text-align: center")
                .contains("background: var(--tp-poll-surface)");
    }

    @Test
    void regroupingRunsBeforeTheScrollIsPutBack() throws IOException {
        String older = between(chatJs(), "async function loadOlder()", "loadingOlder = false;");

        /*
          묶음을 다시 세면 이름·시각·날짜 줄이 생기거나 사라져 높이가 달라진다.
          자리를 맞추기 전에 끝내야 보고 있던 메시지가 제자리에 남는다.
        */
        assertThat(older).contains("regroup()");
        assertThat(older.indexOf("regroup()"))
                .isLessThan(older.indexOf("body.scrollTop += body.scrollHeight - before"));
        // 앞에 끼워 넣는 방식 자체는 그대로다
        assertThat(older).contains("const before = body.scrollHeight");
    }

    // ── 이모지 ──────────────────────────────────────────────

    @Test
    void theComposerHasASmallEmojiButtonBesideTheOthers() throws IOException {
        String detail = detailHtml();
        String composer = between(detail, "class=\"travel-plan-chat-form\"", "</form>");

        // [😊] [입력칸] [전송] 이 한 줄에 같은 크기로 선다
        assertThat(composer)
                .contains("data-travel-plan-chat-emoji-toggle")
                .contains("aria-label=\"이모지 선택\"")
                .contains("aria-haspopup=\"true\"");
        assertThat(composer.indexOf("data-travel-plan-chat-emoji-toggle"))
                .isLessThan(composer.indexOf("data-travel-plan-chat-input"));
        assertThat(composer.indexOf("data-travel-plan-chat-input"))
                .isLessThan(composer.indexOf("data-travel-plan-chat-send"));
        // 같은 control 크기를 쓴다(전용 큰 버튼을 만들지 않는다)
        assertThat(composer).contains("class=\"travel-plan-chat-tool\"");
    }

    @Test
    void thePickerOpensBesideTheComposerAndClosesEveryUsualWay() throws IOException {
        String chat = chatJs();
        String picker = emojiJs();
        String css = resource("/static/css/travel-plan-emoji.css");

        assertThat(chat)
                .contains("window.TravelPlanEmojiPicker?.mount")
                .contains("function closeEmoji()")
                .contains("emojiPicker?.close()");
        assertThat(picker)
                .contains("async function open()")
                .contains("panel.hidden = false")
                .contains("aria-expanded");
        // 바깥 클릭과 Esc 로 닫힌다
        assertThat(chat)
                .contains("closeEmoji();")
                .contains("event.key !== \"Escape\"");
        // 입력줄 위에서 열리고 부모 패널의 overflow에 잘리지 않는다.
        assertThat(between(css, ".travel-plan-chat-emoji-panel.tp-emoji-picker {", "}"))
                .contains("position: fixed")
                .contains("max-width: calc(100vw - 24px)")
                .contains("background: #fff");
        // 손가락으로도 누를 수 있는 크기를 남긴다
        assertThat(between(css, ".travel-plan-chat-emoji-item {", "}"))
                .contains("height: 40px");
        assertThat(css).contains("button:focus-visible");
    }

    @Test
    void theEmojiAreLaidOutInFiveColumnsRatherThanOneLongList() throws IOException {
        String css = cssFile();
        String panel = between(css, ".travel-plan-chat-emoji-panel {", "}");

        // 칸 나누기는 격자가 맡고, 최근 사용과 전체 목록이 그것을 함께 쓴다
        assertThat(between(css, ".travel-plan-chat-emoji-grid {", "}"))
                .contains("display: grid")
                .contains("grid-template-columns: repeat(5, 1fr)");
        /*
          떠 있는 상자는 폭을 정해 주지 않으면 담긴 32px 짜리 버튼 자리만큼만
          잡아, 이모지가 한 줄에 하나씩 세로로 늘어선다.
        */
        assertThat(panel)
                .contains("width: 220px")
                .contains("max-width: calc(100vw - 48px)");
        // 많아지면 채팅 패널을 밀지 않고 이 안에서만 넘긴다
        assertThat(panel)
                .contains("max-height: min(232px, calc(70vh - 96px - var(--tp-chat-toggle-space)))")
                .contains("overflow-y: auto");
        assertThat(css).contains(".travel-plan-chat-emoji-panel::-webkit-scrollbar");
    }

    @Test
    void theEmojiButtonsGoStraightIntoTheGrid() throws IOException {
        String build = between(emojiJs(), "function render()", "function position()");

        // 줄마다 상자를 만들면 그 상자가 각자 폭을 잡아 격자가 되지 않는다
        assertThat(build)
                .contains("visible.map(item =>")
                .contains("grid.replaceChildren(...buttons)")
                .doesNotContain("travel-plan-chat-emoji-row");
        assertThat(cssFile()).doesNotContain(".travel-plan-chat-emoji-row");
        // 카테고리/검색이 바뀌면 같은 격자를 다시 그린다.
        assertThat(build).contains("search(items, query)");
    }

    @Test
    void pickingAnEmojiTypesItWhereTheCursorIs() throws IOException {
        String picker = emojiJs();
        String insert = between(picker, "function choose(item)", "function render()");

        /*
          고른 것을 바로 보내지 않는다. 쓰던 글은 그대로 두고
          커서 자리에만 끼워 넣은 뒤 입력칸으로 돌아간다.
        */
        assertThat(insert)
                .contains("insertAt(input.value, savedStart, savedEnd, item.emoji, maxLength)")
                .contains("input.focus({preventScroll: true})")
                .contains("input.setSelectionRange(next.caret, next.caret)")
                // 넣은 만큼 입력칸 높이도 다시 맞춘다
                .contains("onInsert?.()");
        assertThat(picker)
                .contains("input.selectionStart")
                .contains("input.selectionEnd")
                .contains("value.slice(0, start) + emoji + value.slice(end)");
        // 고르는 것만으로 전송되지 않는다
        assertThat(insert).doesNotContain("send()");
    }

    @Test
    void anEmojiIsJustTextOnItsWayToTheDatabase() throws IOException {
        String chat = chatJs();
        String picker = emojiJs();

        // 그림 파일도, 새 메시지 종류도, 새 라이브러리도 쓰지 않는다
        assertThat(picker)
                .contains("'travel-plan-chat-emoji-item', item.emoji")
                .contains("node.textContent = text")
                .doesNotContain("<img")
                .doesNotContain("emoji-mart")
                .doesNotContain("EMOJI\"");
        // 보내는 길은 지금 쓰던 그대로다
        assertThat(between(chat, "function send()", "\n    }"))
                .contains("realtime().sendChat(content)");
        // 받아 그릴 때도 글자로만 넣는다(이모지만 있는 메시지도 같은 말풍선이다)
        assertThat(chat).contains("content.textContent = message.deleted");
    }

    @Test
    void theEmojiCatalogIncludesCommonTravelChoicesAndSearchableUnicodeData() throws IOException {
        String rows = resource("/static/data/travel-plan-emoji.json");

        for (String emoji : new String[]{"😊", "👍", "❤️", "🎉", "✈️", "🏨", "📸"}) {
            assertThat(rows).as("자주 쓰는 %s", emoji).contains(emoji);
        }
        // 이번 단계는 고르기까지다. 스티커나 GIF 는 만들지 않는다
        assertThat(emojiJs())
                .contains("const CATEGORIES = [")
                .contains("function search(items, query)")
                .doesNotContain("sticker")
                .doesNotContain("giphy");
    }

    @Test
    void whatWasPickedLatelyComesBackToTheTop() throws IOException {
        String chat = emojiJs();

        assertThat(chat)
                .contains("const RECENT_LIMIT = 24")
                .contains("function remember(emoji)");
        // 같은 것을 다시 골라도 두 번 쌓이지 않고 맨 앞으로 올라간다
        assertThat(between(chat, "function remember(emoji)", "function saveSelection()"))
                .contains("[emoji, ...readRecent().filter(saved => saved !== emoji)]")
                .contains(".slice(0, RECENT_LIMIT)");
        // 고르면 넣는 것과 기억하는 것이 함께 일어난다
        assertThat(between(chat, "function choose(item)", "function render()"))
                .contains("input.value = next.value")
                .contains("remember(item.emoji)");
    }

    @Test
    void theRecentListLivesInThisBrowserAlone() throws IOException {
        String chat = emojiJs();

        // 서버로 보내지 않고 DB 에도 두지 않는다
        assertThat(chat).contains("view.localStorage.getItem(RECENT_KEY)");
        assertThat(between(chat, "function remember(emoji)", "function saveSelection()"))
                .doesNotContain("fetch(")
                .doesNotContain("realtime()");
        /*
          저장을 막아 둔 브라우저에서도 고르는 것 자체는 되어야 한다.
          읽고 쓰는 곳이 모두 막힌 경우를 견딘다.
        */
        assertThat(chat)
                .contains("catch (_)")
                .contains("Array.isArray(saved)")
                // 저장된 값도 남이 고쳐 넣을 수 있다
                .contains("typeof emoji === 'string'")
                .contains("allowed.has(emoji)");
    }

    @Test
    void anEmptyRecentCategoryExplainsWhereFutureSelectionsWillAppear() throws IOException {
        String picker = emojiJs();
        assertThat(picker)
                .contains("{id: 'recent', label: '최근 사용'")
                .contains("active === 'recent'")
                .contains("선택한 이모지가 여기에 표시돼요.")
                .contains("grid.replaceChildren(...buttons)");
        assertThat(detailHtml()).contains("/js/travel-plan-emoji-picker.js");
    }

    // ── 메시지 반응 ─────────────────────────────────────────

    @Test
    void reactingIsOfferedOnHoverAndStaysReachableByTouch() throws IOException {
        String chat = chatJs();
        String css = cssFile();

        assertThat(chat)
                .contains("function reactionPickerOf(message)")
                .contains("aria-label\", \"반응 남기기\"");
        // 여섯 가지뿐이고, 전체 이모지 목록과 다른 자리다
        assertThat(between(chat, "const REACTION_TYPES = [", "];"))
                .contains("\"LIKE\"").contains("\"HEART\"").contains("\"LAUGH\"")
                .contains("\"WOW\"").contains("\"SAD\"").contains("\"PARTY\"");
        // 평소에는 드러나지 않는다
        assertThat(between(css, ".travel-plan-chat-react-button {", "}")).contains("opacity: 0");
        assertThat(css).contains(
                ".travel-plan-chat-message:hover .travel-plan-chat-react-button");
        // hover 가 없는 화면에서는 늘 보인다
        assertThat(css).contains("@media (hover: none) {\n"
                + "    .travel-plan-chat-react-button {\n"
                + "        opacity: 1;\n"
                + "    }\n"
                + "}");
    }

    @Test
    void theReactionButtonIsQuietButLegibleOnceItShows() throws IOException {
        String css = cssFile();
        String button = between(css, ".travel-plan-chat-react-button {", "}");

        // 나왔을 때는 눌러도 되는 것인지 알아볼 수 있어야 한다
        assertThat(button)
                .contains("width: 24px")
                .contains("height: 24px")
                .contains("font-size: 15px");
        // 큰 원형 버튼이나 테두리·그림자를 두지 않는다
        assertThat(button)
                .contains("border: none")
                .doesNotContain("box-shadow")
                .doesNotContain("border-radius: 50%");
        // 줄에 손을 얹으면 옅은 바탕과 또렷한 회색 글자가 함께 나온다
        assertThat(between(css,
                ".travel-plan-chat-message:hover .travel-plan-chat-react-button,", "}"))
                .contains("background: var(--tp-chat-composer)")
                .contains("color: var(--tp-plan-ink-soft)");
        // 버튼 자체에 손을 얹으면 한 단계 더 또렷해진다
        assertThat(between(css, ".travel-plan-chat-react-button:hover,", "}"))
                .contains("background: var(--tp-chat-line)")
                .contains("color: var(--tp-plan-ink)");
        // 시각과 붙지 않게 한 칸 띄운다
        assertThat(between(css, ".travel-plan-chat-react {", "}")).contains("margin-left: 2px");
    }

    @Test
    void onlyRealReactionsGetAPillUnderTheBubble() throws IOException {
        String chat = chatJs();
        String css = cssFile();

        // 0 이면 그리지 않는다
        assertThat(between(chat, "function renderReactions(item, reactions)", "\n    }"))
                .contains("reaction.count > 0")
                .contains("pill.textContent = `${reaction.emoji} ${reaction.count}`")
                // 알약을 눌러도 같은 반응을 남기거나 거둘 수 있다
                .contains("toggleReaction(Number(messageId), reaction.type)")
                // 내가 누른 것만 약하게 표시된다
                .contains("if (reaction.reacted) pill.classList.add(\"is-mine\")");
        assertThat(between(css, ".travel-plan-chat-reaction.is-mine {", "}"))
                .contains("background: var(--tp-plan-accent-soft)");
        // 하나도 없으면 자리째 비어 높이를 차지하지 않는다
        assertThat(css).contains(".travel-plan-chat-reactions:not(:empty)");
    }

    @Test
    void theCountAlwaysComesFromTheServerNotFromCounting() throws IOException {
        String chat = chatJs();

        /*
          알림을 받으면 개수를 더하는 대신 그 메시지의 요약을 다시 읽는다.
          그래서 같은 알림이 두 번 와도 숫자가 어긋나지 않는다.
        */
        assertThat(chat).contains("MESSAGE_REACTION_CHANGED");
        assertThat(between(chat, "async function refreshReactions(messageId)", "\n    }"))
                .contains("/chat/messages/${messageId}/reactions")
                .contains("renderReactions(item,");
        // 화면이 개수를 더하거나 빼지 않는다
        assertThat(chat)
                .doesNotContain("count + 1")
                .doesNotContain("count - 1");
        // 보내는 것은 메시지 번호와 반응 종류까지다. 누가 눌렀는지는 서버가 정한다
        assertThat(between(chat, "function toggleReaction(messageId, reactionType)", "\n    }"))
                .contains("reactChatMessage(messageId, reactionType)");
        assertThat(between(realtimeJs(), "reactChatMessage(messageId, reactionType)", "},"))
                .contains("publishChat(\"react\", { messageId, reactionType })")
                .doesNotContain("memberId");
    }

    @Test
    void aDeletedMessageLosesBothThePickerAndThePills() throws IOException {
        String chat = chatJs();

        // 처음 그릴 때부터 지워진 메시지에는 붙이지 않는다
        assertThat(between(chat, "const reactions = document.createElement(\"div\");",
                "return item;"))
                .contains("if (!message.deleted)")
                .contains("reactionPickerOf(message)");
        // 보고 있는 중에 지워져도 함께 걷힌다
        assertThat(between(chat, "function onMessageDeleted(messageId)", "\n    }"))
                .contains("[data-travel-plan-chat-react]")
                .contains("[data-travel-plan-chat-reactions]");
        // DB 의 반응 행을 지우지는 않는다(지움은 tombstone 이다)
        assertThat(chat).doesNotContain("deleteReactions");
    }

    // ── 이전 대화 ───────────────────────────────────────────

    @Test
    void thereIsNoLoadOlderButtonAnyMore() throws IOException {
        assertThat(detailHtml())
                .doesNotContain("이전 메시지 보기")
                .doesNotContain("data-travel-plan-chat-more");
        assertThat(chatJs()).doesNotContain("data-travel-plan-chat-more");
        assertThat(cssFile()).doesNotContain(".travel-plan-chat-more");
    }

    @Test
    void scrollingUpBringsTheOlderMessagesIn() throws IOException {
        String chat = chatJs();

        // 위쪽에 가까워지면 알아서 이어 온다
        assertThat(between(chat, "body.addEventListener(\"scroll\"", "});"))
                .contains("if (body.scrollTop <= OLDER_TRIGGER_PX) loadOlder()");
        // 이미 있는 cursor 조회를 그대로 쓴다.
        // 대화와 투표 알림은 표가 달라 기준을 각자 들고 간다
        assertThat(between(chat, "async function loadOlder()", "\n    }"))
                .contains("fetchTimeline(beforeMessageId, beforePollId)")
                .contains("list.prepend(");
    }

    @Test
    void readingOldMessagesDoesNotMakeTheScreenJump() throws IOException {
        String older = between(chatJs(), "async function loadOlder()", "\n    }");

        // 앞에 끼워 넣어 늘어난 높이만큼 스크롤을 내려 같은 자리에 남긴다
        assertThat(older)
                .contains("const before = body.scrollHeight")
                .contains("body.scrollTop += body.scrollHeight - before");
        // 보정이 붙이기보다 나중이다
        assertThat(older.indexOf("list.prepend("))
                .isLessThan(older.indexOf("body.scrollTop += body.scrollHeight - before"));
    }

    @Test
    void theSameOlderPageIsNotFetchedTwice() throws IOException {
        String chat = chatJs();

        // 위에서 스크롤이 여러 번 튀어도 요청은 한 번뿐이고, 더 없으면 묻지 않는다
        assertThat(between(chat, "async function loadOlder()", "\n    }"))
                .contains("if (loadingOlder || !hasMoreOlder) return")
                .contains("loadingOlder = true")
                .contains("loadingOlder = false");
        // 다음 기준은 서버가 알려 준 것을 그대로 쓴다
        assertThat(between(chat, "function rememberCursor(payload)", "\n    }"))
                .contains("hasMoreOlder = !!payload.hasMore")
                .contains("beforeMessageId = payload.nextBeforeMessageId")
                .contains("beforePollId = payload.nextBeforePollId");
    }

    @Test
    void theStartOfTheConversationIsMarkedOnlyWhenThereIsNoMore() throws IOException {
        assertThat(between(chatJs(), "function renderEmptyState()", "\n    }"))
                .contains("start.hidden = hasMoreOlder || list.childElementCount === 0");
        assertThat(detailHtml()).contains("대화의 시작이에요.");
    }

    @Test
    void whatWasMissedWhileDisconnectedIsFetchedAgain() throws IOException {
        String chat = chatJs();

        // 지난 알림을 되돌려 주는 장치를 만들지 않고 그때 서버에서 다시 읽는다
        assertThat(between(chat, "realtime()?.onReconnected(", "});"))
                .contains("loadUnread()")
                .contains("loadRecent()");
    }

    // ── 읽음 / 안 읽은 개수 ─────────────────────────────────

    @Test
    void nothingIsMarkedReadWhileThePanelIsClosed() throws IOException {
        String chat = chatJs();

        assertThat(between(chat, "function isReading()", "\n    }"))
                .contains("isOpen()")
                .contains("document.visibilityState === \"visible\"");
        // 읽음 처리는 반드시 그 확인을 지나서만 일어난다
        assertThat(between(chat, "function markRead()", "\n    }"))
                .contains("if (!isReading()) return");
    }

    @Test
    void aMessageThatArrivesWhileClosedRaisesTheBadge() throws IOException {
        String created = between(chatJs(), "function onMessageCreated(message)", "\n    }");

        assertThat(created)
                .contains("if (isMine(message)) return")
                .contains("setUnread(unreadCount + 1)")
                .contains("markRead()");
    }

    @Test
    void scrollingDoesNotWriteToTheDatabaseEveryTime() throws IOException {
        String chat = chatJs();

        // 아래까지 내려온 순간에만 읽음을 알린다
        assertThat(between(chat, "body.addEventListener(\"scroll\"", "});"))
                .contains("if (!isAtBottom()) return")
                .contains("markRead()");
    }

    // ── 스크롤 ──────────────────────────────────────────────

    @Test
    void readingOldMessagesIsNotInterrupted() throws IOException {
        String chat = chatJs();

        // 위에서 옛 대화를 읽는 중이면 끌어내리지 않고 알림만 띄운다
        assertThat(between(chat, "function appendItem(node, countsAsNew)", "\n    }"))
                .contains("const stick = isAtBottom()")
                .contains("pendingNew += 1");
        assertThat(chat).contains("jump.textContent = `새 메시지 ${pendingNew}개`");
        // 스크롤 라이브러리를 들이지 않는다
        assertThat(chat).doesNotContain("scrollIntoView(");
    }

    // ── 지우기 ──────────────────────────────────────────────

    @Test
    void onlyMyOwnMessageGetsAMenu() throws IOException {
        String chat = chatJs();

        assertThat(between(chat, "item.append(sender, row, reactions);", "return item;"))
                .contains("if (isMine(message) && !message.deleted)")
                .contains("deleteMenuOf(message.messageId)");
        // 상시 보이는 빨간 휴지통을 두지 않는다
        assertThat(between(chat, "function deleteMenuOf(messageId)", "return menu;"))
                .contains("button.textContent = \"⋯\"")
                .contains("remove.textContent = \"삭제\"");
        assertThat(between(cssFile(), ".travel-plan-chat-menu-button {", "}"))
                .contains("opacity: 0");
    }

    @Test
    void theDeleteActionCarriesTheNumberItWasGivenNotAFieldItGuesses() throws IOException {
        String chat = chatJs();

        /*
          기록에서 온 줄과 실시간으로 온 줄은 번호가 담긴 필드 이름이 서로 다르다
          (messageId / id). 삭제 자리에서 객체를 다시 뒤지면 한쪽에서 undefined 가
          나가고, 서버는 어느 메시지인지 알 수 없어 그대로 거절한다.
          그래서 여기서는 번호만 받아 그대로 쓴다.
        */
        assertThat(chat).contains("function deleteMenuOf(messageId)");
        assertThat(between(chat, "function deleteMenuOf(messageId)", "return menu;"))
                .contains("realtime()?.deleteChatMessage(messageId)")
                // 넘겨받은 번호 말고 객체에서 다시 꺼내 쓰지 않는다
                .doesNotContain("message.");
        // 줄에 적어 둔 번호와 삭제가 보내는 번호가 같은 값에서 나온다
        assertThat(chat)
                .contains("item.setAttribute(\"data-message-id\", message.messageId)")
                .contains("deleteMenuOf(message.messageId)")
                .doesNotContain("deleteChatMessage(message.id)");
    }

    @Test
    void theTwoWaysAMessageArrivesAgreeOnItsNumber() throws IOException {
        String chat = chatJs();

        // 실시간으로 온 원본은 번호가 id 다. 화면에 넣기 전에 messageId 로 맞춘다
        assertThat(between(chat, "function messageItem(message)", "\n    }"))
                .contains("messageId: message.id");
        /*
          맞추기 전의 원본을 다루는 자리에서는 id 가 맞다.
          이 두 곳까지 messageId 로 바꾸면 실시간 메시지가 매번 새로 그려진다.
        */
        assertThat(between(chat, "function onMessageCreated(message)", "\n    }"))
                .contains("nodeOf(message.id)")
                .contains("messageNode(messageItem(message))");
    }

    @Test
    void aDeletedMessageLeavesAMarkInItsPlace() throws IOException {
        String deleted = between(chatJs(), "function onMessageDeleted(messageId)", "\n    }");

        assertThat(deleted)
                .contains("content.textContent = \"삭제된 메시지입니다.\"")
                // 목록에서 빼면 앞뒤 대화가 당겨진다
                .doesNotContain("node.remove()")
                // 지운 뒤에는 메뉴가 필요 없다
                .contains("[data-travel-plan-chat-menu]");
    }

    // ── 다음 단계 자리 ──────────────────────────────────────

    @Test
    void theVoteEntryPointSitsInTheChatHeader() throws IOException {
        String detail = detailHtml();

        // 투표로 들어가는 길은 채팅 머리글의 [투표 N] 하나뿐이다
        assertThat(between(detail, "class=\"travel-plan-chat-header-actions\"", "</div>"))
                .contains("data-travel-plan-poll-entry");
        // 입력줄에는 같은 곳으로 가는 길을 하나 더 두지 않는다
        assertThat(between(detail, "class=\"travel-plan-chat-form\"", "</form>"))
                .doesNotContain("data-travel-plan-poll-open")
                .doesNotContain("data-travel-plan-chat-tool-menu");
    }

    @Test
    void theChatOnlyShowsThatAPollHappenedAndLeavesTheRestToThePollCentre() throws IOException {
        String chat = chatJs();

        /*
          투표가 만들어졌다는 것은 대화 사이에 있었던 일이라 채팅 흐름에 한 줄 남는다.
          하지만 투표를 다루지는 않는다. 목록·만들기·마감은 투표 센터가 맡는다.
        */
        assertThat(chat)
                .contains("travelplan:chat-opened")
                .contains("POLL_CREATED")
                // 누르면 투표 센터가 열린다. 여는 것도 저쪽이 맡는다
                .contains("travelplan:poll-center-open");
        assertThat(chat)
                .doesNotContain("/polls")
                .doesNotContain("selectionType")
                .doesNotContain("options")
                .doesNotContain("vote");
    }

    @Test
    void aLowScreenKeepsTheChatHeaderOutFromUnderThePageHeader() throws IOException {
        String css = cssFile();
        String panel = between(css, ".travel-plan-chat-panel {", "\n}");

        // 대화가 없어도 같은 크기로 열린다는 것은 그대로다
        assertThat(panel).contains("height: 500px");

        /*
          낮은 화면에서는 위를 비워 둔다. 끝까지 올라오면 채팅 머리글과 닫기 버튼이
          페이지 헤더(z-index 1000) 밑에 깔려 닫을 길이 없어진다.
        */
        /*
          패널은 토글 버튼 위로 올라선 만큼 높이에서 빼므로 윗선은 예전과 같은 자리(화면의 30% 아래)다.
        */
        assertThat(panel).contains("max-height: min(calc(70vh - var(--tp-chat-toggle-space)),");

        /*
          주소창이 접혔다 펴지는 모바일에서는 vh 가 실제로 보이는 높이보다 크다.
          dvh 로 한 번 더 적어 두되, 모르는 브라우저가 vh 로 읽도록 뒤에 둔다.
        */
        assertThat(panel).contains("max-height: min(calc(70dvh - var(--tp-chat-toggle-space)),");
        assertThat(panel.indexOf("100dvh")).as("dvh 가 vh 뒤에 온다")
                .isGreaterThan(panel.indexOf("100vh"));

        // 가장 좁은 화면(아래에 붙는 패널)도 같은 짝을 갖춘다. 560px 이하는 :root 값만 바꿔 위 규칙을 그대로 쓴다
        assertThat(countOf(css, "max-height: min(calc(70dvh - var(--tp-chat-toggle-space)),")).isEqualTo(2);
    }

    @Test
    void theReactionChoicesOpenFromTheMessageLineNotTheTinyButton() throws IOException {
        String css = cssFile();

        /*
          ☺ 버튼은 24px 이라 말풍선이 길어지면 패널 가장자리까지 밀린다.
          거기서 펴면 188px 짜리 반응 줄이 패널 밖으로 나간다.
          기준은 말풍선 줄이어야 한다. 줄은 패널의 78% 를 넘지 않는다.
        */
        assertThat(between(css, ".travel-plan-chat-row {", "\n}"))
                .contains("position: relative")
                .contains("max-width: 78%");
        assertThat(between(css, ".travel-plan-chat-react {", "\n}"))
                .as("버튼이 다시 기준이 되면 긴 말풍선에서 잘린다")
                .doesNotContain("position: relative");

        // 남의 말은 줄 왼쪽 끝, 내 말은 줄 오른쪽 끝에서 안쪽으로 편다
        assertThat(between(css, ".travel-plan-chat-react-menu {", "\n}"))
                .contains("position: absolute")
                .contains("left: 0");
        assertThat(between(css,
                ".travel-plan-chat-message.is-mine .travel-plan-chat-react-menu {", "\n}"))
                .contains("left: auto")
                .contains("right: 0");
    }

    @Test
    void theEmojiPanelShrinksWithTheChatPanelOnALowScreen() throws IOException {
        String panel = between(cssFile(), ".travel-plan-chat-emoji-panel {", "\n}");

        /*
          채팅 패널이 화면의 70% 로 줄면 이 상자도 같이 줄어야 한다.
          패널이 모서리를 다듬느라 overflow 를 잘라 두어서, 높이를 고집하면
          위쪽이 잘려 나간다.
        */
        // 패널이 토글 버튼 위로 올라선 만큼도 함께 줄어든다
        assertThat(panel).contains("max-height: min(232px, calc(70dvh - 96px - var(--tp-chat-toggle-space)))");
        assertThat(panel.indexOf("70dvh")).as("dvh 가 vh 뒤에 온다")
                .isGreaterThan(panel.indexOf("70vh"));
        // 줄어든 만큼은 안에서 넘겨 본다. 격자는 옆으로 밀리지 않는다
        assertThat(panel).contains("overflow-y: auto");
    }

    @Test
    void aLongSenderNameFoldsInsteadOfBeingCutOff() throws IOException {
        String sender = between(cssFile(), ".travel-plan-chat-sender {", "\n}");

        assertThat(sender)
                // 말풍선과 같은 폭 안에 선다
                .contains("max-width: 78%")
                // 띄어쓰기 없는 긴 이름도 패널 밖으로 잘려 나가지 않는다
                .contains("overflow-wrap: break-word");
    }

    private String detailHtml() throws IOException {
        return resource("/templates/travelplan/detail.html");
    }

    private String chatJs() throws IOException {
        return resource("/static/js/travel-plan-chat.js");
    }

    private String emojiJs() throws IOException {
        return resource("/static/js/travel-plan-emoji-picker.js");
    }

    private String realtimeJs() throws IOException {
        return resource("/static/js/travel-plan-realtime.js");
    }

    private String schedulerJs() throws IOException {
        return resource("/static/js/travel-plan-scheduler.js");
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
