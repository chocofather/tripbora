package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 작성 중 상태를 주고받는 화면 계약.
 * 저장은 여전히 HTTP 로 하고, WebSocket 은 "지금 이렇게 쓰고 있다" 만 나른다.
 */
class TravelPlanEditorDraftContractTest {

    // ── 한글 입력 ───────────────────────────────────────────

    @Test
    void koreanTypingIsNeverMistakenForSaving() throws IOException {
        String scheduler = schedulerJs();

        // 한글은 여러 키가 모여 한 글자가 된다. 조합 중 Enter 는 글자 확정이지 저장이 아니다
        assertThat(scheduler)
                .contains("compositionstart")
                .contains("compositionend")
                .contains("event.isComposing")
                // 브라우저에 따라 조합 중 keyCode 가 229 로 온다
                .contains("event.keyCode === 229")
                // 판단은 한 곳에만 두어 채팅 입력과 규칙이 달라지지 않게 한다
                .contains("return composing || event.isComposing || event.keyCode === 229;")
                .contains("if (window.travelPlanIme.isComposing(event, composing)) return");

        // 조합 검사가 Enter 저장보다 먼저 온다
        int guard = scheduler.indexOf("if (window.travelPlanIme.isComposing(event, composing))");
        int enterSave = scheduler.indexOf("event.key === \"Enter\" && !event.shiftKey");
        assertThat(guard).isGreaterThan(0);
        assertThat(guard).isLessThan(enterSave);
    }

    @Test
    void aFinishedKoreanCharacterIsSentRightAway() throws IOException {
        String scheduler = schedulerJs();

        // 조합이 끝난 순간의 완성된 값은 모아 두지 않고 곧바로 보낸다
        String compositionEnd = between(scheduler,
                "field.addEventListener(\"compositionend\"", "});");
        assertThat(compositionEnd)
                .contains("composing = false")
                .contains("sendDraftNow()");
    }

    @Test
    void aSyllableStillBeingAssembledIsAlreadySentToTheOthers() throws IOException {
        String scheduler = schedulerJs();

        // 조합 중이라고 보내지 않으면 마지막 글자가 상대 화면에 늦게 나타난다.
        // 브라우저가 주는 지금 값을 그대로 보낸다.
        String input = between(scheduler,
                "field.addEventListener(\"input\"", "});");
        assertThat(input)
                .contains("sendDraftSoon()")
                .doesNotContain("if (composing) return")
                .doesNotContain("isComposing");

        // 한글을 직접 조합하지 않는다
        assertThat(scheduler)
                .contains("realtime()?.sendDraft(readDraft())")
                .doesNotContain("charCodeAt")
                .doesNotContain("0xAC00");
    }

    @Test
    void theLastValueWinsEvenIfAnEarlierSendWasStillWaiting() throws IOException {
        String scheduler = schedulerJs();

        // 모아 보내는 타이머는 값을 미리 잡아 두지 않고 보내는 순간의 값을 읽는다.
        // 그래서 늦게 발화해도 예전 글자로 되돌아갈 수 없다.
        String soon = between(scheduler, "function sendDraftSoon()", "\n        }");
        assertThat(soon)
                .contains("realtime()?.sendDraft(readDraft())")
                .doesNotContain("const value");

        // 조합이 끝나면 기다리던 것을 취소하고 최신 값을 곧바로 보낸다
        String now = between(scheduler, "function sendDraftNow()", "\n        }");
        assertThat(now)
                .contains("window.clearTimeout(draftTimer)")
                .contains("draftTimer = null")
                .contains("realtime()?.sendDraft(readDraft())");
    }

