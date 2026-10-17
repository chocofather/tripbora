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

    function renderCalendar() {
        if (!calendar || !start || !end) return;
        const dates = new Map(days().map(day => [day.dataset.planDate, day.id]));
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
                if (target) {
                    cell.href = `#${target}`;
                    cell.classList.add("is-trip-date");
                    cell.setAttribute("aria-label", `${key} 상세 일정`);
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
            summary.querySelector("[data-planner-summary-count]").textContent = `${items.length}개 일정`;
            const special = Array.from(items).filter(item =>
                ["FLIGHT", "ACCOMMODATION", "TRANSPORT"].includes(item.dataset.itemType));
            const ordinaryCount = items.length - special.length;
            summary.querySelector("[data-planner-summary-content]").textContent = !items.length
                ? "아직 일정이 없어요. 첫 일정을 추가해 보세요."
                : ordinaryCount ? `일반 일정 ${ordinaryCount}개 · 상세 일정에서 확인` : "";
            const host = summary.querySelector("[data-planner-summary-items]");
            if (host && !host.querySelector("form, .tp-draft-summary")) {
                const cards = special.map(item => {
                    const row = document.createElement("div");
                    row.className = "tp-overview-item";
                    row.dataset.type = item.dataset.itemType;
                    const source = item.querySelector("[data-overview-item-view]")?.content.firstElementChild || item.querySelector("[data-item-view]");
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
                    row.append(view, editor);
                    return row;
                });
                host.replaceChildren(...cards);
                window.decorateTravelPlanTypes?.(host);
            }
            summary.classList.toggle("is-empty", items.length === 0);
        });
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
        if (!document.hidden) updateDday();
    });
});
