(function () {
    "use strict";

    function mount(planner, environment = {}) {
        if (!planner || planner.dataset.overviewInsertMounted === "true") return;
        planner.dataset.overviewInsertMounted = "true";
        const doc = environment.document || document;
        const win = environment.window || window;
        const send = environment.fetch || ((...args) => fetch(...args));
        const Form = environment.FormData || FormData;
        const Event = environment.CustomEvent || CustomEvent;
        const summaries = planner.querySelector("[data-planner-summaries]");
        const bar = planner.querySelector("[data-overview-insert-bar]");
        if (!summaries || !bar) return;
        const menu = bar.querySelector("[data-overview-insert-menu]");
        let menuTargets = [];
        let busy = false;
        let drag = null;
        let pointer = null;
        const grip = bar.querySelector("[data-overview-insert-handle]");
        grip?.setAttribute("draggable", "false");
        // A lost response may already have created an item. Require a reload before
        // another add rather than silently issuing the same creation twice.
        let needsReload = false;
        const labels = {FLIGHT: "항공", ACCOMMODATION: "숙소", TRANSPORT: "이동"};
        const cards = host => Array.from(host.querySelectorAll("[data-overview-item-id]"));
        const days = () => Array.from(summaries.querySelectorAll("[data-planner-summary]"));
        const summaryOf = dayId => days().find(day => day.dataset.plannerSummary === dayId);
        const hostOf = dayId => summaryOf(dayId)?.querySelector("[data-planner-summary-items]");
        const rowsOf = dayId => {
            const day = Array.from(planner.querySelectorAll("[data-travel-plan-day]")).find(node => node.dataset.dayId === dayId);
            return day ? Array.from(day.querySelectorAll("[data-travel-plan-item]")) : [];
        };
        const blocked = () => busy || !!drag || planner.dataset.editorOpening === "true" ||
            planner.dataset.overviewCardDragging === "true" || !!win.travelPlanOverviewReorder?.isBusy() || !!win.travelPlanOverviewReorder?.isDragging() ||
            !!planner.querySelector(".is-editing, .is-draft, [aria-busy=true]") || !!win.travelPlanDayTitle?.isEditing();
        const status = (bar, message, error = false) => {
            const element = bar.querySelector("[data-overview-insert-status]");
            if (element) { element.textContent = message; element.classList.toggle("is-error", error); }
            const hint = bar.querySelector(".tp-overview-insert-hint");
            if (hint) hint.hidden = error;
        };
        const stop = event => { event.preventDefault(); event.stopImmediatePropagation(); };
        const notifyLayout = () => doc.dispatchEvent(new Event("travelplan:overview-layout"));
        const clearDrag = (restoreOriginal = false) => {
            const current = drag;
            const original = current?.original;
            drag?.bar.classList.remove("is-insert-dragging");
            const captured = pointer;
            pointer = null;
            if (current) {
                current.animations.forEach(animation => animation.cancel());
                current.placeholder.remove();
                Object.assign(bar.style, current.originalStyle.values);
                if (current.originalStyle.text === null) bar.removeAttribute("style");
                else bar.setAttribute("style", current.originalStyle.text);
            }
            summaries.querySelectorAll(".is-insert-target").forEach(host => host.classList.remove("is-insert-target"));
            planner.removeAttribute("data-overview-insert-dragging");
            drag = null;
            if (captured && grip?.hasPointerCapture?.(captured.id)) grip.releasePointerCapture(captured.id);
            if (restoreOriginal && original) {
                bar.dataset.dayId = original.dayId;
                bar.dataset.insertPlacement = original.placement;
                bar.dataset.beforeItemId = original.beforeItemId;
                bar.dataset.insertDefault = original.isDefault;
                restore();
            }
            if (current) notifyLayout();
        };

        function closeMenu() {
            if (menu) menu.hidden = true;
            bar.querySelector("[data-overview-insert-location]")?.setAttribute("aria-expanded", "false");
        }

        function restore() {
            if (drag) return;
            const availableDays = days();
            const last = availableDays[availableDays.length - 1];
            if (bar.dataset.insertDefault === "true" && last) {
                bar.dataset.dayId = last.dataset.plannerSummary;
                bar.dataset.insertPlacement = "end";
                bar.dataset.beforeItemId = "";
                hostOf(bar.dataset.dayId)?.insertBefore(bar, null);
                return;
            }
            if (!summaryOf(bar.dataset.dayId) && last) {
                bar.dataset.dayId = last.dataset.plannerSummary;
                bar.dataset.insertPlacement = "end";
                bar.dataset.beforeItemId = "";
                status(bar, "선택한 날짜가 없어져 남은 마지막 날짜의 마지막 위치로 옮겼습니다.");
            }
            const dayId = bar.dataset.dayId;
            const summary = summaryOf(dayId);
            const host = hostOf(dayId);
            if (!summary || !host) { status(bar, "선택한 날짜를 찾을 수 없습니다. 새로고침해 주세요.", true); return; }
            const placement = bar.dataset.insertPlacement;
            if (placement === "start") {
                bar.dataset.beforeItemId = rowsOf(dayId)[0]?.dataset.itemId || "";
                host.insertBefore(bar, cards(host)[0] || Array.from(host.children).find(node => node !== bar) || null);
            } else if (placement === "before") {
                const anchor = cards(host).find(card => card.dataset.overviewItemId === bar.dataset.beforeItemId);
                const existing = rowsOf(dayId).some(row => row.dataset.itemId === bar.dataset.beforeItemId);
                if (anchor && existing) host.insertBefore(bar, anchor);
                else {
                    bar.dataset.insertPlacement = "end"; bar.dataset.beforeItemId = "";
                    host.insertBefore(bar, null);
                    status(bar, "선택한 일정이 변경되어 같은 날짜의 마지막 위치로 옮겼습니다.");
                }
            } else {
                bar.dataset.insertPlacement = "end"; bar.dataset.beforeItemId = "";
                host.insertBefore(bar, null);
            }
        }

        function beforeRefresh(host) {
            if (drag) return;
            if (bar.closest("[data-planner-summary-items]") === host) {
                bar.remove();
            }
        }

        function positions() {
            return days().flatMap((day, index) => {
                const dayId = day.dataset.plannerSummary;
                const prefix = `DAY ${index + 1}`;
                return [{dayId, placement: "start", beforeItemId: "", label: `${prefix} 시작`},
                    ...cards(hostOf(dayId)).map((card, at) => ({dayId, placement: "before", beforeItemId: card.dataset.overviewItemId,
                        label: `${prefix} · ${at + 1}번째 주요 일정 앞`})),
                    {dayId, placement: "end", beforeItemId: "", label: `${prefix} 마지막`}];
            });
        }

        function position(target) {
            bar.dataset.insertDefault = "false";
            bar.dataset.dayId = target.dayId;
            bar.dataset.insertPlacement = target.placement;
            bar.dataset.beforeItemId = target.beforeItemId || "";
            status(bar, target.label || "이 위치에 일정이 추가됩니다.");
            restore();
        }

        function layoutMiddle(row, host) {
            if (row.offsetParent === host && Number.isFinite(row.offsetTop) && Number.isFinite(row.offsetHeight)) {
                return host.getBoundingClientRect().top + row.offsetTop + row.offsetHeight / 2;
            }
            const bounds = row.getBoundingClientRect();
            return bounds.top + bounds.height / 2;
        }

        function preview(target, host) {
            const previous = drag.visualTarget;
            drag.candidate = target;
            if (previous && previous.dayId === target.dayId && previous.placement === target.placement && previous.beforeItemId === target.beforeItemId) return;
            const reduced = win.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
            const visible = Array.from(summaries.querySelectorAll("[data-overview-item-id]"));
            const before = new Map(visible.map(row => [row, row.getBoundingClientRect()]));
            drag.animations.forEach(animation => animation.cancel()); drag.animations.clear();
            const anchor = target.placement === "start" ? cards(host)[0] :
                target.placement === "before" ? cards(host).find(row => row.dataset.overviewItemId === target.beforeItemId) : null;
            host.insertBefore(drag.placeholder, anchor || null);
            drag.visualTarget = target;
            if (!reduced) visible.forEach(row => {
                const old = before.get(row); const now = row.getBoundingClientRect();
                const y = old.top - now.top;
                if (y && typeof row.animate === "function") {
                    const animation = row.animate([{transform: `translateY(${y}px)`}, {transform: "translateY(0)"}], {duration: 200, easing: "ease"});
                    animation.finished?.then(notifyLayout, notifyLayout);
                    drag.animations.set(row, animation);
                }
            });
            notifyLayout();
        }

        function dragVisuals(bounds) {
            const placeholder = doc.createElement("div");
            placeholder.className = "tp-overview-insert-placeholder"; placeholder.setAttribute("aria-hidden", "true");
            placeholder.style.height = `${bounds.height}px`;
            bar.parentNode.insertBefore(placeholder, bar);
            // Move the real control, never a native drag image or overlay clone.
            // Pointer capture keeps events on the grip; hit testing below the bar
            // resolves only valid DAY content hosts, independently of its transform.
            const sourceStyles = {position: "fixed", left: `${bounds.left}px`, top: `${bounds.top}px`, width: `${bounds.width}px`, height: `${bounds.height}px`, zIndex: "1000", pointerEvents: "none", transition: "none", transform: "translateY(0px)"};
            const originalStyle = {text: bar.getAttribute("style"), values: Object.fromEntries(Object.keys(sourceStyles).map(name => [name, bar.style[name] || ""]))};
            Object.assign(bar.style, sourceStyles);
            return {placeholder, originalStyle};
        }

        function openMenu() {
            if (!menu) return;
            menuTargets = positions();
            const buttons = menuTargets.map((target, index) => {
                const button = doc.createElement("button");
                button.type = "button"; button.dataset.overviewInsertChoice = String(index); button.textContent = target.label;
                return button;
            });
            menu.replaceChildren(...buttons); menu.hidden = false;
            bar.querySelector("[data-overview-insert-location]")?.setAttribute("aria-expanded", "true");
        }

        function pendingCard(bar, type) {
            const node = (tag, className, text) => {
                const element = doc.createElement(tag);
                element.className = className;
                if (text) element.textContent = text;
                return element;
            };
            const row = node("div", "tp-overview-item tp-overview-pending tp-item-enter");
            row.dataset.type = type;
            row.setAttribute("aria-busy", "true");
            const shell = node("div", "tp-detail-item-shell tp-item-card"); shell.dataset.type = type;
            const body = node("div", "travel-plan-line-body");
            const header = node("div", "tp-detail-item-bar");
            const badge = node("span", "tp-type-badge", labels[type]); badge.dataset.itemTypeBadge = type;
            const auxiliary = node("span", "tp-detail-item-aux");
            const label = node("span", "tp-detail-item-time-label", {FLIGHT: "탑승시간", ACCOMMODATION: "체크인", TRANSPORT: "출발"}[type]);
            const date = node("span", "", "날짜 미정");
            const time = node("time", "", "시간 미정");
            auxiliary.append(label, date, time);
            const title = node("h3", "tp-detail-item-title", type === "ACCOMMODATION" ? "숙소 이름 입력하기" : "구간 / 장소 입력하기");
            const saving = node("span", "tp-detail-pending-state", "저장 중"); saving.setAttribute("role", "status");
            const chevron = node("button", "tp-card-edit");
            chevron.type = "button"; chevron.disabled = true; chevron.setAttribute("aria-label", "일정 저장 중");
            header.append(badge, auxiliary, title, saving, chevron);
            body.append(header); shell.append(body); row.append(shell);
            const host = hostOf(bar.dataset.dayId);
            host.insertBefore(row, bar.dataset.insertPlacement === "start" ? host.children[0] || null : bar.parentNode === host ? bar : null);
            win.decorateTravelPlanTypes?.(row);
            return row;
        }

        async function add(bar, type) {
            if (!labels[type] || busy) return;
            if (needsReload) { status(bar, "저장 결과를 새로고침해서 확인해 주세요.", true); return; }
            if (blocked()) { status(bar, "편집 중인 일정을 먼저 완료해 주세요.", true); return; }
            restore();
            const dayId = bar.dataset.dayId;
            if (!hostOf(dayId)) return;
            // Resolve from the full persisted row order, including ordinary items
            // hidden by the overview, and freeze this target before awaiting a lock.
            const beforeItemId = bar.dataset.insertPlacement === "start" ? rowsOf(dayId)[0]?.dataset.itemId || "" :
                bar.dataset.insertPlacement === "before" ? bar.dataset.beforeItemId : "";
            bar.dataset.beforeItemId = beforeItemId;
            const slot = Array.from(planner.querySelectorAll("[data-travel-plan-slot]")).find(node => node.dataset.dayId === dayId);
            const form = slot?.querySelector("form[data-travel-plan-slot-form]");
            if (!form) { status(bar, "일정 추가 폼을 찾을 수 없습니다. 새로고침해 주세요.", true); return; }
            closeMenu(); clearDrag();
            busy = true;
            planner.dataset.editorOpening = "true";
            status(bar, "");
            // This happens synchronously, including while the collaborative lock
            // is still being requested. No editor or existing draft is touched.
            const pending = pendingCard(bar, type);
            const live = win.travelPlanRealtime;
            let locked = false;
            let postStarted = false;
            let responseReceived = false;
            let accepted = false;
            let createdId = null;
            try {
                if (live) {
                    const result = await live.requestLock({mode: "ADD", dayId, itemId: null});
                    if (!result.granted) throw new Error("다른 참여자가 이 날짜에 일정을 추가 중입니다.");
                    locked = true;
                }
                const body = new Form(form);
                body.set("emptyCard", "true"); body.set("itemType", type); body.set("content", "");
                if (beforeItemId) body.set("beforeItemId", beforeItemId);
                else body.delete("beforeItemId");
                postStarted = true;
                const response = await send(form.action, {
                    method: "POST", credentials: "same-origin", headers: {"X-Requested-With": "XMLHttpRequest"}, body
                });
                responseReceived = true;
                if (!response.ok) {
                    if (response.status >= 500) needsReload = true;
                    throw new Error("일정을 추가하지 못했습니다. 잠시 후 다시 확인해 주세요.");
                }
                accepted = true;
                const itemId = (await response.json()).itemId;
                if (!itemId) throw new Error("Missing created item");
                pending.remove();
                if (!live) { needsReload = true; win.location.reload(); return; }
                await live.refreshDay(dayId);
                const actual = rowsOf(dayId).find(row => row.dataset.itemId === String(itemId));
                if (!actual) throw new Error("Created item missing after refresh");
                createdId = String(itemId);
                status(bar, `${labels[type]} 일정이 추가되었습니다.`);
            } catch (error) {
                if (accepted || (postStarted && !responseReceived)) needsReload = true;
                status(bar, accepted ? "일정은 저장됐습니다. 새로고침해서 확인해 주세요." :
                    needsReload ? "저장 결과를 확인할 수 없습니다. 새로고침해서 확인해 주세요." : error.message, true);
            } finally {
                pending.remove();
                try { if (locked) live.releaseLock(); }
                finally {
                    busy = false;
                    delete planner.dataset.editorOpening;
                    doc.dispatchEvent(new Event("travelplan:schedule-updated"));
                    const host = hostOf(dayId);
                    if (createdId && host) cards(host).find(row => row.dataset.overviewItemId === createdId)?.classList.add("tp-item-enter");
                    doc.dispatchEvent(new Event("travelplan:editor-idle"));
                }
            }
        }

        planner.addEventListener("click", event => {
            if (busy) { stop(event); return; }
            const button = event.target.closest("[data-overview-insert-type], [data-overview-insert-location], [data-overview-insert-choice]");
            if (!button || button.closest("[data-overview-insert-bar]") !== bar) { closeMenu(); return; }
            stop(event);
            if (button.dataset.overviewInsertType) void add(bar, button.dataset.overviewInsertType);
            else if (!blocked()) {
                if (button.dataset.overviewInsertChoice !== undefined) {
                    const target = menuTargets[Number(button.dataset.overviewInsertChoice)];
                    if (target) position(target);
                    closeMenu(); bar.querySelector("[data-overview-insert-location]")?.focus();
                } else if (menu?.hidden) openMenu();
                else closeMenu();
            }
        }, true);
        planner.addEventListener("submit", event => { if (busy) stop(event); }, true);
        planner.addEventListener("keydown", event => {
            if (event.key === "Escape") { closeMenu(); clearDrag(true); return; }
            const handle = event.target.closest("[data-overview-insert-handle]");
            if (handle?.closest("[data-overview-insert-bar]") !== bar || !["ArrowUp", "ArrowDown", "Home", "End"].includes(event.key)) return;
            stop(event);
            if (blocked()) return;
            const targets = positions();
            let index = targets.findIndex(target => target.dayId === bar.dataset.dayId && target.placement === bar.dataset.insertPlacement &&
                (target.placement !== "before" || target.beforeItemId === bar.dataset.beforeItemId));
            if (event.key === "Home") index = 0;
            else if (event.key === "End") index = targets.length - 1;
            else index = Math.max(0, Math.min(targets.length - 1, index + (event.key === "ArrowUp" ? -1 : 1)));
            if (targets[index]) position(targets[index]);
            handle.focus();
        });
        planner.addEventListener("dragstart", event => {
            // Disable HTML5 drag only for this control; existing card drags remain native.
            if (event.target.closest("[data-overview-insert-bar]") === bar) stop(event);
        }, true);
        planner.addEventListener("pointerdown", event => {
            const handle = event.target.closest("[data-overview-insert-handle]");
            if (handle?.closest("[data-overview-insert-bar]") !== bar) return;
            if (event.button !== 0 || event.isPrimary === false || pointer) return;
            stop(event);
            if (blocked()) return;
            pointer = {id: event.pointerId, x: event.clientX, y: event.clientY};
            handle.setPointerCapture?.(event.pointerId);
            handle.focus();
        }, true);
        function activatePointer() {
            closeMenu();
            const bounds = bar.getBoundingClientRect();
            drag = {bar, ...dragVisuals(bounds), animations: new Map(), original: {
                dayId: bar.dataset.dayId, placement: bar.dataset.insertPlacement,
                beforeItemId: bar.dataset.beforeItemId, isDefault: bar.dataset.insertDefault
            }};
            win.getSelection?.()?.removeAllRanges?.();
            planner.dataset.overviewInsertDragging = "true";
            bar.classList.add("is-insert-dragging");
        }
        function dragPosition(event) {
            if (!drag || busy) return false;
            summaries.querySelectorAll(".is-insert-target").forEach(node => node.classList.remove("is-insert-target"));
            const hit = doc.elementFromPoint ? doc.elementFromPoint(event.clientX, event.clientY) : event.target;
            const host = hit?.closest("[data-planner-summary-items]");
            const summary = host?.closest("[data-planner-summary]");
            const dayId = summary?.dataset.plannerSummary;
            if (!host || !dayId || hostOf(dayId) !== host) { drag.candidate = null; return false; }
            event.preventDefault(); event.stopImmediatePropagation();
            host.classList.add("is-insert-target");
            if (hit.closest(".tp-overview-insert-placeholder") === drag.placeholder) {
                drag.candidate = drag.visualTarget || {dayId: drag.original.dayId, placement: drag.original.placement, beforeItemId: drag.original.beforeItemId};
                return true;
            }
            const items = cards(host);
            const index = items.findIndex(row => event.clientY < layoutMiddle(row, host));
            preview({dayId, placement: index === 0 ? "start" : index < 0 ? "end" : "before", beforeItemId: items[index]?.dataset.overviewItemId || ""}, host);
            if (typeof win.scrollBy === "function" && win.innerHeight) {
                const delta = event.clientY < 56 ? -24 : event.clientY > win.innerHeight - 56 ? 24 : 0;
                if (delta) win.scrollBy({top: delta, behavior: "auto"});
            }
            return true;
        }
        planner.addEventListener("pointermove", event => {
            if (!pointer || pointer.id !== event.pointerId) return;
            if (!drag) {
                if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) < 6) return;
                if (blocked()) { clearDrag(); return; }
                activatePointer();
            }
            stop(event);
            bar.style.transform = `translateY(${event.clientY - pointer.y}px)`;
            dragPosition(event);
        }, true);
        planner.addEventListener("pointerup", event => {
            if (!pointer || pointer.id !== event.pointerId) return;
            if (!drag) { clearDrag(); return; }
            stop(event);
            if (!dragPosition(event)) { clearDrag(true); return; }
            const target = drag.candidate;
            clearDrag();
            if (target) { position(target); notifyLayout(); }
        }, true);
        ["pointercancel", "lostpointercapture"].forEach(type => planner.addEventListener(type, event => {
            if (pointer?.id === event.pointerId) clearDrag(true);
        }, true));
        win.addEventListener?.("blur", () => clearDrag(true));
        doc.addEventListener("click", event => {
            if (event.target?.closest("[data-overview-insert-bar]") !== bar) closeMenu();
        });
        const api = {beforeRefresh, restore, isBusy: () => busy, isDragging: () => !!drag};
        win.travelPlanOverviewInsert = api;
        restore();
        return api;
    }

    if (typeof module !== "undefined" && module.exports) module.exports = {mount};
    else document.addEventListener("DOMContentLoaded", () => mount(document.querySelector(".is-planning [data-plan-id]")));
})();
