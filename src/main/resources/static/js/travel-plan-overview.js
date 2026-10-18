/* 저장된 일정 DOM으로 날짜별 주요 카드를 만든다. 편집기는 스케줄러의 기존 폼 하나를 재사용한다. */
document.addEventListener("DOMContentLoaded", () => {
    const planner = document.querySelector(".is-planning [data-plan-id]");
    if (!planner) return;

    const days = () => Array.from(planner.querySelectorAll("[data-travel-plan-day]"));
    const parseDate = value => new Date(`${value}T00:00:00Z`);
    const dateKey = date => date.toISOString().slice(0, 10);
    const start = planner.dataset.tripStart;
    const end = planner.dataset.tripEnd;
    const calendar = planner.querySelector("[data-planner-calendar]");
    const summaries = planner.querySelector("[data-planner-summaries]");
    const observedTimelineNodes = new Set();
    let timelineFrame = null;
    const timelineResize = window.ResizeObserver ? new window.ResizeObserver(scheduleTimeline) : null;

    function scheduleTimeline() {
        if (timelineFrame !== null) return;
        timelineFrame = window.requestAnimationFrame(() => { timelineFrame = null; refreshTimelines(); });
    }

    // Connect only adjacent card centres, never a DAY boundary or the insertion bar.
    function refreshTimelines() {
        const currentNodes = new Set();
        summaries?.querySelectorAll("[data-planner-summary-items]").forEach(host => {
            const cards = Array.from(host.querySelectorAll(".tp-overview-item"));
            currentNodes.add(host);
            cards.forEach((card, index) => {
                currentNodes.add(card);
                let line = Array.from(card.children).find(child => child.classList.contains("tp-overview-timeline-connector"));
                const shell = card.querySelector(".tp-detail-item-shell");
                const nextShell = cards[index + 1]?.querySelector(".tp-detail-item-shell");
                if (!shell || !nextShell) { line?.remove(); return; }
                if (!line) {
                    line = document.createElement("span");
                    line.className = "tp-overview-timeline-connector";
                    line.setAttribute("aria-hidden", "true");
                    card.append(line);
                }
                const bounds = shell.getBoundingClientRect();
                const next = nextShell.getBoundingClientRect();
                const centre = bounds.top + bounds.height / 2;
                line.style.top = `${centre - card.getBoundingClientRect().top}px`;
                line.style.height = `${Math.max(0, next.top + next.height / 2 - centre)}px`;
            });
        });
        observedTimelineNodes.forEach(node => {
            if (!currentNodes.has(node)) { timelineResize?.unobserve(node); observedTimelineNodes.delete(node); }
        });
        currentNodes.forEach(node => {
            if (!observedTimelineNodes.has(node)) { timelineResize?.observe(node); observedTimelineNodes.add(node); }
        });
    }
    if (summaries && window.MutationObserver) {
        new window.MutationObserver(scheduleTimeline).observe(summaries, {childList: true, subtree: true});
    }
    window.addEventListener("resize", scheduleTimeline);
    document.addEventListener("travelplan:overview-layout", scheduleTimeline);

    function renderCalendar() {
        if (!calendar || !start || !end) return;
        const dates = new Map(days().map(day => [day.dataset.planDate, day.id]));
        const now = new Date();
        const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
        const cursor = parseDate(start);
        const last = parseDate(end);
        if (!Number.isFinite(cursor.getTime()) || !Number.isFinite(last.getTime())) return;
        cursor.setUTCDate(1);
        const months = document.createDocumentFragment();
        while (cursor <= last) {
            const month = document.createElement("section");
            month.className = "tp-planner-month";
            const title = document.createElement("h3");
            title.textContent = `${cursor.getUTCFullYear()}년 ${cursor.getUTCMonth() + 1}월`;
            month.append(title);
            const grid = document.createElement("div");
            grid.className = "tp-planner-month-grid";
            ["일", "월", "화", "수", "목", "금", "토"].forEach(label => {
                const weekday = document.createElement("span");
                weekday.className = "tp-planner-weekday";
                weekday.textContent = label;
                grid.append(weekday);
            });
            for (let i = 0; i < cursor.getUTCDay(); i++) {
                const blank = document.createElement("span");
                blank.setAttribute("aria-hidden", "true");
                grid.append(blank);
            }
            const count = new Date(Date.UTC(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, 0)).getUTCDate();
            for (let n = 1; n <= count; n++) {
                const date = new Date(Date.UTC(cursor.getUTCFullYear(), cursor.getUTCMonth(), n));
                const key = dateKey(date);
                const target = dates.get(key);
                const cell = document.createElement(target ? "a" : "span");
                cell.className = "tp-planner-date";
                cell.textContent = String(n);
                cell.setAttribute("aria-label", key);
                if (target) {
                    cell.href = `#${target}`;
                    cell.classList.add("is-trip-date");
                    cell.setAttribute("aria-label", `${key} 상세 일정`);
                }
                if (key === today) {
                    cell.classList.add("is-today");
                    cell.setAttribute("aria-current", "date");
                    cell.setAttribute("aria-label", `${key} 오늘${target ? ", 여행 기간 · 상세 일정" : ""}`);
                }
                grid.append(cell);
            }
            month.append(grid);
            months.append(month);
            cursor.setUTCMonth(cursor.getUTCMonth() + 1);
        }
        calendar.replaceChildren(months);
    }

    function updateSummaries() {
        days().forEach(day => {
            const summary = planner.querySelector(`[data-planner-summary="${day.dataset.dayId}"]`);
            if (!summary) return;
            const items = day.querySelectorAll("[data-travel-plan-item]");
            summary.dataset.itemCount = String(items.length);
            const special = Array.from(items).filter(item =>
                ["FLIGHT", "ACCOMMODATION", "TRANSPORT"].includes(item.dataset.itemType));
            const ordinaryCount = items.length - special.length;
            summary.dataset.ordinaryCount = String(ordinaryCount);
            const host = summary.querySelector("[data-planner-summary-items]");
            if (host && !window.travelPlanOverviewInsert?.isDragging() && summary.dataset.overviewReordering !== "true" && !host.querySelector("form, .tp-draft-summary, .tp-overview-pending")) {
                // The first overview render precedes the insertion module's
                // DOMContentLoaded callback. Keep its server-rendered node so
                // that later callback can mount the same, single control.
                const initialBar = !window.travelPlanOverviewInsert ? host.querySelector("[data-overview-insert-bar]") : null;
                window.travelPlanOverviewInsert?.beforeRefresh(host);
                const cards = special.map(item => {
                    const row = document.createElement("div");
                    row.className = "tp-overview-item";
                    row.dataset.type = item.dataset.itemType;
                    row.dataset.overviewItemId = item.dataset.itemId;
                    const source = item.querySelector("[data-item-view]");
                    const view = source?.cloneNode(true);
                    if (!view) return row;
                    view.hidden = false;
                    view.removeAttribute("data-item-view");
                    view.querySelector("[data-travel-plan-item-content]")?.remove();
                    const edit = view.querySelector("[data-item-edit-button]");
                    if (edit) {
                        edit.removeAttribute("data-item-edit-button");
                        edit.dataset.overviewItemEdit = item.dataset.itemId;
                        edit.setAttribute("aria-expanded", "false");
                        edit.setAttribute("aria-label", `${item.dataset.itemTitle || "일정"} 편집`);
                    }
                    const editor = document.createElement("div");
                    editor.className = "tp-overview-editor-host";
                    editor.dataset.overviewEditorHost = item.dataset.itemId;
                    editor.dataset.type = item.dataset.itemType;
                    const shell = document.createElement("div");
                    shell.className = "tp-detail-item-shell tp-item-card";
                    shell.dataset.type = item.dataset.itemType;
                    const body = document.createElement("div");
                    body.className = "travel-plan-line-body";
                    body.append(view);
                    const handle = item.querySelector("[data-item-drag-handle]")?.cloneNode(true);
                    if (handle) {
                        handle.removeAttribute("data-item-drag-handle");
                        handle.setAttribute("data-overview-card-drag-handle", "");
                        handle.setAttribute("aria-label", `${item.dataset.itemTitle || {FLIGHT: "항공", ACCOMMODATION: "숙소", TRANSPORT: "이동"}[item.dataset.itemType]} 순서 이동`);
                        const controls = document.createElement("div");
                        controls.className = "tp-item-order-controls";
                        controls.append(handle);
                        shell.append(controls);
                    }
                    shell.append(body);
                    row.append(shell, editor);
                    return row;
                });
                host.replaceChildren(...cards);
                if (initialBar) host.append(initialBar);
                window.decorateTravelPlanTypes?.(host);
            }
            summary.classList.toggle("is-empty", items.length === 0);
        });
        window.travelPlanOverviewInsert?.restore();
        scheduleTimeline();
    }

    function updateDday() {
        const badge = planner.querySelector("[data-planner-dday]");
        if (!badge || !start || !end) return;
        const now = new Date();
        const today = Date.UTC(now.getFullYear(), now.getMonth(), now.getDate());
        const remaining = Math.round((parseDate(start).getTime() - today) / 86400000);
        if (!Number.isFinite(remaining)) return;
        badge.textContent = remaining > 0 ? `D-${remaining}` : remaining === 0 ? "D-day" :
            today <= parseDate(end).getTime() ? "여행 중" : "여행 종료";
        badge.hidden = false;
    }

    renderCalendar();
    updateSummaries();
    updateDday();
    // 기존 모듈이 저장 후/재연결 후 fragment 교체를 알리는 이벤트를 재사용한다.
    // 입력 중 draft 및 채팅 상태 변화는 요약에 반영하지 않는다.
    document.addEventListener("travelplan:schedule-updated", updateSummaries);
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) { updateDday(); renderCalendar(); }
    });
});