    @Test
    void bothTheNewLineAndAnExistingOneGetTheSameTyping() throws IOException {
        String scheduler = schedulerJs();

        // 입력 동작은 bind(line) 한 곳에만 있고, 추가 슬롯과 기존 일정 모두 그것을 쓴다
        String bindLines = between(scheduler, "function bindLines(root)", "\n    }");
        assertThat(countOf(bindLines, "bind(line);")).isEqualTo(2);
        assertThat(bindLines)
                .contains("[data-travel-plan-slot]")
                .contains("[data-travel-plan-item]");
    }

    @Test
    void shiftEnterStillMakesANewLine() throws IOException {
        String scheduler = schedulerJs();

        assertThat(scheduler).contains("event.key === \"Enter\" && !event.shiftKey");
        // 조합 중 blur 로도 저장되지 않는다
        assertThat(scheduler).contains("if (submitting || composing) return");
    }

    // ── 편집기가 언제 끝나는가 ──────────────────────────────

    @Test
    void movingToAnotherWindowDoesNotEndTheEditing() throws IOException {
        String scheduler = schedulerJs();

        /*
          창을 옮기면 브라우저가 입력칸에 blur 를 준다.
          그것을 "편집을 끝냈다" 로 보면 옆 창에서 이어 쓰려던 사람이
          편집기와 작성 중 내용을 잃고, 자리까지 놓여 차단이 풀린다.
          같은 페이지 안에서 다른 곳을 눌렀을 때만 끝난 것으로 본다.
        */
        String blur = between(scheduler, "field.addEventListener(\"blur\"", "});");
        assertThat(blur).contains("if (!document.hasFocus()) return");
        // 그 확인이 onBlur 보다 먼저다
        assertThat(blur.indexOf("document.hasFocus()"))
                .isLessThan(blur.indexOf("onBlur(event)"));
    }

    @Test
    void losingFocusNeverCancelsOrLetsGoOfTheSpotByItself() throws IOException {
        String scheduler = schedulerJs();
        String realtime = realtimeJs();

        // blur 자체가 취소나 unlock 을 직접 부르지 않는다
        String blur = between(scheduler, "field.addEventListener(\"blur\"", "});");
        assertThat(blur)
                .doesNotContain("closeActive()")
                .doesNotContain("closeAlt()")
                .doesNotContain("releaseLock()");

        // focusout / 창 blur / 화면 전환은 편집기 수명에 아예 관여하지 않는다
        for (String source : new String[]{scheduler, realtime}) {
            assertThat(source)
                    .doesNotContain("focusout")
                    .doesNotContain("visibilitychange")
                    .doesNotContain("window.addEventListener(\"blur\"");
        }
    }

    @Test
    void theAlternativeEditorHasNoFocusOutSavingAtAll() throws IOException {
        String scheduler = schedulerJs();

        // 조건/내용 두 칸을 오갈 때 저장되면 안 되므로 대안은 onBlur 를 아예 주지 않는다
        String bindAlt = between(scheduler, "function bindAlternatives(root)",
                "function blockWhileAlternativeEditing");
        assertThat(bindAlt).doesNotContain("onBlur");
    }

    @Test
    void escapeAndTheCancelButtonAreWhatEndTheEditing() throws IOException {
        String scheduler = schedulerJs();

        // Esc 는 두 편집기 모두에서 취소로 이어진다
        String keydown = between(scheduler, "field.addEventListener(\"keydown\"", "});");
        assertThat(keydown)
                .contains("event.key === \"Escape\"")
                .contains("onEscape()");
        assertThat(scheduler)
                .contains("onEscape: () => closeActive()")
                .contains("onEscape: () => closeAlt()")
                // 취소 버튼도 같은 곳으로 간다
                .contains("[data-travel-plan-alt-cancel]");

        // 취소는 자리를 놓는다
        assertThat(between(scheduler, "function closeActive()", "\n    }"))
                .contains("realtime()?.releaseLock()");
        assertThat(between(scheduler, "function closeAlt()", "\n    }"))
                .contains("realtime()?.releaseLock()");
    }

