(function () {
    "use strict";
    // A single writer per shared editor. Input remains editable while requests run.
    function create(options, clock = globalThis) {
        let baseline, pending = null, timer = null, active = false, paused = false, generation = 0;
        function clearTimer() { clock.clearTimeout(timer); timer = null; }
        function start() { clearTimer(); baseline = options.read().key; active = true; paused = false; generation++; }
        async function flush() {
            clearTimer();
            if (!active) return true;
            if (paused) return false;
            if (pending) return pending;
            const epoch = generation;
            pending = (async () => {
                while (active && !paused && epoch === generation) {
                    const snapshot = options.read();
                    if (snapshot.key === baseline) { options.status("저장됨"); return true; }
                    options.status("저장 중…");
                    try {
                        const result = await options.write(snapshot);
                        if (!active || epoch !== generation) return false;
                        options.accept(result, snapshot);
                        baseline = result?.key ?? snapshot.key;
                    } catch (error) {
                        options.status("저장 실패"); options.error(error);
                        return false;
                    }
                }
                return false;
            })();
            try { return await pending; } finally { pending = null; }
        }
        function changed(immediate = false) {
            if (!active || paused) return;
            clearTimer(); options.status("저장 대기 중");
            if (immediate) Promise.resolve().then(() => { if (active) void flush(); });
            else timer = clock.setTimeout(() => { timer = null; void flush(); }, 650);
        }
        function stop() { clearTimer(); active = false; generation++; }
        function pause() { clearTimer(); paused = true; return pending || Promise.resolve(true); }
        function resume() { paused = false; options.status(options.read().key === baseline ? "저장됨" : "저장 대기 중"); }
        return {start, changed, flush, stop, pause, resume, isBusy: () => !!pending,
            isDirty: () => active && options.read().key !== baseline};
    }
    if (typeof module !== "undefined" && module.exports) module.exports = {create};
    else window.TravelPlanItemAutosave = {create};
})();
