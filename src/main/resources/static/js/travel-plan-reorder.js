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
        const planner = document.querySelector(".is-planning [data-plan-id]");
        // Share the version-checked transfer/reorder path between both views.
        window.TravelPlanCardReorder?.mount(planner, {scope: "detail"});
    });
})();