    @Test
    void typingIsSentInSmallBatchesNotPerKeystroke() throws IOException {
        String scheduler = schedulerJs();

        String soon = between(scheduler, "function sendDraftSoon()", "}");
        assertThat(soon).contains("if (draftTimer) return");
        // 입력이 느껴질 만큼 길게 두지 않는다
        int delay = Integer.parseInt(between(scheduler, "draftTimer = null;\n                realtime()?.sendDraft(readDraft());\n            }, ", ")").replaceAll("\\D", ""));
        assertThat(delay).isBetween(80, 150);
    }

    // ── 자리 잡기 ───────────────────────────────────────────

    @Test
    void theEditorOnlyOpensAfterTheServerHandsOverTheSpot() throws IOException {
        String scheduler = schedulerJs();

        assertThat(scheduler)
                .contains("await requestEditorLock(live, spot)")
                // 받지 못하면 열지 않는다
                .contains("if (!result.granted || activeLine || activeAlt) return")
                // 남이 쓰고 있으면 아예 시도하지 않는다
                .contains("if (live.isLockedByOther(spot)) return");

        // 자리를 받는 것이 편집기를 여는 것보다 먼저다
        assertThat(scheduler.indexOf("requestLock("))
                .isLessThan(scheduler.indexOf("line.classList.add(\"is-editing\")"));
    }

    @Test
    void theSpotIsHandedBackWhenTheEditorCloses() throws IOException {
        String scheduler = schedulerJs();

        String close = between(scheduler, "function closeActive()", "notifyEditorIdle();");
        assertThat(close).contains("realtime()?.releaseLock()");
    }

