(function () {
    "use strict";
    // Same pointer/capture + projected gap model as the overview insertion bar.
    // Persistence stays with the caller; never clone/reparent the active source.
    function mount(planner, options, environment = {}) {
        const doc = environment.document || document, win = environment.window || window;
        let pointer = null, active = null;
        const stop = event => { event.preventDefault(); event.stopImmediatePropagation(); };
        const layout = () => doc.dispatchEvent(new (environment.CustomEvent || CustomEvent)("travelplan:overview-layout"));
        function release() {
            const held = pointer; pointer = null;
            if (held?.handle.hasPointerCapture?.(held.id)) held.handle.releasePointerCapture(held.id);
        }
        function reset() {
            const old = active; active = null;
            if (old) {
                old.animations.forEach(animation => animation.cancel());
                old.gap.remove(); old.card.classList.remove("tp-pointer-sorting");
                Object.assign(old.card.style, old.values);
                if (old.style === null) old.card.removeAttribute("style"); else old.card.setAttribute("style", old.style);
            }
            release(); layout();
            return old;
        }
        function cancel() { const old = reset(); if (old) options.cancel(); }
        function project(target) {
            if (active.target?.host === target.host && active.target?.anchor === target.anchor && active.target?.placement === target.placement) return;
            const items = options.items().filter(item => item !== active.card);
            const before = new Map(items.map(item => [item, item.getBoundingClientRect().top]));
            active.animations.forEach(animation => animation.cancel()); active.animations.clear();
            const visible = options.rows(target.host).filter(item => item !== active.card);
            const anchor = target.placement === "start" ? visible[0] : target.placement === "before" ? target.anchor :
                target.placement === "after" ? visible[visible.indexOf(target.anchor) + 1] : null;
            target.host.insertBefore(active.gap, anchor || options.endAnchor?.(target.host) || null);
            active.target = target;
            if (!win.matchMedia?.("(prefers-reduced-motion: reduce)").matches) items.forEach(item => {
                const y = before.get(item) - item.getBoundingClientRect().top;
                if (y && item.animate) {
                    const animation = item.animate([{transform: `translateY(${y}px)`}, {transform: "translateY(0)"}], {duration: 200, easing: "ease"});
                    active.animations.set(item, animation); animation.finished?.then(layout, layout);
                }
            });
            layout();
        }
        function locate(event) {
            const hit = doc.elementFromPoint ? doc.elementFromPoint(event.clientX, event.clientY) : event.target;
            if (hit?.closest(".tp-pointer-sort-gap") === active.gap) return active.target;
            return options.locate(hit, event.clientY);
        }
        planner.addEventListener("dragstart", event => {
            if (event.target.closest(options.handle)) stop(event);
        }, true);
        planner.addEventListener("pointerdown", event => {
            const handle = event.target.closest(options.handle);
            if (!handle || event.button !== 0 || event.isPrimary === false || pointer) return;
            stop(event);
            if (!options.canStart(handle)) return;
            pointer = {handle, id: event.pointerId, x: event.clientX, y: event.clientY};
            handle.setPointerCapture?.(event.pointerId); handle.focus?.();
        }, true);
        planner.addEventListener("pointermove", event => {
            if (!pointer || pointer.id !== event.pointerId) return;
            if (!active) {
                if (Math.hypot(event.clientX - pointer.x, event.clientY - pointer.y) < 6) return;
                if (!options.canStart(pointer.handle)) { release(); return; }
                const card = options.start(pointer.handle);
                if (!card) { release(); return; }
                const bounds = card.getBoundingClientRect();
                const gap = doc.createElement(card.tagName === "LI" ? "li" : "div");
                gap.className = "tp-pointer-sort-gap"; gap.setAttribute("aria-hidden", "true"); gap.style.height = `${bounds.height}px`;
                const styles = {position: "fixed", top: `${bounds.top}px`, left: `${bounds.left}px`, width: `${bounds.width}px`, height: `${bounds.height}px`, margin: "0", zIndex: "1000", pointerEvents: "none", transition: "none", transform: "translateY(0px)", opacity: "1"};
                active = {card, gap, style: card.getAttribute("style"), values: Object.fromEntries(Object.keys(styles).map(key => [key, card.style[key] || ""])), animations: new Map(), target: null};
                card.parentNode.insertBefore(gap, card); Object.assign(card.style, styles); card.classList.add("tp-pointer-sorting");
                win.getSelection?.()?.removeAllRanges?.();
            }
            stop(event);
            active.card.style.transform = `translateY(${event.clientY - pointer.y}px)`;
            const target = locate(event);
            if (target) project(target);
            if (win.innerHeight && win.scrollBy) {
                const delta = event.clientY < 56 ? -24 : event.clientY > win.innerHeight - 56 ? 24 : 0;
                if (delta) win.scrollBy({top: delta, behavior: "auto"});
            }
        }, true);
        planner.addEventListener("pointerup", event => {
            if (!pointer || pointer.id !== event.pointerId) return;
            if (!active) { release(); return; }
            stop(event);
            const target = locate(event);
            reset();
            if (target) void options.drop(target); else options.cancel();
        }, true);
        ["pointercancel", "lostpointercapture"].forEach(type => planner.addEventListener(type, event => {
            if (pointer?.id === event.pointerId) cancel();
        }, true));
        doc.addEventListener("keydown", event => { if (event.key === "Escape" && pointer) { stop(event); cancel(); } });
        win.addEventListener?.("blur", () => { if (pointer) cancel(); });
        return {cancel};
    }
    if (typeof module !== "undefined" && module.exports) module.exports = {mount};
    else window.TravelPlanPointerSort = {mount};
})();
