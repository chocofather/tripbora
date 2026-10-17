(function () {
    "use strict";

    // Existing endpoints exchange adjacent rows. Verify every returned page before
    // another exchange so a stale version or concurrent reorder stops this sequence.
    async function persistMove(state, itemId, targetIndex, postStep) {
        let index = state.ids.indexOf(itemId);
        if (index < 0 || targetIndex < 0 || targetIndex >= state.ids.length) throw new Error("Invalid order");
        while (index !== targetIndex) {
            const nextIndex = index + (targetIndex > index ? 1 : -1);
            const expected = state.ids.slice();
            [expected[index], expected[nextIndex]] = [expected[nextIndex], expected[index]];
            state = await postStep(state, nextIndex > index ? "down" : "up");
            if (state.ids.length !== expected.length || state.ids.some((id, at) => id !== expected[at])) {
                throw new Error("Order changed concurrently");
            }
            index = nextIndex;
        }
        return state;
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = {persistMove};
        return;
    }

    document.addEventListener("DOMContentLoaded", () => {
        const planner = document.querySelector("[data-plan-id]");
        if (!planner) return;
        let drag = null;
        const rows = day => Array.from(day.querySelectorAll("[data-travel-plan-item]"));
        const ids = day => rows(day).map(row => row.dataset.itemId);

        function clearTarget() {
            if (!drag) return;
            drag.day.querySelectorAll(".tp-drop-before, .tp-drop-after").forEach(row => {
                row.classList.remove("tp-drop-before", "tp-drop-after");
            });
            drag.placeholder.remove();
            drag.target = null;
        }

        function cleanup() {
            if (!drag) return;
            clearTarget();
            drag.ghost.remove();
            drag.row.classList.remove("is-editing", "tp-is-dragging");
            drag.day.classList.remove("tp-is-reordering");
            drag.row.removeAttribute("aria-busy");
            drag = null;
            document.dispatchEvent(new CustomEvent("travelplan:editor-idle"));
        }

        function readState(day, itemId) {
            return {ids: ids(day), row: rows(day).find(row => row.dataset.itemId === itemId)};
        }

        planner.addEventListener("dragstart", event => {
            const handle = event.target.closest("[data-item-drag-handle]");
            const row = handle?.closest("[data-travel-plan-item]");
            const day = row?.closest("[data-travel-plan-day]");
            const live = window.travelPlanRealtime;
            if (!handle || !day || !live || drag || planner.dataset.editorOpening === "true" || planner.querySelector(".is-editing, .is-draft, [aria-busy=true]") ||
                    day.querySelector(".is-remote-editing, .is-alt-editing") || rows(day).some(item =>
                        live.isLockedByOther?.({mode: "EDIT", dayId: day.dataset.dayId, itemId: item.dataset.itemId}) ||
                        live.hasAlternativeEditing?.(item.dataset.itemId))) {
                event.preventDefault();
                return;
            }
            const ghost = row.cloneNode(true);
            ghost.classList.add("tp-drag-ghost");
            ghost.style.width = `${row.getBoundingClientRect().width}px`;
            ghost.removeAttribute("id");
            ghost.querySelectorAll("[id]").forEach(node => node.removeAttribute("id"));
            ghost.setAttribute("aria-hidden", "true");
            document.body.append(ghost);
            const placeholder = document.createElement("li");
            placeholder.className = "tp-drag-placeholder";
            placeholder.setAttribute("aria-hidden", "true");
            drag = {row, day, ghost, placeholder, initial: rows(day), state: readState(day, row.dataset.itemId), target: null, saving: false};
            row.classList.add("is-editing", "tp-is-dragging");
            day.classList.add("tp-is-reordering");
            day.querySelectorAll("[data-travel-plan-menu-list]").forEach(list => { list.hidden = true; });
            day.querySelectorAll("[data-travel-plan-menu-button]").forEach(button => button.setAttribute("aria-expanded", "false"));
            event.dataTransfer.effectAllowed = "move";
            event.dataTransfer.setData("text/plain", row.dataset.itemId);
            event.dataTransfer.setDragImage(ghost, 20, 16);
        });

        planner.addEventListener("dragover", event => {
            if (!drag || drag.saving) return;
            if (drag.placeholder.contains(event.target)) {
                event.preventDefault();
                return;
            }
            const target = event.target.closest("[data-travel-plan-item]");
            if (!target || target === drag.row || target.closest("[data-travel-plan-day]") !== drag.day) {
                if (!drag.placeholder.contains(event.target)) clearTarget();
                return;
            }
            event.preventDefault();
            event.dataTransfer.dropEffect = "move";
            const bounds = target.getBoundingClientRect();
            const after = event.clientY > bounds.top + bounds.height / 2;
            clearTarget();
            target.classList.add(after ? "tp-drop-after" : "tp-drop-before");
            target.parentNode.insertBefore(drag.placeholder, after ? target.nextSibling : target);
            drag.target = {row: target, after};
        });

        planner.addEventListener("drop", async event => {
            if (!drag || drag.saving || !drag.target || event.target.closest("[data-travel-plan-day]") !== drag.day) return;
            event.preventDefault();
            const current = drag;
            const {row: target, after} = current.target;
            const desired = current.initial.filter(row => row !== current.row);
            desired.splice(desired.indexOf(target) + (after ? 1 : 0), 0, current.row);
            const targetIndex = desired.indexOf(current.row);
            clearTarget();
            if (targetIndex === current.initial.indexOf(current.row)) { cleanup(); return; }
            current.saving = true;
            current.row.setAttribute("aria-busy", "true");
            current.row.parentNode.insertBefore(current.row, after ? target.nextSibling : target);
            rows(current.day).forEach((row, index) => {
                row.querySelector(".travel-plan-line-order").textContent = String(index + 1).padStart(2, "0");
            });
            const dayId = current.day.dataset.dayId;
            let failed = false;
            try {
                await persistMove(current.state, current.row.dataset.itemId, targetIndex, async (state, direction) => {
                    const form = state.row?.querySelector(`form[action$="/move-${direction}"]`);
                    if (!form) throw new Error("Missing movement form");
                    const response = await fetch(form.action, {
                        method: "POST", body: new URLSearchParams(new FormData(form)), credentials: "same-origin"
                    });
                    if (!response.ok) throw new Error("Movement failed");
                    const page = new DOMParser().parseFromString(await response.text(), "text/html");
                    const day = page.querySelector(`[data-travel-plan-day][data-day-id="${dayId}"]`);
                    if (!day) throw new Error("Missing updated day");
                    return readState(day, current.row.dataset.itemId);
                });
            } catch (error) {
                failed = true;
            } finally {
                cleanup();
                // A partially completed adjacent sequence must always be read back.
                try {
                    await window.travelPlanRealtime.refreshDay(dayId);
                    if (current.day.isConnected) window.location.reload();
                }
                catch (error) { window.location.reload(); }
                if (failed) window.alert("일정 순서를 다시 확인해 주세요. 다른 참여자의 변경이나 연결 상태로 이동이 중단되었을 수 있습니다.");
            }
        });

        planner.addEventListener("dragend", () => { if (!drag?.saving) cleanup(); });
        document.addEventListener("keydown", event => {
            if (event.key === "Escape" && !drag?.saving) cleanup();
        });
        // Capture prevents opening editors, submitting forms or collapsing a DAY
        // while its DOM order is waiting for the server.
        ["click", "submit"].forEach(type => planner.addEventListener(type, event => {
            if (!drag) return;
            event.preventDefault();
            event.stopImmediatePropagation();
        }, true));
    });
})();