    @Test
    void theRequesterIsToldWhetherItGotTheSpot() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime)
                .contains("/user/queue/travel-plan-editor")
                .contains("payload.granted")
                .contains("pendingLocks")
                // 답이 오지 않으면 열지 않는다
                .contains("resolve({ granted: false })");
    }

    // ── 원격 표시 ───────────────────────────────────────────

    @Test
    void someoneElsesTypingShowsUpWithoutBecomingARealItem() throws IOException {
        String realtime = realtimeJs();
        String css = css();

        assertThat(realtime)
                .contains("travel-plan-remote-note")
                .contains("일정 작성 중")
                .contains("편집 중");
        // 임시 표시일 뿐이라 번호나 메뉴를 만들지 않는다
        assertThat(realtime)
                .doesNotContain("travel-plan-line-order")
                .doesNotContain("travel-plan-item-menu-list");
        // 남이 쓰는 동안에는 그 줄의 관리 메뉴를 내려 둔다
        assertThat(css).contains(".travel-plan-line.is-remote-editing .travel-plan-item-menu");
    }

    @Test
    void addingIsNotOfferedWhileSomeoneElseIsAlreadyWritingThere() throws IOException {
        String realtime = realtimeJs();
        String css = css();

        /*
          "쭈니님이 일정 작성 중" 과 "+ 일정 추가" 가 한자리에 함께 있으면 뜻이 어긋난다.
          표시는 ADD 잠금이 살아 있는 동안에만 붙고, 풀리면 함께 걷힌다.
        */
        assertThat(realtime)
                .contains("lock.mode === \"ADD\"")
                .contains("classList.add(\"is-remote-adding\")")
                .contains("classList.remove(\"is-remote-adding\")");
        assertThat(css).contains(
                ".travel-plan-line.is-slot.is-remote-adding .travel-plan-slot-hint");
        // 그 DAY 의 자리 하나만 가린다. 다른 DAY 는 그대로다
        assertThat(realtime).contains(
                "[data-travel-plan-day-id=\"${lock.dayId}\"] [data-travel-plan-slot]");
    }

    @Test
    void theTypingNoteSitsWhereTheAddActionWas() throws IOException {
        String realtime = realtimeJs();

        // 슬롯에는 본문 칸이 없어 "+ 일정 추가" 자리에 넣어야 글자가 같은 줄에 선다
        assertThat(realtime)
                .contains(".travel-plan-slot-hint")
                .contains("insertAdjacentElement(\"afterend\", note)");
        // 지금 붙어 있는 사람이라는 뜻이라 참여자 목록의 접속 점과 같은 초록을 쓴다
        String css = css();
        assertThat(between(css, ".travel-plan-remote-note::before {", "}"))
                .contains("background: #8a9a7b");
        assertThat(between(css, ".travel-plan-member.is-online .travel-plan-presence-dot {", "}"))
                .contains("#8a9a7b");
    }

    @Test
    void remoteTypingUsesItsOwnClassSoRefreshesAreNotBlockedForever() throws IOException {
        String realtime = realtimeJs();

        // .is-editing 으로 표시하면 정식 갱신이 영원히 미뤄진다
        assertThat(realtime)
                .contains("classList.add(\"is-remote-editing\")")
                .contains("classList.remove(\"is-remote-editing\")")
                .doesNotContain("classList.add(\"is-editing\")");
        // 미뤄 두는 판단은 여전히 내 편집기만 본다
        assertThat(realtime).contains("element.querySelector(\".is-editing\")");
    }

    @Test
    void myOwnTypingNeverOverwritesMyOwnBox() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime).contains("if (heldLock === lock.lockKey) return");
    }

    @Test
    void thePrivateAckCanActuallyReachTheBrowser() throws IOException {
        String config = source("config/WebSocketConfig.java");

        // 잠금 결과는 /user/queue 로 간다.
        // 브로커가 /queue 를 맡지 않으면 그 응답이 사라져 편집기가 열리지 않는다.
        assertThat(config).contains("enableSimpleBroker(\"/topic\", \"/queue\")");
    }

    @Test
    void theGrantedAckWipesAnyRemoteMarkThatArrivedFirst() throws IOException {
        String realtime = realtimeJs();

        // 방 전체 알림이 내 응답보다 먼저 도착할 수 있다.
        // 그때 생긴 "편집 중" 표시를 내 것으로 확정되는 순간 지운다.
        String reply = between(realtime, "if (payload.granted)", "waiting(payload)");
        assertThat(reply)
                .contains("heldLock = payload.lock")
                .contains("remoteLocks.delete(heldLock)")
                .contains("clearRemote(payload.lock)");
    }

    @Test
    void selfIsRecognisedByThisConnectionNotByWhoIsLoggedIn() throws IOException {
        String realtime = realtimeJs();

        // 같은 계정의 다른 탭은 남으로 보여야 하므로 memberId 로 자기 판별을 하지 않는다
        assertThat(realtime).doesNotContain("memberId ===").doesNotContain("=== lock.memberId");
        // 판별 기준은 이 연결이 실제로 붙잡은 자리다
        assertThat(realtime).contains("heldLock === lock.lockKey");
    }

    @Test
    void aBrowserOnlyEverHoldsOneSpot() throws IOException {
        String realtime = realtimeJs();
        String scheduler = schedulerJs();

        // 새 자리를 열기 전에 쓰던 자리를 놓는다
        assertThat(scheduler).contains("closeActive()");
        // 자리를 못 받았으면 붙잡은 것도 없어야 한다
        assertThat(realtime).contains("if (payload.granted)");
    }

    @Test
    void lettingGoBringsBackWhatIsActuallySaved() throws IOException {
        String realtime = realtimeJs();

        // 취소였다면 원본이 다시 보여야 하므로 그 DAY 를 서버에서 다시 읽는다
        String unlocked = between(realtime, "payload.type === \"UNLOCKED\"", "\n        }");
        assertThat(unlocked)
                .contains("dropLock(payload.lock)")
                .contains("refreshDay(payload.lock.dayId)");
    }

    @Test
    void aReconnectClearsStaleTypingMarks() throws IOException {
        String realtime = realtimeJs();

        assertThat(realtime)
                .contains("/app/travel-plans/${planId}/editor/sync")
                .contains("clearAllRemote()")
                .contains("payload.type === \"SNAPSHOT\"");
    }

    // ── 저장 ────────────────────────────────────────────────

    @Test
    void enterSavesThroughTheExistingEndpointWithoutReloading() throws IOException {
        String scheduler = schedulerJs();

        assertThat(scheduler)
                .contains("event.preventDefault()")
                .contains("fetch(form.action")
                .contains("method: \"POST\"")
                // 기존 폼의 값을 그대로 보낸다(CSRF hidden 필드 포함)
                .contains("new FormData(form)")
                .contains("\"X-Requested-With\": \"XMLHttpRequest\"");

        // 정상 저장 경로에서 화면을 통째로 새로 띄우지 않는다
        assertThat(between(scheduler, "async function save(line)", "function bindEditableField"))
                .doesNotContain("location.reload")
                .doesNotContain("window.location =")
                .doesNotContain("form.submit()");
    }

    @Test
    void afterSavingTheScreenTakesWhatTheDatabaseHas() throws IOException {
        String scheduler = schedulerJs();

        String save = between(scheduler, "async function save(line)", "function bind(line)");
        assertThat(save)
                .contains("closeActive()")
                .contains("live.refreshDay(dayId)");
    }

    @Test
    void aFailedSaveKeepsWhatWasTyped() throws IOException {
        String scheduler = schedulerJs();

        String save = between(scheduler, "async function save(line)", "function bind(line)");
        assertThat(save)
                .contains("if (!response.ok)")
                .contains("showSaveError(line")
                .contains("focusEditor(form)")
                // 실패했다고 입력을 지우거나 편집기를 닫지 않는다
                .doesNotContain("textarea.value = \"\"");
    }

    @Test
    void theServerStillAnswersPlainFormsWithARedirect() throws IOException {
        String controller = source("controller/travelplan/TravelPlanController.java");

        // 스크립트가 없으면 지금까지처럼 redirect 된다
        assertThat(controller)
                .contains("isAjax(request) ? noContent(response) : redirectToDay(travelPlanId, dayId)")
                .contains("\"XMLHttpRequest\".equals(request.getHeader(\"X-Requested-With\"))");
        // 저장 실패는 500 HTML 이 아니라 짧은 사유로 돌려준다
        assertThat(controller)
                .contains("HttpStatus.CONFLICT.value()")
                .contains("text/plain;charset=UTF-8");
    }

    @Test
    void theOptimisticVersionCheckIsStillInPlace() throws IOException {
        String fragment = resource("/templates/travelplan/fragments/schedule-day.html");
        String service = source("service/travelplan/TravelPlanService.java");

        // 자리 잡기는 1차 방어일 뿐이고 최종 충돌은 version 이 막는다
        assertThat(fragment).contains("<input type=\"hidden\" name=\"version\"");
        assertThat(service).contains("updateContent(itemId, dayId, normalizedContent, version)");
    }

    // ── 대안(B/C)도 같은 구조를 쓴다 ────────────────────────

    @Test
    void theAlternativeEditorAlsoOpensOnlyAfterTheServerHandsOverTheSpot() throws IOException {
        String scheduler = schedulerJs();

        String altOpen = between(scheduler, "async function openAlt(node)", "function showAltError");
        assertThat(altOpen)
                .contains("live.isLockedByOther(spot)")
                .contains("await requestEditorLock(live, spot)")
                .contains("if (!result.granted || activeAlt || activeLine) return")
                .contains("live?.sendDraft(altDraftOf(node))");

        // 자리를 받는 것이 편집기를 여는 것보다 먼저다
        assertThat(altOpen.indexOf("requestEditorLock("))
                .isLessThan(altOpen.indexOf("node.classList.add(\"is-editing\")"));
    }

    @Test
    void theAlternativeSpotNameSaysWhichKindOfSpotItIs() throws IOException {
        String scheduler = schedulerJs();
        String realtime = realtimeJs();

        // 새 대안은 A 일정마다 하나, 저장된 B/C 는 각각 따로다
        assertThat(between(scheduler, "function altSpotOf(node)", "\n    }"))
                .contains("alternativeId ? \"ALT_EDIT\" : \"ALT_ADD\"");
        assertThat(between(realtime, "function lockKeyOf(spot)", "\n    }"))
                .contains("`ALT:${spot.alternativeId}`")
                .contains("`ALT_ADD:${spot.itemId}`");
    }

    @Test
    void theConditionAndTheContentTravelTogether() throws IOException {
        String scheduler = schedulerJs();
        String realtime = realtimeJs();
        String controller = source("controller/travelplan/TravelPlanEditorController.java");

        // 어느 칸을 치든 두 칸의 지금 값을 함께 보내, 상대가 같은 시점 값을 본다
        assertThat(between(scheduler, "function altDraftOf(node)", "\n    }"))
                .contains("conditionLabel")
                .contains("content");
        assertThat(between(realtime, "sendDraft(draft)", "\n        }"))
                .contains("conditionLabel: payload.conditionLabel || \"\"")
                .contains("content: payload.content || \"\"");
        assertThat(controller)
                .contains("text(payload.get(\"conditionLabel\"))")
                .contains("text(payload.get(\"content\"))");
    }

    @Test
    void theAlternativeUsesTheSameImeHelperAsTheMainLine() throws IOException {
        String scheduler = schedulerJs();

        // 한글 조합 처리가 두 벌 생기지 않게 공통 helper 하나만 둔다
        assertThat(countOf(scheduler, "function bindEditableField")).isEqualTo(1);
        assertThat(countOf(scheduler, "compositionstart")).isEqualTo(1);
        String bindAlt = between(scheduler, "function bindAlternatives(root)",
                "function blockWhileAlternativeEditing");
        assertThat(bindAlt)
                .contains("bindEditableField(field, {")
                .contains("readDraft: () => altDraftOf(node)")
                .contains("onEnter: () => saveAlt(node)")
                .contains("onEscape: () => closeAlt()");
    }

    @Test
    void savingAnAlternativeDoesNotReloadThePage() throws IOException {
        String scheduler = schedulerJs();

        // 기존 POST endpoint 그대로 보내고, 그 DAY 만 다시 읽는다
        String save = between(scheduler, "async function saveAlt(node)", "\n    }");
        assertThat(save)
                .contains("fetch(form.action")
                .contains("\"X-Requested-With\": \"XMLHttpRequest\"")
                .contains("live.refreshDay(dayId)")
                // 실패해도 입력을 날리지 않는다
                .contains("showAltError(node,");
    }

    @Test
    void closingTheAlternativeEditorLetsGoOfTheSpot() throws IOException {
        String scheduler = schedulerJs();

        assertThat(between(scheduler, "function closeAlt()", "\n    }"))
                .contains("realtime()?.releaseLock()")
                .contains("notifyEditorIdle()");
    }

    @Test
    void theItemIsNotDeletedOrMovedWhileSomeoneEditsItsAlternative() throws IOException {
        String scheduler = schedulerJs();
        String css = css();

        assertThat(between(scheduler, "function blockWhileAlternativeEditing(root)", "\n    }"))
                .contains("is-alt-editing")
                .contains("hasAlternativeEditing(itemId)")
                .contains("event.preventDefault()");
        assertThat(css).contains(".travel-plan-line.is-alt-editing .travel-plan-item-menu-list form");
    }

    private String schedulerJs() throws IOException {
        return resource("/static/js/travel-plan-scheduler.js");
    }

    private String realtimeJs() throws IOException {
        return resource("/static/js/travel-plan-realtime.js");
    }

    private String css() throws IOException {
        return resource("/static/css/travel-plan.css");
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
