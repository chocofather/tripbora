/*
  한글은 여러 번의 키 입력이 모여 한 글자가 된다(ㄱ -> 겨 -> 경).
  조합이 끝나기 전의 Enter 는 글자를 확정하려는 것이지 확인/전송이 아니다.

  일정 편집기와 채팅 입력이 같은 규칙을 써야 해서 판단을 한 곳에만 둔다.
  브라우저마다 알려 주는 방법이 달라 세 가지를 모두 본다.
  (조합 알고리즘을 직접 만들지 않는다. 글자 조합은 브라우저에 맡긴다)
*/
window.travelPlanIme = {
    isComposing(event, composing) {
        return composing || event.isComposing || event.keyCode === 229;
    }
};

document.addEventListener("DOMContentLoaded", () => {
    const planner = document.querySelector("[data-plan-id]");
    if (!planner) return;

    // 화면 전체에서 열려 있는 편집기는 항상 하나뿐이다.
    // 추가 슬롯과 기존 일정 수정도 서로 동시에 열리지 않는다.
    let activeLine = null;
    let activePortal = null;
    const initialEditorStates = new WeakMap();
    const draftSummaries = new WeakMap();
    // 저장 중에는 focus-out 이 한 번 더 저장하지 않도록 잠근다.
    let submitting = false;
    let creatingCard = false;
    let editorOpening = false;
    const collapsedDays = new Set();

    // 대안 편집기도 같은 규칙을 따른다. 열려 있는 것은 A 쪽이든 대안 쪽이든 하나뿐이다.
    let activeAlt = null;

    /*
      편집기가 모두 닫혔다고 알린다.
      실시간 갱신 쪽이 이 신호를 듣고 미뤄 둔 DAY 를 그때 새로 고친다.
      (내부 변수를 밖에서 들여다보지 않도록 DOM 이벤트로만 알린다)
      다음 편집기를 곧바로 여는 중일 수 있어 한 박자 뒤에 상태를 다시 본다.
    */
    function notifyEditorIdle() {
        queueMicrotask(() => {
            if (activeLine || activeAlt) return;
            document.dispatchEvent(new CustomEvent("travelplan:editor-idle"));
        });
    }

    function formOf(line) {
        if (activePortal?.line === line) return activePortal.form;
        return line.querySelector("[data-travel-plan-slot-form], [data-travel-plan-item-form]");
    }

    function textareaOf(line) {
        // 대안 편집기의 textarea 까지 집히지 않게 A 일정 폼 안에서만 찾는다.
        const form = formOf(line);
        return form ? form.querySelector("textarea[name=content]") : null;
    }

    function setEditorType(form, type) {
        const route = type === "FLIGHT" || type === "TRANSPORT";
        const special = route || type === "ACCOMMODATION";
        const input = form.querySelector("[name=itemType]");
        if (input) input.value = type;
        form.dataset.editorType = type;
        form.querySelectorAll("[data-item-type-fields]").forEach(group => {
            const key = group.dataset.itemTypeFields;
            const active = key === (special ? type : "GENERAL") || (key === "ROUTE" && route) ||
                (key === "SPECIAL" && special);
            group.hidden = !active;
            group.querySelectorAll("input, select, textarea").forEach(field => { field.disabled = !active; });
        });
        form.querySelectorAll("[data-item-booking-field]").forEach(group => {
            group.hidden = !special;
            group.querySelectorAll("input, textarea").forEach(field => { field.disabled = !special; });
        });
    }

    function syncContent(form) {
        const content = form.querySelector("textarea[name=content]");
        const titleInput = form.querySelector("[data-item-title-input]");
        if (!content || !titleInput) return;
        const type = form.querySelector("[name=itemType]")?.value;
        const named = type === "FLIGHT" ? form.querySelector('[name="details.flightNumber"]') :
            type === "ACCOMMODATION" ? form.querySelector('[name="details.stayName"]') : null;
        const departure = form.querySelector('[name="details.departurePlace"]')?.value.trim();
        const arrival = form.querySelector('[name="details.arrivalPlace"]')?.value.trim();
        const route = ["FLIGHT", "TRANSPORT"].includes(type) ? [departure, arrival].filter(Boolean).join(" → ") : "";
        const fallback = {FLIGHT: "항공", ACCOMMODATION: "숙소", TRANSPORT: "이동"}[type] || "";
        // 기존 자유 텍스트 첫 줄은 숨긴 제목 필드에 보존한다. 전용 필드 수정으로 원문을 버리지 않는다.
        const title = titleInput.value.trim() || named?.value.trim() || route || fallback;
        const note = form.querySelector("[data-item-note-input]")?.value || "";
        content.value = title + (note ? `\n${note}` : "");
    }

    function editorState(form) {
        return JSON.stringify(Array.from(form.querySelectorAll("input[name], select[name], textarea[name]"))
            .filter(field => !field.disabled && field.name !== "_csrf" && field.name !== "version")
            .map(field => [field.name, field.value]));
    }

    function focusEditor(form) {
        const field = form.querySelector('[data-item-type-fields]:not([hidden]) input:not([disabled]):not([type=hidden])') ||
            form.querySelector("[data-item-title-input]") || form.querySelector("textarea[name=content]");
        field?.focus();
    }

    function restorePortal() {
        if (!activePortal) return;
        const {form, parent, sibling, host, button} = activePortal;
        parent.insertBefore(form, sibling?.parentNode === parent ? sibling : null);
        host.classList.remove("is-editing");
        button.setAttribute("aria-expanded", "false");
        activePortal = null;
    }

    function attachPortal(line, form, portal) {
        restorePortal();
        activePortal = {line, form, parent: form.parentNode, sibling: form.nextSibling, ...portal};
        portal.host.append(form);
        portal.host.classList.add("is-editing");
        portal.button.setAttribute("aria-expanded", "true");
    }

    function isItem(line) {
        return line.hasAttribute("data-travel-plan-item");
    }

    // 입력 높이를 내용에 맞춘다. 줄 안에서 편집하는 느낌을 유지하기 위한 것이다.
    function autoResize(textarea) {
        if (!textarea) return;
        textarea.style.height = "auto";
        textarea.style.height = `${textarea.scrollHeight}px`;
    }

    function closeActive() {
        if (submitting) return;
        if (!activeLine) return;
        const closing = activeLine;
        const form = formOf(activeLine);
        const textarea = textareaOf(activeLine);
        form?.reset();
        if (isItem(activeLine)) {
            // 수정 취소는 원래 내용으로 되돌린다.
            const content = activeLine.querySelector("[data-travel-plan-item-content]");
            if (textarea && content) textarea.value = content.textContent.trim();
            const view = activeLine.querySelector("[data-item-view]");
            if (view) view.hidden = false;
            else if (content) content.hidden = false;
            activeLine.querySelector("[data-item-edit-button]")?.setAttribute("aria-expanded", "false");
        } else if (textarea) {
            textarea.value = "";
            form?.querySelectorAll("[data-item-title-input], [data-item-note-input], [name^='details.']")
                .forEach(field => { field.value = ""; });
        }
        const type = form?.querySelector("[name=itemType]");
        const time = form?.querySelector("[name=startTime]");
        if (type) type.value = activeLine.dataset.itemType || "SCHEDULE";
        if (time) time.value = activeLine.dataset.startTime || "";
        if (form) form.hidden = true;
        if (form) {
            initialEditorStates.delete(form);
            setEditorType(form, type?.value || "SCHEDULE");
        }
        restorePortal();
        activeLine.classList.remove("is-editing");
        activeLine = null;
        if (closing.hasAttribute("data-travel-plan-draft")) {
            draftSummaries.get(closing)?.remove();
            draftSummaries.delete(closing);
            closing.remove();
        }
        window.TravelPlanDateTimePicker?.refresh(planner);
        // 자리를 놓아 다른 사람이 곧바로 편집할 수 있게 한다.
        realtime()?.releaseLock();
        notifyEditorIdle();
    }

    /** 이 줄이 가리키는 자리. 새 일정이면 DAY, 기존 일정이면 그 일정이다. */
    function spotOf(line) {
        const day = line.closest("[data-travel-plan-day-id]");
        const itemId = isItem(line) ? line.getAttribute("data-item-id") : null;
        return {
            mode: itemId ? "EDIT" : "ADD",
            dayId: day ? day.getAttribute("data-travel-plan-day-id") : null,
            itemId
        };
    }

    /**
     * 대안 칸이 가리키는 자리.
     * 새 대안이면 그 A 일정 하나에 자리가 하나뿐이다(B 인지 C 인지는 서버가 정한다).
     */
    function altSpotOf(node) {
        const day = node.closest("[data-travel-plan-day-id]");
        const item = node.closest("[data-travel-plan-item]");
        const alternativeId = node.getAttribute("data-alternative-id");
        return {
            mode: alternativeId ? "ALT_EDIT" : "ALT_ADD",
            dayId: day ? day.getAttribute("data-travel-plan-day-id") : null,
            itemId: item ? item.getAttribute("data-item-id") : null,
            alternativeId
        };
    }

    function realtime() {
        return window.travelPlanRealtime;
    }

    async function requestEditorLock(live, spot) {
        if (editorOpening) return {granted: false};
        editorOpening = true;
        planner.dataset.editorOpening = "true";
        try {
            return await live.requestLock(spot);
        } finally {
            editorOpening = false;
            delete planner.dataset.editorOpening;
        }
    }

    async function finishCurrentLine() {
        if (!activeLine) return true;
        const form = formOf(activeLine);
        syncContent(form);
        if (form.querySelector("[data-item-title-input]") && form.dataset.userEdited === "true" &&
            initialEditorStates.get(form) !== editorState(form)) {
            await save(activeLine);
            return !activeLine; // 저장 실패/입력 오류면 원래 편집기를 유지한다.
        }
        closeActive();
        return true;
    }

    /**
     * 편집기를 열기 전에 서버에서 그 자리를 받아 온다.
     * 받지 못하면 열지 않는다. "아마 됐겠지" 로 두 사람이 같은 줄을 고치지 않게 한다.
     */
    async function open(line, portal = null, created = false) {
        if (submitting || editorOpening || window.travelPlanDayTitle?.isEditing() || (creatingCard && !created)) return;
        if (activeLine === line) {
            if (portal && activePortal?.host !== portal.host) attachPortal(line, formOf(line), portal);
            return line;
        }
        let form = formOf(line);
        if (!form) return;
        if (!await finishCurrentLine()) return;
        // 이전 편집 저장이 DAY를 새로 그렸다면 최신 row/host를 다시 찾는다.
        if (!line.isConnected) {
            const fresh = isItem(line) ? planner.querySelector(`[data-travel-plan-item][data-item-id="${line.dataset.itemId}"]`) :
                planner.querySelector(`[data-travel-plan-slot][data-day-id="${line.dataset.dayId}"]`);
            if (fresh) return open(fresh, portal, created);
            return;
        }
        if (portal && !portal.host.isConnected) {
            const host = planner.querySelector(`[data-overview-editor-host="${line.dataset.itemId}"]`);
            const button = planner.querySelector(`[data-overview-item-edit="${line.dataset.itemId}"]`);
            if (!host || !button) return;
            portal = {host, button};
        }

        const spot = spotOf(line);
        const live = realtime();
        if (live) {
            if (live.isLockedByOther(spot)) return;
            closeActive();
            closeAlt();
            const result = await requestEditorLock(live, spot);
            // 그 사이 다른 곳을 열었거나 자리를 못 받았으면 그만둔다.
            if (!result.granted || activeLine || activeAlt) return;
            // 잠금 응답을 기다리는 동안 다른 참여자의 갱신으로 DAY가 교체될 수 있다.
            if (!line.isConnected) {
                const fresh = isItem(line) ? planner.querySelector(`[data-travel-plan-item][data-item-id="${line.dataset.itemId}"]`) :
                    planner.querySelector(`[data-travel-plan-slot][data-day-id="${line.dataset.dayId}"]`);
                const freshForm = fresh && formOf(fresh);
                if (!freshForm) { live.releaseLock(); return; }
                line = fresh;
                form = freshForm;
            }
            if (portal && !portal.host.isConnected) {
                const host = planner.querySelector(`[data-overview-editor-host="${line.dataset.itemId}"]`);
                const button = planner.querySelector(`[data-overview-item-edit="${line.dataset.itemId}"]`);
                if (!host || !button) { live.releaseLock(); return; }
                portal = {host, button};
            }
        } else {
            closeActive();
            closeAlt();
        }

        if (isItem(line)) {
            const content = line.querySelector("[data-travel-plan-item-content]");
            const view = line.querySelector("[data-item-view]");
            if (view) view.hidden = !line.querySelector(".tp-detail-item-shell") || !!portal;
            else if (content) content.hidden = true;
            line.querySelector("[data-item-edit-button]")?.setAttribute("aria-expanded", "true");
        }
        setEditorType(form, form.querySelector("[name=itemType]")?.value || "SCHEDULE");
        syncContent(form);
        form.dataset.userEdited = "false";
        initialEditorStates.set(form, editorState(form));
        if (portal) {
            attachPortal(line, form, portal);
        }
        form.hidden = false;
        line.classList.add("is-editing");
        activeLine = line;
        const textarea = textareaOf(line);
        if (!form.querySelector("[data-item-title-input]")) autoResize(textarea);
        focusEditor(form);
        document.dispatchEvent(new CustomEvent("travelplan:editor-opened"));
        // 열자마자 지금 값을 한 번 알려 다른 화면이 자리를 비워 두게 한다.
        live?.sendDraft({ content: textarea ? textarea.value : "" });
        return line;
    }

    function originalContentOf(line) {
        const content = line.querySelector("[data-travel-plan-item-content]");
        return content ? content.textContent.trim() : "";
    }

    /** 저장 실패 사유를 그 줄 안에 짧게 보여 준다. 입력은 그대로 둔다. */
    function showSaveError(line, message) {
        let notice = line.querySelector("[data-travel-plan-save-error]") ||
            formOf(line)?.querySelector("[data-travel-plan-save-error]");
        if (!notice) {
            notice = document.createElement("p");
            notice.className = "travel-plan-slot-error";
            notice.setAttribute("data-travel-plan-save-error", "");
            // 편집기가 닫혀 있을 때 알리는 경우도 있어, 숨겨진 폼 안에 넣지 않는다.
            const form = formOf(line);
            const host = form && !form.hidden
                ? form
                : line.querySelector(".travel-plan-line-body") || line;
            host.append(notice);
        }
        notice.textContent = message || "저장하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    function clearSaveError(line) {
        line.querySelector("[data-travel-plan-save-error]")?.remove();
        formOf(line)?.querySelector("[data-travel-plan-save-error]")?.remove();
    }

    function setSaving(form, saving) {
        form.querySelectorAll("textarea, select, input:not([type=hidden]), button")
            .forEach(field => { field.disabled = saving; });
        form.setAttribute("aria-busy", String(saving));
        if (!saving && form.querySelector("[name=itemType]")) {
            setEditorType(form, form.querySelector("[name=itemType]").value);
        }
    }

    /**
     * 기존 POST endpoint 로 그대로 저장한다. 저장 경로는 바뀌지 않는다.
     * 다만 화면이 통째로 새로 뜨지 않도록 form submit 대신 직접 보낸다.
     */
    async function save(line) {
        if (submitting) return;
        const textarea = textareaOf(line);
        const form = formOf(line);
        if (!textarea || !form) return;
        syncContent(form);

        const value = textarea.value.trim();
        // 공백만 있으면 저장하지 않는다. 수정이면 원래 내용을 되살린다.
        if (value === "") {
            if (form.querySelector("[data-item-title-input]")) {
                showSaveError(line, "제목이나 항공편·숙소 이름을 입력해 주세요.");
                focusEditor(form);
                return;
            }
            closeActive();
            return;
        }
        // 내용이 그대로면 불필요한 UPDATE 를 보내지 않는다.
        const type = form.querySelector("[name=itemType]");
        const time = form.querySelector("[name=startTime]");
        const metadataChanged = (type && type.value !== (line.dataset.itemType || "SCHEDULE")) ||
            (time && time.value !== (line.dataset.startTime || "")) ||
            (initialEditorStates.has(form) && initialEditorStates.get(form) !== editorState(form));
        if (isItem(line) && value === originalContentOf(line) && !metadataChanged) {
            closeActive();
            return;
        }

        submitting = true;
        clearSaveError(line);
        const live = realtime();
        if (!live) {
            // 스크립트로 보낼 수 없으면 지금까지처럼 폼을 그대로 보낸다.
            form.requestSubmit();
            return;
        }

        const { dayId } = spotOf(line);
        try {
            const body = new FormData(form);
            body.set("content", textarea.value);
            setSaving(form, true);
            const response = await fetch(form.action, {
                method: "POST",
                headers: { "X-Requested-With": "XMLHttpRequest" },
                body
            });
            if (!response.ok) {
                // 입력을 날리지 않는다. 사유만 알리고 그 자리에서 다시 시도할 수 있게 둔다.
                submitting = false;
                setSaving(form, false);
                showSaveError(line, (await response.text()).trim());
                focusEditor(form);
                return;
            }
        } catch (error) {
            submitting = false;
            setSaving(form, false);
            showSaveError(line, null);
            focusEditor(form);
            return;
        }

        submitting = false;
        setSaving(form, false);
        // 저장된 뒤에는 DB 내용이 기준이다. 그 DAY 를 서버에서 다시 읽어 온다.
        closeActive();
        if (dayId) await live.refreshDay(dayId);
    }

    /*
      입력칸 하나에 붙는 공통 규칙. A 일정과 대안(B/C)이 똑같이 쓴다.

      한글은 여러 번의 키 입력이 모여 한 글자가 된다(ㄱ → 겨 → 경).
      조합이 끝나기 전의 Enter 는 글자를 확정하려는 것이지 저장이 아니다.
      다만 화면에 이미 보이는 글자는 조합 중이라도 상대에게 그대로 보낸다.

      @param readDraft 지금 보낼 값을 만들어 준다(대안은 조건과 내용 두 칸을 함께 싣는다)
      @param onEnter   조합이 아닌 진짜 Enter 일 때 할 일. 없으면 Enter 를 흘려보낸다
      @param onEscape  Esc 로 취소할 때 할 일
      @param onBlur    같은 페이지 안에서 편집을 끝냈을 때 할 일. 없으면 아무것도 하지 않는다
                       (창 전체가 비활성화된 것은 편집을 끝낸 것으로 보지 않는다)
    */
    function bindEditableField(field, { readDraft, onEnter, onEscape, onBlur }) {
        let composing = false;
        let draftTimer = null;

        // 기다리던 것을 취소하고 지금 값을 곧바로 보낸다.
        function sendDraftNow() {
            window.clearTimeout(draftTimer);
            draftTimer = null;
            realtime()?.sendDraft(readDraft());
        }

        /*
          글자마다 보내지 않고 잠깐 모아 보낸다. 입력이 느껴질 만큼 길게 두지 않는다.
          값을 미리 잡아 두지 않고 보내는 순간에 읽으므로,
          늦게 발화하더라도 예전 글자로 되돌아가지 않는다.
        */
        function sendDraftSoon() {
            if (draftTimer) return;
            draftTimer = window.setTimeout(() => {
                draftTimer = null;
                realtime()?.sendDraft(readDraft());
            }, 120);
        }

        field.addEventListener("compositionstart", () => {
            composing = true;
        });
        field.addEventListener("compositionend", () => {
            composing = false;
            // 조합이 끝나 글자가 완성됐다. 지금 값을 곧바로 보낸다.
            sendDraftNow();
        });

        field.addEventListener("input", () => {
            if (field.tagName === "TEXTAREA") autoResize(field);
            /*
              조합 중에도 보낸다.
              한글은 마지막 글자가 한동안 조합 상태로 남아 있어서,
              조합이 끝날 때까지 참으면 상대 화면이 "경복" 에서 멈춰 보인다.
              브라우저가 지금 보여 주는 값을 그대로 보내고, 글자 조합은 브라우저에 맡긴다.
              (저장 여부는 아래 keydown 에서 따로 막으므로 여기서는 상관없다)
            */
            sendDraftSoon();
        });

        field.addEventListener("keydown", event => {
            if (event.key === "Escape") {
                event.preventDefault();
                onEscape();
                return;
            }
            // 조합 중의 Enter 는 글자를 확정하는 것이라 저장으로 보지 않는다.
            if (window.travelPlanIme.isComposing(event, composing)) return;
            // Enter 는 저장, Shift+Enter 는 줄바꿈.
            if (event.key === "Enter" && !event.shiftKey && onEnter) {
                event.preventDefault();
                sendDraftNow();
                onEnter();
            }
        });

        if (onBlur) {
            field.addEventListener("blur", event => {
                if (submitting || composing) return;
                /*
                  창 전체가 비활성화된 것뿐이라면 편집을 끝낸 것이 아니다.
                  다른 창·다른 탭·다른 앱으로 옮겼다고 저장하거나 자리를 놓으면
                  옆 창에서 이어 쓰려던 사람이 편집기와 작성 중 내용을 잃는다.
                  같은 페이지 안에서 다른 곳을 눌렀을 때만 편집을 끝낸 것으로 본다.
                */
                if (!document.hasFocus()) return;
                onBlur(event);
            });
        }
    }

    function handleItemDelete(event, line) {
        const button = event.submitter;
        if (!button?.hasAttribute("data-item-editor-delete")) return false;
        if (submitting || creatingCard || editorOpening || realtime()?.hasAlternativeEditing(line.dataset.itemId)) {
            event.preventDefault();
            showSaveError(line, "진행 중인 저장이나 공동 편집을 마친 뒤 삭제해 주세요.");
        } else if (!window.confirm(button.dataset.deleteConfirm || "이 일정을 삭제할까요?")) {
            event.preventDefault();
        }
        // 확인한 삭제는 버튼의 formaction으로 기존 POST를 전송한다. 일정 저장으로 바꾸지 않는다.
        return true;
    }

    function bind(line) {
        const textarea = textareaOf(line);
        if (!textarea) return;

        const form = formOf(line);
        const structured = !!form.querySelector("[data-item-title-input]");
        setEditorType(form, form.querySelector("[name=itemType]")?.value || "SCHEDULE");
        if (!structured) bindEditableField(textarea, {
            readDraft: () => ({ content: textarea.value }),
            onEnter: () => save(line),
            onEscape: () => closeActive(),
            onBlur: event => {
                if (!formOf(line)?.contains(event.relatedTarget)) save(line);
            }
        });
        if (structured) form.querySelectorAll("input:not([name=itemType]):not([name=_csrf]):not([name=version]), textarea:not([name=content])").forEach(field => {
            field.addEventListener("input", () => { form.dataset.userEdited = "true"; });
            field.addEventListener("change", () => { form.dataset.userEdited = "true"; syncContent(form); });
            bindEditableField(field, {
                readDraft: () => { syncContent(form); return {content: textarea.value}; },
                onEnter: field.tagName === "TEXTAREA" ? null : () => save(line),
                onEscape: () => closeActive()
            });
            field.addEventListener("keydown", event => {
                if (event.key === "Enter" && (event.ctrlKey || event.metaKey) && !event.isComposing) {
                    event.preventDefault(); save(line);
                }
            });
        });
        form?.addEventListener("submit", event => {
            if (handleItemDelete(event, line)) return;
            syncContent(form);
            if (!realtime()) return; // requestSubmit fallback은 원래 POST 폼으로 전송한다.
            event.preventDefault();
            save(line);
        });
        form?.querySelector("[data-item-editor-cancel]")?.addEventListener("click", event => {
            event.stopPropagation();
            closeActive();
        });
        if (!structured) form?.querySelectorAll("select, input[type=time]").forEach(field => {
            field.addEventListener("blur", event => {
                if (activeLine === line && document.hasFocus() && !form.contains(event.relatedTarget)) save(line);
            });
            field.addEventListener("keydown", event => {
                if (event.key === "Escape") { event.preventDefault(); closeActive(); }
            });
        });
    }

    /** 클릭 즉시 로컬 카드를 표시한다. 저장 전까지 실제 row는 만들지 않는다. */
    async function createDraftCard(line, type, fromOverview = false) {
        if (submitting || creatingCard || editorOpening || window.travelPlanDayTitle?.isEditing()) return;
        creatingCard = true;
        const dayId = line.dataset.dayId;
        const slot = planner.querySelector(`[data-travel-plan-slot][data-day-id="${dayId}"]`);
        if (!slot || !formOf(slot)) { creatingCard = false; return; }
        const form = formOf(slot).cloneNode(true);
        form.querySelectorAll("[id]").forEach(field => field.removeAttribute("id"));
        form.querySelectorAll("input:not([name=_csrf]), textarea").forEach(field => { field.value = ""; });
        form.querySelectorAll("[data-travel-plan-save-error], .travel-plan-slot-error").forEach(node => node.remove());
        form.querySelector("[type=submit]").textContent = "저장";
        form.hidden = false;
        const draft = document.createElement("li");
        draft.className = "travel-plan-line is-draft is-editing";
        draft.setAttribute("data-travel-plan-draft", "");
        draft.dataset.dayId = dayId;
        draft.dataset.itemType = type;
        const body = document.createElement("div");
        body.className = "travel-plan-line-body";
        const heading = document.createElement("div");
        heading.className = "tp-draft-heading";
        heading.dataset.type = type;
        const badge = document.createElement("span");
        badge.className = "tp-type-badge";
        badge.dataset.itemTypeBadge = type;
        const label = {SCHEDULE: "일정", FLIGHT: "항공", ACCOMMODATION: "숙소", TRANSPORT: "이동"}[type];
        badge.textContent = label;
        const state = document.createElement("span");
        state.className = "tp-draft-state";
        state.textContent = "새 일정 · 편집 준비 중";
        heading.append(badge, state);
        body.append(heading, form);
        draft.append(body);
        slot.before(draft);
        const dateName = type === "ACCOMMODATION" ? "details.checkInDate" : "details.departureDate";
        const date = form.querySelector(`[name="${dateName}"]`);
        if (date) date.value = slot.closest("[data-plan-date]")?.dataset.planDate || "";
        setEditorType(form, type);
        setSaving(form, true);
        window.decorateTravelPlanTypes?.(draft);
        let summary = null;
        if (fromOverview) {
            summary = document.createElement("div");
            summary.className = "tp-overview-item tp-item-card tp-draft-summary";
            summary.dataset.type = type;
            summary.append(heading.cloneNode(true));
            planner.querySelector(`[data-planner-summary="${dayId}"] [data-planner-summary-items]`)?.append(summary);
            draftSummaries.set(draft, summary);
        }
        try {
            if (!await finishCurrentLine()) { summary?.remove(); draft.remove(); return; }
            closeAlt();
            const live = realtime();
            if (live) {
                const result = await requestEditorLock(live, {mode: "ADD", dayId, itemId: null});
                if (!result.granted) throw new Error("다른 참여자가 이 날짜에 일정을 추가 중입니다.");
            }
            activeLine = draft;
            bind(draft);
            setSaving(form, false);
            state.textContent = "새 일정 · 저장 전";
            if (summary) {
                const host = document.createElement("div");
                host.className = "tp-overview-editor-host";
                summary.append(host);
                attachPortal(draft, form, {host, button: summary});
                summary.querySelector(".tp-draft-state").textContent = "새 일정 · 저장 전";
            }
            initialEditorStates.set(form, editorState(form));
            form.dataset.userEdited = "false";
            document.dispatchEvent(new CustomEvent("travelplan:editor-opened"));
            focusEditor(form);
        } catch (error) {
            summary?.remove(); draft.remove();
            const errorHost = fromOverview ? planner.querySelector(`[data-planner-summary="${dayId}"]`) : slot;
            showSaveError(errorHost || slot, error.message);
        } finally {
            creatingCard = false;
            notifyEditorIdle();
        }
    }

    function pendingDetailRow(slot, type) {
        const row = document.createElement("li");
        row.className = "travel-plan-line is-creating is-editing tp-item-enter";
        row.dataset.itemType = type;
        const shell = document.createElement("div");
        shell.className = "tp-detail-item-shell tp-item-card";
        shell.dataset.type = type;
        const controls = document.createElement("div");
        controls.className = "tp-item-order-controls";
        const grip = document.createElement("button");
        grip.type = "button"; grip.disabled = true; grip.className = "tp-item-drag-handle";
        grip.textContent = "⠿"; grip.setAttribute("aria-label", "일정 저장 중");
        const order = document.createElement("span");
        order.className = "travel-plan-line-order";
        order.textContent = String(slot.closest("[data-travel-plan-day]").querySelectorAll("[data-travel-plan-item]").length + 1).padStart(2, "0");
        controls.append(grip, order);
        const body = document.createElement("div");
        body.className = "travel-plan-line-body";
        const view = document.createElement("div");
        view.className = "tp-detail-row-view";
        const badge = document.createElement("span");
        badge.className = "tp-type-badge"; badge.dataset.itemTypeBadge = type;
        badge.textContent = {FLIGHT: "항공", ACCOMMODATION: "숙소", TRANSPORT: "이동", SCHEDULE: "일정"}[type];
        const kind = document.createElement("span");
        kind.className = "tp-detail-kind-label";
        kind.textContent = {FLIGHT: "탑승시간", ACCOMMODATION: "체크인", TRANSPORT: "출발", SCHEDULE: "시작시간"}[type];
        const date = document.createElement("span");
        date.className = "tp-detail-datetime"; date.textContent = "날짜 미정";
        const title = document.createElement("span");
        title.className = "tp-card-title";
        title.textContent = type === "ACCOMMODATION" ? "숙소 이름 입력하기" : type === "SCHEDULE" ? "일정 입력하기" : "구간 / 장소 입력하기";
        const state = document.createElement("span");
        state.className = "tp-detail-pending-state"; state.textContent = "저장 중";
        state.setAttribute("role", "status");
        const arrow = document.createElement("button");
        arrow.type = "button"; arrow.disabled = true; arrow.className = "tp-card-edit";
        arrow.setAttribute("aria-label", "일정 저장 중");
        view.append(badge, kind, date, title, state, arrow);
        body.append(view); shell.append(controls, body); row.append(shell);
        slot.before(row);
        window.decorateTravelPlanTypes?.(row);
        return row;
    }

    /** 상세 일정은 클릭 즉시 한 줄을 표시하고, 입력 없이도 실제 빈 row를 저장한다. */
    async function createPersistedRow(line, type) {
        if (submitting || creatingCard || editorOpening || window.travelPlanDayTitle?.isEditing()) return;
        const dayId = line.dataset.dayId;
        const slot = planner.querySelector(`[data-travel-plan-slot][data-day-id="${dayId}"]`);
        if (!slot || !formOf(slot)) return;
        creatingCard = true;
        const pending = pendingDetailRow(slot, type);
        let createdId = null;
        let creationAccepted = false;
        let locked = false;
        try {
            if (!await finishCurrentLine()) { pending.remove(); return; }
            closeAlt();
            const live = realtime();
            if (live) {
                const result = await requestEditorLock(live, {mode: "ADD", dayId, itemId: null});
                if (!result.granted) throw new Error("다른 참여자가 이 날짜에 일정을 추가 중입니다.");
                locked = true;
            }
            const body = new FormData(formOf(slot));
            body.set("emptyCard", "true"); body.set("itemType", type); body.set("content", "");
            const response = await fetch(formOf(slot).action, {
                method: "POST", headers: {"X-Requested-With": "XMLHttpRequest"}, body
            });
            if (!response.ok) throw new Error((await response.text()).trim() || "일정을 추가하지 못했습니다.");
            creationAccepted = true;
            createdId = (await response.json()).itemId;
            if (!createdId) throw new Error("일정 저장 결과를 확인할 수 없습니다.");
            pending.classList.remove("is-editing");
            if (locked) { live.releaseLock(); locked = false; }
            if (!live) { window.location.reload(); return; }
            await live.refreshDay(dayId);
            let actual = planner.querySelector(`[data-travel-plan-item][data-item-id="${createdId}"]`);
            if (!actual) {
                await live.refreshDay(dayId);
                actual = planner.querySelector(`[data-travel-plan-item][data-item-id="${createdId}"]`);
            }
            if (!actual) throw new Error("일정은 저장됐습니다. 새로고침해서 확인해 주세요.");
            pending.remove();
            actual.classList.add("tp-item-enter");
        } catch (error) {
            if (!creationAccepted) pending.remove();
            else pending.classList.remove("is-editing");
            const host = creationAccepted ? pending : planner.querySelector(`[data-travel-plan-slot][data-day-id="${dayId}"]`) || slot;
            showSaveError(host, creationAccepted ? (createdId ? "일정은 저장됐습니다. 새로고침해서 확인해 주세요." : "일정 저장 요청이 처리됐습니다. 새로고침해서 확인해 주세요.") : error.message);
            if (creationAccepted) pending.querySelector("[role=status]").textContent = createdId ? "저장 완료" : "저장 결과 확인 필요";
        } finally {
            if (locked) realtime()?.releaseLock();
            creatingCard = false;
            notifyEditorIdle();
        }
    }

    function bindLines(root) {
        // 빈 슬롯: 줄을 누르면 그 자리가 입력 상태가 된다.
        root.querySelectorAll("[data-travel-plan-slot]").forEach(line => {
            line.addEventListener("click", event => {
                if (event.target === line) createPersistedRow(line, "SCHEDULE");
            });
            line.querySelectorAll("[data-add-item-type]").forEach(button => {
                button.addEventListener("click", async event => {
                    event.stopPropagation();
                    await createPersistedRow(line, button.dataset.addItemType);
                });
            });
            bind(line);
        });

        // 기존 일정: 내용을 누르면 그 줄이 편집 상태가 된다.
        root.querySelectorAll("[data-travel-plan-item]").forEach(line => {
            line.querySelector("[data-travel-plan-item-content]")
                ?.addEventListener("click", () => open(line));
            line.querySelector("[data-item-edit-button]")?.addEventListener("click", () => {
                if (activeLine === line) finishCurrentLine();
                else open(line);
            });
            line.querySelector("[data-item-view]")?.addEventListener("click", event => {
                if (!event.target.closest("a, button")) open(line);
            });
            bind(line);
        });
    }

    // ── 대안(B/C) ───────────────────────────────────────────────
    // A 아래에 접힌 목록이 하나 있고, 그 안에서 각 대안이 그 자리에서 편집기가 된다.

    function altFormOf(node) {
        return node.querySelector(
            "[data-travel-plan-alt-form], [data-travel-plan-alt-new-form]");
    }

    function altViewOf(node) {
        return node.querySelector("[data-travel-plan-alt-view]");
    }

    function altConditionOf(node) {
        return altFormOf(node)?.querySelector("input[name=\"conditionLabel\"]") || null;
    }

    function altTextareaOf(node) {
        return altFormOf(node)?.querySelector("textarea") || null;
    }

    /** 지금 두 칸에 들어 있는 값. 조건과 내용은 늘 함께 보낸다. */
    function altDraftOf(node) {
        return {
            conditionLabel: altConditionOf(node)?.value || "",
            content: altTextareaOf(node)?.value || ""
        };
    }

    /** 저장돼 있던 값. 바뀐 것이 없으면 굳이 UPDATE 를 보내지 않기 위한 것이다. */
    function originalAltOf(node) {
        const view = altViewOf(node);
        return {
            conditionLabel:
                view?.querySelector(".travel-plan-alt-condition")?.textContent.trim() || "",
            content: view?.querySelector(".travel-plan-alt-content")?.textContent.trim() || ""
        };
    }

    // 편집기를 닫는다. 저장된 대안은 원래 값으로 되돌리고, 새 대안은 비운다.
    function closeAlt() {
        if (submitting) return;
        if (!activeAlt) return;
        const form = altFormOf(activeAlt);
        const view = altViewOf(activeAlt);
        if (form) {
            // 저장된 대안은 원래 값으로, 새 대안은 빈 칸으로 돌아간다.
            form.reset();
            form.hidden = true;
        }
        if (view) view.hidden = false;
        clearAltError(activeAlt);
        activeAlt.classList.remove("is-editing");
        activeAlt = null;
        // A 일정과 같은 자리를 쓴다. 놓아야 다른 사람이 그 대안을 열 수 있다.
        realtime()?.releaseLock();
        notifyEditorIdle();
    }

    /**
     * A 일정과 똑같이, 열기 전에 서버에서 그 자리를 받아 온다.
     * 새 대안 자리는 그 A 일정마다 하나뿐이다(B 인지 C 인지는 저장할 때 서버가 정한다).
     */
    async function openAlt(node) {
        if (submitting || creatingCard || editorOpening || window.travelPlanDayTitle?.isEditing()) return;
        if (activeAlt === node) return;
        if (!await finishCurrentLine()) return;
        if (!node.isConnected) {
            const itemId = node.closest("[data-travel-plan-item]")?.dataset.itemId;
            const item = planner.querySelector(`[data-travel-plan-item][data-item-id="${itemId}"]`);
            const fresh = node.dataset.alternativeId ?
                item?.querySelector(`[data-alternative-id="${node.dataset.alternativeId}"]`) :
                item?.querySelector("[data-travel-plan-alt-new]");
            if (fresh) return openAlt(fresh);
            return;
        }
        const form = altFormOf(node);
        if (!form) return;

        const spot = altSpotOf(node);
        const live = realtime();
        if (live) {
            if (live.isLockedByOther(spot)) return;
            closeActive();
            closeAlt();
            const result = await requestEditorLock(live, spot);
            // 그 사이 다른 곳을 열었거나 자리를 못 받았으면 그만둔다.
            if (!result.granted || activeAlt || activeLine) return;
        } else {
            closeActive();
            closeAlt();
        }

        const view = altViewOf(node);
        if (view) view.hidden = true;
        form.hidden = false;
        node.classList.add("is-editing");
        activeAlt = node;
        const textarea = altTextareaOf(node);
        autoResize(textarea);
        const first = altConditionOf(node) || textarea;
        first?.focus();
        // 열자마자 지금 값을 한 번 알려 다른 화면이 그 자리를 비워 두게 한다.
        live?.sendDraft(altDraftOf(node));
    }

    function showAltError(node, message) {
        let notice = node.querySelector("[data-travel-plan-alt-error]");
        if (!notice) {
            notice = document.createElement("p");
            notice.className = "travel-plan-slot-error";
            notice.setAttribute("data-travel-plan-alt-error", "");
            (altFormOf(node) || node).append(notice);
        }
        notice.textContent = message || "저장하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    }

    function clearAltError(node) {
        node.querySelector("[data-travel-plan-alt-error]")?.remove();
    }

    /**
     * 기존 대안 POST endpoint 로 그대로 저장한다. 저장 경로는 바뀌지 않는다.
     * A 일정과 같이 화면이 통째로 새로 뜨지 않도록 form submit 대신 직접 보낸다.
     */
    async function saveAlt(node) {
        if (submitting) return;
        const form = altFormOf(node);
        const textarea = altTextareaOf(node);
        if (!form || !textarea) return;

        const draft = altDraftOf(node);
        // 내용이 비어 있으면 저장하지 않는다. 조건만 적힌 대안은 두지 않는다.
        if (draft.content.trim() === "") {
            closeAlt();
            return;
        }
        const isExisting = node.hasAttribute("data-alternative-id");
        if (isExisting) {
            const original = originalAltOf(node);
            if (draft.content.trim() === original.content
                    && draft.conditionLabel.trim() === original.conditionLabel) {
                closeAlt();
                return;
            }
        }

        submitting = true;
        clearAltError(node);
        const live = realtime();
        if (!live) {
            // 스크립트로 보낼 수 없으면 지금까지처럼 폼을 그대로 보낸다.
            form.requestSubmit();
            return;
        }

        const { dayId } = altSpotOf(node);
        try {
            const body = new FormData(form);
            body.set("conditionLabel", draft.conditionLabel);
            body.set("content", draft.content);
            setSaving(form, true);
            const response = await fetch(form.action, {
                method: "POST",
                headers: { "X-Requested-With": "XMLHttpRequest" },
                body
            });
            if (!response.ok) {
                // 입력을 날리지 않는다. 사유만 알리고 그 자리에서 다시 시도할 수 있게 둔다.
                submitting = false;
                setSaving(form, false);
                showAltError(node, (await response.text()).trim());
                textarea.focus();
                return;
            }
        } catch (error) {
            submitting = false;
            setSaving(form, false);
            showAltError(node, null);
            textarea.focus();
            return;
        }

        submitting = false;
        setSaving(form, false);
        // 저장된 뒤에는 DB 내용이 기준이다. 그 DAY 를 서버에서 다시 읽어 온다.
        closeAlt();
        if (dayId) live.refreshDay(dayId);
    }

    function listOf(line) {
        return line.querySelector("[data-travel-plan-alt-list]");
    }

    function toggleOf(line) {
        return line.querySelector("[data-travel-plan-alt-toggle]");
    }

    function expand(line, shouldOpen) {
        if (submitting) return;
        const list = listOf(line);
        if (!list) return;
        list.hidden = !shouldOpen;
        toggleOf(line)?.setAttribute("aria-expanded", String(shouldOpen));
        // 접으면 그 안에서 열려 있던 편집기도 함께 닫는다.
        if (!shouldOpen) closeAlt();
    }

    function bindAlternatives(root) {
    root.querySelectorAll("[data-travel-plan-item]").forEach(line => {
        toggleOf(line)?.addEventListener("click", () => {
            const list = listOf(line);
            expand(line, list ? list.hidden : false);
        });

        // ⋯ 메뉴의 "대안 추가" 는 목록을 펼치고 새 입력만 열어 준다.
        line.querySelector("[data-travel-plan-alt-add]")?.addEventListener("click", () => {
            closeMenus(null);
            expand(line, true);
            const slot = line.querySelector("[data-travel-plan-alt-new]");
            if (slot) openAlt(slot);
        });

        // 저장된 B/C 와 아직 비어 있는 새 대안 칸이 같은 규칙을 쓴다.
        const slots = "[data-travel-plan-alt], [data-travel-plan-alt-new]";
        line.querySelectorAll(slots).forEach(node => {
            altViewOf(node)?.addEventListener("click", () => openAlt(node));
            node.querySelector("[data-travel-plan-alt-cancel]")
                ?.addEventListener("click", () => closeAlt());

            const form = altFormOf(node);
            if (!form) return;
            // 저장 버튼도 화면을 새로 띄우지 않고 같은 경로로 보낸다.
            form.addEventListener("submit", event => {
                if (!realtime()) return;
                  event.preventDefault();
                saveAlt(node);
            });

            /*
              조건 칸과 내용 칸이 A 일정과 똑같은 입력 규칙을 쓴다.
              (한글 조합 처리도 여기 한 곳에만 있다)
              어느 칸을 치든 두 칸의 지금 값을 함께 보내,
              상대 화면이 조건과 내용을 같은 시점 값으로 본다.
            */
            [altConditionOf(node), altTextareaOf(node)].forEach(field => {
                if (!field) return;
                bindEditableField(field, {
                    readDraft: () => altDraftOf(node),
                    onEnter: () => saveAlt(node),
                    onEscape: () => closeAlt()
                });
            });
        });
    });
    }

    /*
      누군가 그 A 밑의 대안을 편집하는 중이면 A 를 지우거나 옮기지 않는다.
      지우면 상대가 쓰던 대안이 통째로 사라지고, 옮기면 그 줄이 다른 DAY 로 가 버린다.
      (표시는 실시간 쪽이 붙여 준다)
    */
    function blockWhileAlternativeEditing(root) {
        root.querySelectorAll("[data-travel-plan-menu-list] form").forEach(form => {
            form.addEventListener("submit", event => {
                const line = form.closest("[data-travel-plan-item]");
                if (!line) return;
                if (creatingCard || editorOpening || window.travelPlanDayTitle?.isEditing()) {
                    event.preventDefault();
                    showSaveError(line, "현재 편집이나 일정 추가를 마친 뒤 다시 시도해 주세요.");
                    return;
                }
                const itemId = line.getAttribute("data-item-id");
                const editing = line.classList.contains("is-alt-editing")
                    || realtime()?.hasAlternativeEditing(itemId);
                if (!editing) return;
                event.preventDefault();
                showSaveError(line, "다른 참여자가 이 일정의 대안을 편집 중입니다.");
            });
        });
    }

    // ⋯ 메뉴는 한 번에 하나만 열어 둔다.
    function closeMenus(except) {
        planner.querySelectorAll("[data-travel-plan-menu-list]").forEach(list => {
            if (list === except) return;
            list.hidden = true;
            list.parentElement?.querySelector("[data-travel-plan-menu-button]")
                ?.setAttribute("aria-expanded", "false");
        });
    }

    function bindItemMenus(root) {
        root.querySelectorAll("[data-travel-plan-menu-list]").forEach(list => {
            list.addEventListener("keydown", event => {
                if (event.key !== "Escape") return;
                event.preventDefault();
                event.stopPropagation();
                const button = list.parentElement?.querySelector("[data-travel-plan-menu-button]");
                closeMenus(null);
                button?.focus();
            });
        });
        root.querySelectorAll("[data-travel-plan-menu-button]").forEach(button => {
            button.addEventListener("click", event => {
                if (submitting || creatingCard || editorOpening || window.travelPlanDayTitle?.isEditing()) return;
                event.stopPropagation();
                const list = button.parentElement?.querySelector("[data-travel-plan-menu-list]");
                if (!list) return;
                const willOpen = list.hidden;
                // 편집 중이던 입력칸과 메뉴가 함께 열려 있지 않게 한다.
                if (willOpen) {
                    closeActive();
                    closeAlt();
                    const line = button.closest("[data-travel-plan-item]");
                    if (line) clearSaveError(line);
                }
                closeMenus(list);
                list.hidden = !willOpen;
                button.setAttribute("aria-expanded", String(willOpen));
            });
        });
    }

    /*
      DAY 구역 안의 동작을 한 번에 붙인다.
      실시간 갱신으로 DAY markup 이 통째로 바뀌면 그 자리의 요소는 전부 새것이라
      갈아 끼운 부분에만 다시 붙여 준다(이미 붙어 있는 다른 DAY 는 건드리지 않는다).
    */
    function bindScheduleRoot(root) {
        bindLines(root);
        bindAlternatives(root);
        bindItemMenus(root);
        blockWhileAlternativeEditing(root);
        root.querySelectorAll("[data-day-collapse]").forEach(button => {
            const day = button.closest("[data-travel-plan-day]");
            const lines = day?.querySelector(".travel-plan-lines");
            if (lines && collapsedDays.has(day.dataset.dayId)) {
                lines.hidden = true;
                button.setAttribute("aria-expanded", "false");
                button.textContent = "펼치기";
            }
            button.addEventListener("click", () => {
                // 작성 중인 폼을 접어 입력을 잃지 않게 한다.
                if (day?.querySelector(".is-editing")) return;
                if (!lines) return;
                lines.hidden = !lines.hidden;
                if (lines.hidden) collapsedDays.add(day.dataset.dayId);
                else collapsedDays.delete(day.dataset.dayId);
                button.setAttribute("aria-expanded", String(!lines.hidden));
                button.textContent = lines.hidden ? "펼치기" : "접기";
            });
        });
    }

    bindScheduleRoot(planner);
    planner.addEventListener("click", event => {
        const overviewAdd = event.target.closest("[data-overview-add-type]");
        if (overviewAdd) {
            event.preventDefault();
            const slot = planner.querySelector(`[data-travel-plan-slot][data-day-id="${overviewAdd.dataset.dayId}"]`);
            if (slot) createDraftCard(slot, overviewAdd.dataset.overviewAddType, true);
            return;
        }
        const overviewEdit = event.target.closest("[data-overview-item-edit]");
        if (overviewEdit) {
            event.preventDefault();
            const id = overviewEdit.dataset.overviewItemEdit;
            const line = planner.querySelector(`[data-travel-plan-item][data-item-id="${id}"]`);
            const host = overviewEdit.closest(".tp-overview-item")?.querySelector("[data-overview-editor-host]");
            if (!line || !host || submitting) return;
            if (activePortal?.line === line) { save(line); return; }
            open(line, {host, button: overviewEdit});
            return;
        }
        const link = event.target.closest('a[href^="#day-"]');
        if (!link) return;
        const day = document.getElementById(link.getAttribute("href").slice(1));
        const lines = day?.querySelector(".travel-plan-lines");
        if (!lines) return;
        lines.hidden = false;
        collapsedDays.delete(day.dataset.dayId);
        const button = day.querySelector("[data-day-collapse]");
        button?.setAttribute("aria-expanded", "true");
        if (button) button.textContent = "접기";
    });
    document.addEventListener("travelplan:schedule-updated", event => {
        const root = event.detail?.root;
        if (root) bindScheduleRoot(root);
    });

    document.addEventListener("click", () => closeMenus(null));

    // ── 상단 보조 popover (참여자 / 초대) ───────────────────────
    // 플래너 종이 바깥의 상단 줄에 있으므로 document 에서 찾는다.
    const popovers = [];

    function closePopovers(except) {
        popovers.forEach(popover => {
            if (popover !== except) popover.open(false);
        });
    }

    // 클릭으로 열고, 바깥 클릭과 Esc 로 닫는다. 한 번에 하나만 열어 둔다.
    function registerPopover(rootName, toggleName, panelName) {
        const root = document.querySelector(`[${rootName}]`);
        if (!root) return null;
        const toggle = root.querySelector(`[${toggleName}]`);
        const panel = root.querySelector(`[${panelName}]`);
        if (!panel) return null;

        const popover = {
            root,
            panel,
            open(shouldOpen) {
                panel.hidden = !shouldOpen;
                toggle?.setAttribute("aria-expanded", String(shouldOpen));
            }
        };
        toggle?.addEventListener("click", event => {
            event.stopPropagation();
            const willOpen = panel.hidden;
            closePopovers(popover);
            popover.open(willOpen);
        });
        // 패널 안을 눌렀다고 닫히지 않게 한다.
        panel.addEventListener("click", event => event.stopPropagation());
        // 화면에서 사라진 예전 popover 는 들고 있지 않는다(방장이 바뀌면 갈린다).
        for (let index = popovers.length - 1; index >= 0; index--) {
            if (!popovers[index].root.isConnected) popovers.splice(index, 1);
        }
        popovers.push(popover);
        return popover;
    }

    registerPopover(
        "data-travel-plan-members",
        "data-travel-plan-members-toggle",
        "data-travel-plan-members-panel");

    /*
      참여자 줄의 ⋯ (OWNER 에게만 렌더링된다). 한 번에 하나만 열어 둔다.

      참여자 목록은 명단이 바뀔 때 통째로 새로 그려지므로 줄마다 동작을 붙이지 않는다.
      (붙여 두면 새로 그린 뒤 ⋯ 가 눌리지 않는다)
      패널에 한 번만 붙여 두고 눌린 곳을 그때 찾는다.
    */
    function closeMemberMenus(except) {
        document.querySelectorAll("[data-travel-plan-member-menu]").forEach(menu => {
            if (menu === except) return;
            const list = menu.querySelector("[data-travel-plan-member-menu-list]");
            if (list) list.hidden = true;
            menu.querySelector("[data-travel-plan-member-menu-button]")
                ?.setAttribute("aria-expanded", "false");
        });
    }

    // 참여자 popover 는 안쪽 클릭을 막아 두므로 패널 안에서도 따로 닫아 준다.
    document.querySelector("[data-travel-plan-members-panel]")
        ?.addEventListener("click", event => {
            const button = event.target.closest("[data-travel-plan-member-menu-button]");
            if (!button) {
                closeMemberMenus(null);
                return;
            }
            const menu = button.closest("[data-travel-plan-member-menu]");
            const list = menu?.querySelector("[data-travel-plan-member-menu-list]");
            if (!list) return;

            // 참여자 popover 자체가 닫히지 않게 여기서 멈춘다.
            event.stopPropagation();
            const willOpen = list.hidden;
            closeMemberMenus(menu);
            list.hidden = !willOpen;
            button.setAttribute("aria-expanded", String(willOpen));
        });

    document.addEventListener("click", () => {
        closeMemberMenus(null);
        closePopovers(null);
    });
    document.addEventListener("keydown", event => {
        if (event.key !== "Escape") return;
        closeMemberMenus(null);
        closePopovers(null);
    });

    /*
      초대는 방장에게만 있고, 방장은 바뀔 수 있다.
      그 자리가 통째로 갈리면 예전 요소에 붙여 둔 동작은 함께 사라지므로
      새 markup 을 다시 찾아 붙인다. 방장이 아니면 붙일 것이 없다.
    */
    function bindInvite() {
        const invite = registerPopover(
            "data-travel-plan-invite",
            "data-travel-plan-invite-toggle",
            "data-travel-plan-invite-panel");
        if (!invite) return;

        const url = invite.root.querySelector("[data-travel-plan-invite-url]");
        const copy = invite.root.querySelector("[data-travel-plan-invite-copy]");

        copy?.addEventListener("click", () => {
            if (!url) return;
            // 복사가 막혀 있어도 사용자가 직접 고를 수 있게 먼저 선택해 둔다.
            url.select();
            url.setSelectionRange(0, url.value.length);
            navigator.clipboard?.writeText(url.value)
                .then(() => { copy.textContent = "복사됨"; })
                .catch(() => { copy.textContent = "직접 복사해 주세요"; });
        });

        // 방금 발급했을 때만 저절로 펼친다.
        // 링크는 새로고침해도 계속 볼 수 있으므로 그 뒤에는 열어 두지 않는다.
        if (invite.root.querySelector("[data-travel-plan-invite-issued]")) {
            closePopovers(invite);
            invite.open(true);
        }
    }

    bindInvite();
    // 방장을 넘겨받았다. 그 자리가 갈렸으니 새 markup 에 다시 붙인다.
    document.addEventListener("travelplan:owner-actions-updated", () => bindInvite());

    // 저장에 실패해 서버가 열어 둔 슬롯이 있으면 그 자리에서 이어 쓴다.
    const reopened = planner.querySelector(
        "[data-travel-plan-slot]:has([data-travel-plan-slot-form]:not([hidden]))");
    if (reopened) {
        activeLine = reopened;
        reopened.classList.add("is-editing");
        const textarea = textareaOf(reopened);
        const form = formOf(reopened);
        const title = form.querySelector("[data-item-title-input]");
        const note = form.querySelector("[data-item-note-input]");
        if (title && textarea) {
            const [first, ...rest] = textarea.value.split(/\r?\n/);
            title.value = first || "";
            if (note) note.value = rest.join("\n");
        }
        setEditorType(form, form.querySelector("[name=itemType]")?.value || "SCHEDULE");
        syncContent(form);
        initialEditorStates.set(form, editorState(form));
        focusEditor(form);
    }
});
