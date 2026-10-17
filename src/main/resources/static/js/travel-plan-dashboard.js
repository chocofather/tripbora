/* 서버에 저장하는 공동 준비 목록, 공유 메모, 원화 경비. 입력 중인 초안은 새 응답으로 덮지 않는다. */
document.addEventListener("DOMContentLoaded", () => {
    const preparation = document.querySelector("[data-dashboard-preparation]");
    if (!preparation) return;
    const planner = preparation.closest("[data-plan-id], [data-dashboard-plan-id]");
    if (!planner) return;
    const expensesSection = planner.querySelector("[data-dashboard-expenses]");
    const readonly = planner.hasAttribute("data-dashboard-readonly");
    const status = preparation.querySelector("[data-dashboard-status]");
    const refresh = preparation.querySelector("[data-dashboard-refresh]");
    const checklist = preparation.querySelector("[data-checklist-list]");
    const checklistAdd = preparation.querySelector("[data-checklist-add]");
    const noticeForm = preparation.querySelector("[data-notice-form]");
    const noticeInput = noticeForm.elements.content;
    const noticeView = preparation.querySelector("[data-notice-view]");
    const noticeEdit = preparation.querySelector("[data-notice-edit]");
    const expenseOpen = expensesSection?.querySelector("[data-expense-add-open]");
    const expenseAdd = expensesSection?.querySelector("[data-expense-add]");
    const base = `/travel-plans/${encodeURIComponent(planner.dataset.planId || planner.dataset.dashboardPlanId)}/dashboard`;
    let ready = false;
    let busy = false;
    let conflict = false;
    let noticeVersion = 0;
    let noticeSaved = "";
    let noticeDirty = false;
    let noticeLatest = {content: "", version: 0};
    let pendingRefresh = false;
    let refreshTimer = null;
    const drafts = new Map();

    function message(text, error = false) {
        status.textContent = text;
        status.classList.toggle("is-error", error);
    }

    function controls() {
        [preparation, expensesSection].filter(Boolean).forEach(section => {
            section.querySelectorAll("button, input, textarea").forEach(input => {
                if (input === refresh) input.disabled = busy;
                else if (input.matches("button, [type=checkbox]")) input.disabled = readonly || !ready || busy || conflict;
                else input.disabled = readonly || !ready;
            });
        });
        preparation.setAttribute("aria-busy", String(busy));
        if (readonly) {
            checklistAdd.hidden = true;
            noticeEdit.hidden = true;
            noticeForm.hidden = true;
            if (expenseOpen) expenseOpen.hidden = true;
            if (expenseAdd) expenseAdd.hidden = true;
        }
    }

    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text !== undefined) node.textContent = text;
        return node;
    }

    function action(label, handler) {
        const button = element("button", "tp-dashboard-text-button", label);
        button.type = "button";
        button.addEventListener("click", handler);
        return button;
    }

    function checklistEditing(draft, editing) {
        draft.editing = editing;
        draft.form.hidden = !editing;
        draft.view.hidden = editing;
        draft.row.classList.toggle("is-editing", editing);
        if (editing) draft.input.focus();
    }

    function noticeEditing(editing) {
        noticeForm.hidden = !editing;
        noticeView.hidden = editing;
        noticeEdit.hidden = editing || readonly;
        if (editing) noticeInput.focus();
    }

    function render(data) {
        const ids = new Set(data.checklist.map(item => String(item.id)));
        checklist.querySelectorAll("[data-checklist-id]").forEach(row => {
            if (!ids.has(row.dataset.checklistId)) {
                const draft = drafts.get(row.dataset.checklistId);
                if (draft?.dirty) {
                    draft.removed = true;
                    row.querySelector("[data-row-state]").textContent = "다른 참여자가 삭제한 항목이에요. 입력 내용은 복사해 새 항목으로 추가할 수 있어요.";
                    row.querySelectorAll("button, [type=checkbox]").forEach(input => input.remove());
                } else {
                    row.remove();
                    drafts.delete(row.dataset.checklistId);
                }
            }
        });
        data.checklist.forEach(item => {
            const id = String(item.id);
            let draft = drafts.get(id);
            if (!draft) {
                const row = element("li", "tp-checklist-row");
                row.dataset.checklistId = id;
                const check = document.createElement("input");
                check.type = "checkbox";
                check.setAttribute("aria-label", `${item.title} 완료`);
                const form = element("form", "tp-checklist-edit");
                form.hidden = true;
                const view = element("button", "tp-checklist-title");
                view.type = "button";
                view.addEventListener("click", () => checklistEditing(draft, true));
                const input = document.createElement("input");
                input.name = "title";
                input.maxLength = 200;
                input.required = true;
                input.setAttribute("aria-label", "준비 항목 제목");
                const save = element("button", "tp-dashboard-text-button", "완료");
                save.type = "submit";
                const state = element("span", "tp-checklist-row-state");
                state.dataset.rowState = "";
                const useLatest = action("최신 내용 사용", () => {
                    const latest = draft.latest;
                    if (!latest) return;
                    draft.dirty = false;
                    draft.version = latest.version;
                    draft.completed = latest.completed;
                    draft.saved = latest.title;
                    draft.input.value = latest.title;
                    draft.check.checked = latest.completed;
                    draft.row.classList.toggle("is-complete", latest.completed);
                    draft.state.textContent = "";
                    useLatest.hidden = true;
                    view.textContent = latest.title;
                });
                useLatest.hidden = true;
                useLatest.title = "작성 중인 내용이 최신 내용으로 바뀝니다. 필요한 초안은 먼저 복사해 주세요.";
                draft = {row, check, input, state, useLatest, form, view, editing: false, version: item.version, dirty: false};
                drafts.set(id, draft);
                input.addEventListener("input", () => { draft.dirty = input.value !== draft.saved; state.textContent = draft.dirty ? "저장 전" : ""; });
                const update = (completed, editingTitle = false) => {
                    if (draft.removed) return;
                    const title = editingTitle ? input.value.trim() : draft.saved;
                    const submittedInput = input.value;
                    return mutate(`/checklist/${id}/update`, {title, completed, version: draft.version}, data => {
                        const stored = data.checklist.find(item => String(item.id) === id);
                        // 내가 저장한 값이 확인된 응답만 다음 초안의 기준으로 삼는다.
                        if (stored?.title === title && stored.completed === completed) {
                            draft.version = stored.version;
                            draft.saved = stored.title;
                            draft.completed = stored.completed;
                            draft.check.checked = stored.completed;
                            draft.row.classList.toggle("is-complete", stored.completed);
                        }
                        draft.dirty = input.value.trim() !== draft.saved;
                        if (editingTitle && input.value === submittedInput) {
                            draft.dirty = false;
                            checklistEditing(draft, false);
                        }
                    });
                };
                check.addEventListener("change", () => {
                    const completed = check.checked;
                    check.checked = draft.completed;
                    update(completed);
                });
                form.addEventListener("submit", event => { event.preventDefault(); if (input.value.trim()) update(draft.completed, true); });
                const cancel = action("취소", () => {
                    input.value = draft.saved;
                    draft.dirty = false;
                    checklistEditing(draft, false);
                    if (draft.latest) syncChecklist(draft, draft.latest);
                });
                input.addEventListener("keydown", event => {
                    if (event.key === "Escape") { event.preventDefault(); if (!busy) cancel.click(); }
                });
                form.append(input, save, cancel);
                const remove = action("×", () => mutate(`/checklist/${id}/delete`, {version: draft.version}, () => { draft.dirty = false; }));
                remove.className = "tp-checklist-delete";
                remove.setAttribute("aria-label", "준비 항목 삭제");
                if (readonly) remove.hidden = true;
                row.append(view, check, form, remove, state, useLatest);
                checklist.append(row);
            }
            syncChecklist(draft, item);
        });
        const completedCount = data.checklist.filter(item => item.completed).length;
        preparation.querySelector("[data-checklist-count]").textContent = `${completedCount} / ${data.checklist.length}`;
        const progress = preparation.querySelector("[data-checklist-progress]");
        progress.max = Math.max(data.checklist.length, 1);
        progress.value = completedCount;
        progress.setAttribute("aria-valuetext", `${data.checklist.length}개 중 ${completedCount}개 완료`);
        preparation.querySelector("[data-checklist-empty]").hidden = data.checklist.length !== 0 || drafts.size !== 0;
        noticeLatest = {version: data.notice.version, content: data.notice.content || ""};
        if (!noticeDirty) {
            noticeVersion = noticeLatest.version;
            noticeSaved = noticeLatest.content;
            noticeInput.value = noticeSaved;
        }
        preparation.querySelector("[data-notice-state]").textContent = noticeDirty ? "저장 전" : "";
        preparation.querySelector("[data-notice-current]").hidden = !noticeDirty;
        preparation.querySelector("[data-notice-current-content]").textContent = noticeLatest.content || "저장된 메모가 없어요.";
        preparation.querySelector("[data-notice-use-latest]").hidden = !noticeDirty || noticeVersion === noticeLatest.version;
        noticeView.textContent = noticeLatest.content || "아직 공유된 메모가 없어요. 만나는 장소나 예약 정보를 남겨 보세요.";
        if (expensesSection) {
            const list = expensesSection.querySelector("[data-expense-list]");
            list.replaceChildren(...data.expenses.map(item => {
                const row = element("li", "tp-expense-row");
                const description = element("div", "tp-expense-description");
                description.append(element("strong", "", item.title));
                if (item.memo) description.append(element("p", "", item.memo));
                row.append(description, element("span", "tp-expense-amount", won(item.amount)));
                if (!readonly) {
                    const menu = element("details", "tp-dashboard-menu");
                    const toggle = element("summary", "", "⋯");
                    toggle.setAttribute("aria-label", `${item.title} 지출 메뉴`);
                    menu.append(toggle, action("삭제", () => mutate(`/expenses/${item.id}/delete`, {version: item.version})));
                    row.append(menu);
                }
                return row;
            }));
            expensesSection.querySelector("[data-expense-total]").textContent = won(data.totalAmount);
            expensesSection.querySelector("[data-expense-empty]").hidden = data.expenses.length !== 0;
        }
    }

    function syncChecklist(draft, item) {
        draft.latest = item;
        // 초안의 저장 기준 버전은 동료 변경 응답으로 승격하지 않는다.
        if (!draft.dirty) {
            draft.version = item.version;
            draft.completed = item.completed;
            draft.saved = item.title;
            draft.check.checked = item.completed;
            draft.input.value = item.title;
            draft.row.classList.toggle("is-complete", item.completed);
        }
        draft.view.textContent = item.title;
        draft.check.setAttribute("aria-label", `${item.title} 완료`);
        draft.check.setAttribute("aria-label", `${item.title} 완료`);
        draft.state.textContent = draft.dirty && draft.version !== item.version
            ? `현재 공유 내용: ${item.title} · 필요한 초안은 먼저 복사해 주세요.` : "";
        draft.useLatest.hidden = !draft.dirty || draft.version === item.version;
    }

    // 합계는 서버에서 계산한 값을 사용하고 여기서는 원화 표기만 한다.
    function won(amount) {
        const [integer, fraction = ""] = String(amount).split(".");
        const digits = fraction.replace(/0+$/, "");
        return `${BigInt(integer).toLocaleString("ko-KR")}${digits ? `.${digits}` : ""}원`;
    }

    async function request(path, body) {
        const headers = {Accept: "application/json"};
        if (body !== undefined) {
            headers["Content-Type"] = "application/json";
            const token = document.querySelector('meta[name="_csrf"]')?.content;
            const header = document.querySelector('meta[name="_csrf_header"]')?.content;
            if (!token || !header) throw new Error("보안 정보를 확인할 수 없어요. 페이지를 새로 열어 주세요.");
            headers[header] = token;
        }
        const response = await fetch(`${base}${path}`, {
            method: body === undefined ? "GET" : "POST", headers, credentials: "same-origin",
            ...(body === undefined ? {} : {body: JSON.stringify(body)})
        });
        if (!response.ok) {
            const error = new Error(response.status === 409
                ? "다른 참여자의 변경이 먼저 저장됐어요. 최신 정보를 불러온 뒤 필요한 초안을 복사하고 ‘최신 내용 사용’을 눌러 다시 편집해 주세요. 입력 내용은 유지돼요."
                : response.status === 403 || response.status === 401
                    ? "접근 권한이나 로그인 상태를 확인해 주세요. 입력 내용은 유지돼요."
                    : "저장하거나 불러오지 못했어요. 입력 내용은 유지돼요. 잠시 후 다시 시도해 주세요.");
            error.conflict = response.status === 409;
            throw error;
        }
        if (!response.headers.get("content-type")?.includes("application/json")) throw new Error("로그인 상태를 확인해 주세요. 입력 내용은 유지돼요.");
        return response.json();
    }

    function queueRefresh() {
        pendingRefresh = true;
        if (busy || refreshTimer !== null) return;
        refreshTimer = window.setTimeout(() => {
            refreshTimer = null;
            if (pendingRefresh) load(true);
        }, 120);
    }

    function finishRequest() {
        busy = false;
        controls();
        if (pendingRefresh) queueRefresh();
    }

    async function load(background = false) {
        if (busy) { pendingRefresh = true; return; }
        pendingRefresh = false;
        busy = true; controls();
        if (!background) message("최신 정보를 불러오는 중이에요.");
        try {
            const data = await request("");
            render(data); ready = true;
            if (!background) conflict = false;
            if (!background || !conflict) message(background ? "공유된 변경을 반영했어요." : "최신 정보를 불러왔어요. 작성 중인 내용은 유지돼요.");
        } catch (error) { message(error.message, true); }
        finally { finishRequest(); }
    }

    async function mutate(path, body, saved) {
        if (busy || readonly || !ready || conflict) return;
        busy = true; controls(); message("저장 중이에요.");
        // 요청 중에는 기존 초안도 편집할 수 있다. 성공 응답으로 새 입력을 지우지 않는다.
        const before = new Map(Array.from(drafts, ([id, draft]) => [id, draft.input.value]));
        const noticeBefore = noticeInput.value;
        try {
            const data = await request(path, body);
            saved?.(data);
            drafts.forEach((draft, id) => { if (draft.input.value !== before.get(id)) draft.dirty = true; });
            if (noticeInput.value !== noticeBefore) noticeDirty = true;
            render(data); message("저장했어요.");
        } catch (error) { conflict = Boolean(error.conflict); message(error.message, true); }
        finally { finishRequest(); }
    }

    noticeInput.addEventListener("input", () => {
        noticeDirty = noticeInput.value !== noticeSaved;
        preparation.querySelector("[data-notice-state]").textContent = noticeDirty ? "저장 전" : "";
        preparation.querySelector("[data-notice-current]").hidden = !noticeDirty;
        preparation.querySelector("[data-notice-use-latest]").hidden = !noticeDirty || noticeVersion === noticeLatest.version;
    });
    preparation.querySelector("[data-notice-use-latest]").addEventListener("click", () => {
        noticeDirty = false;
        noticeVersion = noticeLatest.version;
        noticeSaved = noticeLatest.content;
        noticeInput.value = noticeSaved;
        preparation.querySelector("[data-notice-state]").textContent = "";
        preparation.querySelector("[data-notice-current]").hidden = true;
    });
    noticeEdit.addEventListener("click", () => noticeEditing(true));
    preparation.querySelector("[data-notice-cancel]").addEventListener("click", () => {
        noticeDirty = false;
        noticeVersion = noticeLatest.version;
        noticeSaved = noticeLatest.content;
        noticeInput.value = noticeSaved;
        noticeEditing(false);
        preparation.querySelector("[data-notice-current]").hidden = true;
        preparation.querySelector("[data-notice-state]").textContent = "";
    });
    noticeInput.addEventListener("keydown", event => {
        if (event.isComposing || event.keyCode === 229) return;
        if ((event.ctrlKey || event.metaKey) && event.key === "Enter") {
            event.preventDefault();
            if (!busy) noticeForm.requestSubmit();
        }
        if (event.key === "Escape" && !busy) {
            event.preventDefault();
            preparation.querySelector("[data-notice-cancel]").click();
        }
    });
    expenseOpen?.addEventListener("click", () => {
        expenseAdd.hidden = false;
        expenseOpen.hidden = true;
        expenseAdd.elements.title.focus();
    });
    expensesSection?.querySelector("[data-expense-add-cancel]").addEventListener("click", () => {
        expenseAdd.reset();
        expenseAdd.hidden = true;
        expenseOpen.hidden = false;
    });
    checklistAdd.addEventListener("submit", event => {
        event.preventDefault();
        const input = checklistAdd.elements.title;
        const title = input.value.trim();
        if (title) mutate("/checklist", {title}, () => {
            if (input.value.trim() === title) {
                input.value = "";
            }
        });
    });
    noticeForm.addEventListener("submit", event => {
        event.preventDefault();
        const content = noticeInput.value;
        mutate("/notice", {content, version: noticeVersion}, data => {
            if ((data.notice.content || "") === content) {
                noticeVersion = data.notice.version;
                noticeSaved = content;
            }
            noticeDirty = false;
            if (noticeInput.value === content) noticeEditing(false);
        });
    });
    expenseAdd?.addEventListener("submit", event => {
        event.preventDefault();
        const fields = expenseAdd.elements;
        const title = fields.title.value.trim();
        const amount = fields.amount.value;
        const memo = fields.memo.value;
        if (!expenseAdd.reportValidity() || !title) return;
        if (!/^(?:\d+|\d*\.\d{1,2})$/.test(amount) || Number(amount) < 0) {
            message("금액을 0 이상의 숫자로 소수점 둘째 자리까지 입력해 주세요.", true);
            return;
        }
        mutate("/expenses", {title, amount, memo}, () => {
            if (fields.title.value.trim() === title && fields.amount.value === amount && fields.memo.value === memo) {
                expenseAdd.reset();
                expenseAdd.hidden = true;
                expenseOpen.hidden = false;
            }
        });
    });
    refresh.addEventListener("click", () => load());
    if (!readonly) document.addEventListener("travelplan:dashboard-updated", queueRefresh);
    controls(); load();
});
