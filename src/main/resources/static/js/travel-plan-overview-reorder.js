(function () {
    "use strict";

    const sameIds = (actual, expected) => actual.length === expected.length && actual.every((id, at) => id === expected[at]);

    // Overview cards omit ordinary schedules. Resolve their gap in the complete
    // persisted order, so dropping after a card never jumps over a hidden row.
    function targetIndex(ids, itemId, placement, anchorId) {
        const remaining = ids.filter(id => id !== itemId);
        if (placement === "start") return 0;
        if (placement === "end") return remaining.length;
        const index = remaining.indexOf(anchorId);
        if (index < 0) throw new Error("Missing target item");
        return index + (placement === "after" ? 1 : 0);
    }

    function verifyState(actual, expectedIds, previous, increments = {}) {
        if (!sameIds(actual.ids, expectedIds)) throw new Error("Order changed concurrently");
        for (const id of expectedIds) {
            const before = previous.versions[id];
            const after = actual.versions[id];
            if (!Number.isSafeInteger(before) || !Number.isSafeInteger(after) || after !== before + (increments[id] || 0)) {
                throw new Error("Version changed concurrently");
            }
        }
        return actual;
    }

    async function persistMove(state, itemId, index, postStep) {
        let at = state.ids.indexOf(itemId);
        if (at < 0 || index < 0 || index >= state.ids.length) throw new Error("Invalid order");
        while (at !== index) {
            const next = at + (index > at ? 1 : -1);
            const neighbour = state.ids[next];
            const expected = state.ids.slice();
            [expected[at], expected[next]] = [expected[next], expected[at]];
            // The existing server swaps the neighbour through a temporary slot:
            // moved item gets +1, neighbour gets +2, other versions stay intact.
            state = verifyState(await postStep(state, next > at ? "down" : "up"), expected, state,
                {[itemId]: 1, [neighbour]: 2});
            at = next;
        }
        return state;
    }

    async function persistAcrossDays(source, target, itemId, index, transfer, postStep) {
        const moved = await transfer(source);
        verifyState(moved.source, source.ids.filter(id => id !== itemId), source);
        const previous = {versions: {...source.versions, ...target.versions}};
        const appended = verifyState(moved.target, [...target.ids, itemId], previous, {[itemId]: 1});
        return {source: moved.source, target: await persistMove(appended, itemId, index, postStep)};
    }

    function mount(planner, environment = {}) {
        const detail = environment.scope === "detail";
        const mounted = detail ? "detailReorderMounted" : "overviewReorderMounted";
        if (!planner || planner.dataset[mounted] === "true") return;
        planner.dataset[mounted] = "true";
        const doc = environment.document || document;
        const win = environment.window || window;
        const send = environment.fetch || ((...args) => fetch(...args));
        const Form = environment.FormData || FormData;
        const Parser = environment.DOMParser || DOMParser;
        const Event = environment.CustomEvent || CustomEvent;
        const sensor = environment.PointerSort || win.TravelPlanPointerSort;
        const handleSelector = detail ? "[data-item-drag-handle]" : "[data-overview-card-drag-handle]";
        const itemSelector = detail ? "[data-travel-plan-item]" : "[data-overview-item-id]";
        const itemIdOf = card => detail ? card.dataset.itemId : card.dataset.overviewItemId;
        let drag = null;
        let busy = false;
        let needsReload = false;
        const allDays = root => Array.from(root.querySelectorAll("[data-travel-plan-day]"));
        const dayOf = (root, id) => allDays(root).find(day => day.dataset.dayId === id);
        const summaryOf = id => Array.from(planner.querySelectorAll("[data-planner-summary]")).find(day => day.dataset.plannerSummary === id);
        const hostOf = id => detail ? dayOf(planner, id)?.querySelector(".travel-plan-lines") : summaryOf(id)?.querySelector("[data-planner-summary-items]");
        const rows = day => day ? Array.from(day.querySelectorAll("[data-travel-plan-item]")) : [];
        function readState(root, dayId, itemId) {
            const day = dayOf(root, dayId);
            if (!day) throw new Error("Missing updated day");
            const items = rows(day);
            const ids = items.map(row => row.dataset.itemId);
            if (new Set(ids).size !== ids.length) throw new Error("Duplicate item");
            return {ids, versions: Object.fromEntries(items.map(row => [row.dataset.itemId,
                /^\d+$/.test(row.dataset.version || "") ? Number(row.dataset.version) : NaN])),
                row: items.find(row => row.dataset.itemId === itemId)};
        }
        function blocked(dayIds, ownCard) {
            const live = win.travelPlanRealtime;
            return planner.dataset.editorOpening === "true" || win.travelPlanDayTitle?.isEditing() ||
                win.travelPlanOverviewInsert?.isBusy() || win.travelPlanOverviewInsert?.isDragging?.() ||
                (detail ? win.travelPlanOverviewReorder : win.travelPlanDetailReorder)?.isBusy() ||
                (detail ? win.travelPlanOverviewReorder : win.travelPlanDetailReorder)?.isDragging() ||
                Array.from(planner.querySelectorAll(".is-editing, .is-draft, [aria-busy=true]")).some(node => node !== ownCard) ||
                dayIds.some(dayId => {
                    const day = dayOf(planner, dayId);
                    return !day || day.querySelector(".is-remote-editing, .is-alt-editing") ||
                        live?.isLockedByOther?.({mode: "ADD", dayId, itemId: null}) || rows(day).some(row =>
                            live?.isLockedByOther?.({mode: "EDIT", dayId, itemId: row.dataset.itemId}) ||
                            live?.hasAlternativeEditing?.(row.dataset.itemId));
                });
        }
        const stop = event => { event.preventDefault(); event.stopImmediatePropagation(); };
        function clearTarget() {
            planner.querySelectorAll(".tp-overview-drop-before, .tp-overview-drop-after, .tp-overview-drop-empty").forEach(node => {
                ["tp-overview-drop-before", "tp-overview-drop-after", "tp-overview-drop-empty"].forEach(name => node.classList.remove(name));
            });
            if (drag) drag.target = null;
        }
        function mark(dayId) {
            const summary = summaryOf(dayId);
            if (summary) { summary.dataset.overviewReordering = "true"; summary.classList.add("is-overview-reordering"); }
        }
        function clearMarkers(current) {
            clearTarget();
            planner.querySelectorAll("[data-overview-reordering]").forEach(summary => {
                delete summary.dataset.overviewReordering; summary.classList.remove("is-overview-reordering");
            });
            current?.card.classList.remove("is-editing");
            if (current) dayOf(planner, current.dayId)?.classList.remove("tp-card-reordering");
            current?.card.removeAttribute("aria-busy");
            // The insertion bar also observes this shared exclusion marker.
            // Keep it through authoritative readback, including detail reorders.
            if (!busy) delete planner.dataset.overviewCardDragging;
        }
        function cancel() {
            if (busy) return;
            clearMarkers(drag); drag = null;
            doc.dispatchEvent(new Event("travelplan:schedule-updated"));
            doc.dispatchEvent(new Event("travelplan:editor-idle"));
        }
        async function post(form, extra) {
            if (!form) throw new Error("Missing movement form");
            const body = new Form(form);
            if (extra) { body.set("version", extra.version); body.set("targetDayId", extra.dayId); }
            const url = extra ? form.action.replace(/\/update$/, "/move") : form.action;
            const response = await send(url, {method: "POST", body: new URLSearchParams(body), credentials: "same-origin"});
            if (!response.ok) throw new Error("Movement rejected");
            return new Parser().parseFromString(await response.text(), "text/html");
        }
        function start(handle) {
            const card = handle.closest(itemSelector);
            const dayId = detail ? card?.closest("[data-travel-plan-day]")?.dataset.dayId : card?.closest("[data-planner-summary]")?.dataset.plannerSummary;
            if (!card || !dayId || drag || busy || needsReload || !win.travelPlanRealtime?.refreshDay || blocked([dayId])) {
                return;
            }
            const state = readState(planner, dayId, itemIdOf(card));
            if (!state.row || Object.values(state.versions).some(value => !Number.isSafeInteger(value))) return;
            drag = {card, dayId, itemId: itemIdOf(card), state, target: null};
            mark(dayId); planner.dataset.overviewCardDragging = "true";
            // is-editing is the existing realtime deferral marker. The dedicated
            // DAY class keeps its read view visible without opening an editor.
            if (detail) dayOf(planner, dayId).classList.add("tp-card-reordering");
            card.classList.add("is-editing"); card.setAttribute("aria-busy", "true");
            return card;
        }

        function locate(hit, y) {
            const summary = hit?.closest(detail ? "[data-travel-plan-day]" : "[data-planner-summary]");
            if (!summary || hit.closest("[data-overview-insert-bar]")) return null;
            const dayId = detail ? summary.dataset.dayId : summary.dataset.plannerSummary;
            const host = hostOf(dayId);
            if (!host || !host.contains(hit) || blocked([drag.dayId, dayId], drag.card)) return null;
            const card = hit.closest(itemSelector);
            if (card === drag.card) return null;
            const visible = Array.from(host.querySelectorAll(itemSelector)).filter(row => row !== drag.card);
            // Offset layout ignores in-progress FLIP transforms when finding gaps.
            const middle = row => {
                const parent = row.offsetParent;
                return parent ? parent.getBoundingClientRect().top + parent.clientTop + row.offsetTop - parent.scrollTop + row.offsetHeight / 2 :
                    row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2;
            };
            const next = visible.find(row => y < middle(row));
            return {dayId, host, anchor: next || null, placement: next ? (visible[0] === next ? "start" : "before") : "end"};
        }
        async function drop(target) {
            if (!drag || busy) return;
            if (!target) { cancel(); return; }
            const current = drag;
            const affected = [...new Set([current.dayId, target.dayId])];
            let expected;
            let failed = false;
            busy = true; mark(target.dayId); clearTarget();
            try {
                const source = readState(planner, current.dayId, current.itemId);
                verifyState(source, current.state.ids, current.state);
                const destination = current.dayId === target.dayId ? source : readState(planner, target.dayId, current.itemId);
                if (Object.values(destination.versions).some(value => !Number.isSafeInteger(value))) throw new Error("Missing target version");
                const index = targetIndex(destination.ids, current.itemId, target.placement, target.anchor && itemIdOf(target.anchor));
                const visible = Array.from(target.host.querySelectorAll(itemSelector)).filter(card => card !== current.card);
                const anchor = target.placement === "start" ? visible[0] : target.placement === "before" ? target.anchor :
                    target.placement === "after" ? visible[visible.indexOf(target.anchor) + 1] : null;
                // Freeze authoritative states above before the visual commit.
                // Subsequent steps use returned forms/versions, never this preview.
                target.host.insertBefore(current.card, anchor || (detail ? target.host.querySelector("[data-travel-plan-slot]") : null));
                const step = async (state, direction) => readState(await post(state.row?.querySelector(`form[action$="/move-${direction}"]`)), target.dayId, current.itemId);
                if (current.dayId === target.dayId) expected = {[target.dayId]: await persistMove(source, current.itemId, index, step)};
                else {
                    const moved = await persistAcrossDays(source, destination, current.itemId, index, async state => {
                        const page = await post(state.row?.querySelector('form[action$="/update"]'), {version: state.versions[current.itemId], dayId: target.dayId});
                        return {source: readState(page, current.dayId, current.itemId), target: readState(page, target.dayId, current.itemId)};
                    }, step);
                    expected = {[current.dayId]: moved.source, [target.dayId]: moved.target};
                }
            } catch (error) { failed = true; }
            finally {
                // refreshDay defers replacement while a row is editing. Remove
                // markers first but keep the global guard until readback finishes.
                clearMarkers(current); drag = null;
                const refreshed = await Promise.allSettled(affected.map(id => win.travelPlanRealtime.refreshDay(id)));
                if (refreshed.some(result => result.status === "rejected")) failed = true;
                if (expected) {
                    try { affected.forEach(id => verifyState(readState(planner, id, current.itemId), expected[id].ids, expected[id])); }
                    catch (error) { failed = true; }
                }
                if (failed) needsReload = true;
                doc.dispatchEvent(new Event("travelplan:schedule-updated"));
                win.travelPlanOverviewInsert?.restore?.();
                busy = false;
                delete planner.dataset.overviewCardDragging;
                doc.dispatchEvent(new Event("travelplan:editor-idle"));
                if (failed) {
                    win.alert("일정 이동 결과를 새로고침해서 확인해 주세요. 일부 이동이 저장되었거나 다른 참여자가 일정을 변경했을 수 있습니다.");
                    win.location?.reload?.();
                }
            }
        }
        sensor?.mount(planner, {
            handle: handleSelector,
            canStart: handle => {
                const card = handle.closest(itemSelector);
                const dayId = detail ? card?.closest("[data-travel-plan-day]")?.dataset.dayId : card?.closest("[data-planner-summary]")?.dataset.plannerSummary;
                return !!dayId && !drag && !busy && !needsReload && !!win.travelPlanRealtime?.refreshDay && !blocked([dayId]);
            },
            start, locate, drop, cancel,
            items: () => Array.from(planner.querySelectorAll(itemSelector)),
            rows: host => Array.from(host.querySelectorAll(itemSelector)),
            endAnchor: host => detail ? host.querySelector("[data-travel-plan-slot]") : null
        }, {document: doc, window: win, CustomEvent: Event});
        ["click", "submit"].forEach(type => planner.addEventListener(type, event => { if (drag || busy) stop(event); }, true));
        const api = {isBusy: () => busy || needsReload, isDragging: () => !!drag && !busy};
        if (detail) win.travelPlanDetailReorder = api; else win.travelPlanOverviewReorder = api;
        return api;
    }
    if (typeof module !== "undefined" && module.exports) module.exports = {targetIndex, verifyState, persistMove, persistAcrossDays, mount};
    else {
        window.TravelPlanCardReorder = {mount};
        document.addEventListener("DOMContentLoaded", () => mount(document.querySelector(".is-planning [data-plan-id]")));
    }
})();
