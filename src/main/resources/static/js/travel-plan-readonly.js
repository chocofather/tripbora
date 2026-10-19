/* 확정본의 열람 동작만 연결한다. 원본 방 조회, 잠금, 자동 저장, 실시간 연결은 시작하지 않는다. */
document.addEventListener("DOMContentLoaded", () => {
    const planner = document.querySelector("[data-plan-readonly]");
    if (!planner) return;

    planner.querySelectorAll("[data-item-readonly-fields]").forEach(form => {
        const type = form.closest("[data-travel-plan-item]").dataset.itemType;
        const route = type === "FLIGHT" || type === "TRANSPORT";
        const special = route || type === "ACCOMMODATION";
        form.querySelectorAll("[data-item-type-fields]").forEach(group => {
            const key = group.dataset.itemTypeFields;
            group.hidden = !(key === (special ? type : "GENERAL") || (key === "ROUTE" && route) ||
                (key === "SPECIAL" && special));
        });
        form.querySelectorAll("input, textarea").forEach(input => { input.readOnly = true; });
        form.querySelectorAll("[data-datetime-picker]").forEach(button => {
            const value = name => name ? form.elements.namedItem(name)?.value || "" : "";
            const display = document.createElement("span");
            display.className = button.className;
            display.textContent = [value(button.dataset.dateField), value(button.dataset.timeField)].filter(Boolean).join(" ") || "미정";
            button.replaceWith(display);
        });
        for (const [name, title] of [["details.address", "주소"], ["details.bookingMemo", "예약 메모"]]) {
            const input = form.elements.namedItem(name);
            if (!input?.value) continue;
            const label = document.createElement("label");
            label.className = "tp-editor-note";
            label.append(document.createTextNode(title));
            const value = document.createElement("textarea");
            value.rows = 2;
            value.readOnly = true;
            value.value = input.value;
            label.append(value);
            form.insertBefore(label, form.querySelector(".tp-item-editor-actions"));
        }
    });

    // 버튼을 직접 눌러 펼치거나 overview에서 같은 일정의 상세로 이동한다.
    function toggleItem(item, open) {
        const fields = item?.querySelector("[data-item-readonly-fields]");
        if (!fields) return;
        fields.hidden = !open;
        item.querySelector("[data-item-readonly-toggle]")?.setAttribute("aria-expanded", String(open));
        document.dispatchEvent(new CustomEvent("travelplan:overview-layout"));
    }

    planner.addEventListener("submit", event => event.preventDefault());
    planner.addEventListener("click", event => {
        const toggle = event.target.closest("[data-item-readonly-toggle]");
        if (toggle) {
            const row = toggle.closest("[data-travel-plan-item]");
            if (row) toggleItem(row, row.querySelector("[data-item-readonly-fields]").hidden);
            else {
                const id = toggle.closest("[data-overview-item-id]")?.dataset.overviewItemId;
                const item = Array.from(planner.querySelectorAll("[data-travel-plan-item]")).find(row => row.dataset.itemId === id);
                if (item) { toggleItem(item, true); item.scrollIntoView({block: "center"}); }
            }
        }
        const collapse = event.target.closest("[data-day-collapse]");
        if (collapse) {
            const lines = document.getElementById(collapse.getAttribute("aria-controls"));
            lines.hidden = !lines.hidden;
            collapse.setAttribute("aria-expanded", String(!lines.hidden));
            collapse.textContent = lines.hidden ? "펼치기" : "접기";
        }
        const alternative = event.target.closest("[data-travel-plan-alt-toggle]");
        if (alternative) {
            const list = alternative.closest("[data-travel-plan-alt-block]").querySelector("[data-travel-plan-alt-list]");
            list.hidden = !list.hidden;
            alternative.setAttribute("aria-expanded", String(!list.hidden));
        }
    });
});
