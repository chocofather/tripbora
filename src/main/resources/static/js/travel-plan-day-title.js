document.addEventListener("DOMContentLoaded", () => {
    const planner = document.querySelector("[data-plan-id]");
    if (!planner) return;

    let active = null;
    const placeholder = "이날의 여행 제목을 적어 주세요";
    window.travelPlanDayTitle = {isEditing: () => active !== null};

    function status(editor, message) {
        const node = editor.header.querySelector("[data-day-title-status]");
        if (node) node.textContent = message;
    }

    function close(editor, cancelled = false) {
        const dayId = editor.header.closest("[data-travel-plan-day-id]")?.dataset.travelPlanDayId;
        editor.input.value = editor.title;
        editor.version.value = editor.titleVersion;
        editor.form.hidden = true;
        editor.button.hidden = false;
        editor.header.classList.remove("is-editing");
        active = null;
        status(editor, "");
        editor.button.focus();
        document.dispatchEvent(new CustomEvent("travelplan:editor-idle"));
        if (cancelled && editor.conflicted && dayId && !active) {
            Promise.resolve(window.travelPlanRealtime?.refreshDay(dayId)).catch(() => {});
        }
    }

    planner.addEventListener("click", event => {
        const cancel = event.target.closest("[data-day-title-cancel]");
        if (cancel && active && cancel.closest("[data-day-title-form]") === active.form) {
            event.preventDefault();
            if (!active.saving) close(active, true);
            return;
        }
        const button = event.target.closest("[data-day-title-edit]");
        if (!button) return;
        const header = button.closest(".travel-plan-day-heading");
        const form = header?.querySelector("[data-day-title-form]");
        if (!form) return;
        const editor = {button, header, form, input: form.elements.namedItem("title"),
            version: form.elements.namedItem("titleVersion"), saving: false};
        if (active || planner.querySelector(".is-editing") || planner.dataset.editorOpening === "true") {
            status(editor, "현재 편집 중인 내용을 저장하거나 취소한 뒤 제목을 수정해 주세요.");
            return;
        }
        editor.title = editor.input.value;
        editor.titleVersion = editor.version.value;
        active = editor;
        header.classList.add("is-editing");
        button.hidden = true;
        form.hidden = false;
        status(editor, "");
        editor.input.focus();
    });

    planner.addEventListener("keydown", event => {
        if (event.key !== "Escape" || event.isComposing || event.keyCode === 229 || !active) return;
        if (event.target.closest("[data-day-title-form]") !== active.form || active.saving) return;
        event.preventDefault();
        close(active, true);
    });

    planner.addEventListener("submit", async event => {
        const form = event.target.closest("[data-day-title-form]");
        if (!form) return;
        event.preventDefault();
        const editor = active;
        if (!editor || editor.form !== form || editor.saving || !form.reportValidity()) return;

        // Serialize first: disabled controls are excluded from FormData.
        const body = new URLSearchParams(new FormData(form));
        const controls = Array.from(form.elements).map(node => ({node, disabled: node.disabled}));
        editor.saving = true;
        controls.forEach(({node}) => { node.disabled = true; });
        status(editor, "저장 중…");
        const headers = {"X-Requested-With": "XMLHttpRequest", Accept: "application/json"};
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (token && csrfHeader) headers[csrfHeader] = token;
        try {
            const response = await fetch(form.action, {method: "POST", headers, body});
            if (active !== editor || !form.isConnected) return;
            if (!response.ok) {
                if (response.status === 409) editor.conflicted = true;
                status(editor, response.status === 409
                    ? "다른 참여자가 제목을 변경했습니다. 입력 내용은 유지됩니다. 취소 후 최신 제목을 확인해 주세요."
                    : "제목을 저장하지 못했습니다. 입력 내용을 확인하고 다시 시도해 주세요.");
                return;
            }
            const result = await response.json();
            if (active !== editor || !form.isConnected) return;
            if ((typeof result.title !== "string" && result.title !== null) || !Number.isInteger(result.titleVersion)) {
                throw new Error("Invalid title response");
            }
            editor.title = result.title || "";
            editor.titleVersion = String(result.titleVersion);
            editor.input.defaultValue = editor.title;
            editor.version.defaultValue = editor.titleVersion;
            editor.button.textContent = editor.title || placeholder;
            editor.button.classList.toggle("is-placeholder", !editor.title);
            close(editor);
        } catch (error) {
            if (active === editor && form.isConnected) {
                status(editor, "제목을 저장하지 못했습니다. 입력 내용은 유지됩니다. 연결을 확인하고 다시 시도해 주세요.");
            }
        } finally {
            editor.saving = false;
            controls.forEach(({node, disabled}) => { node.disabled = disabled; });
            if (active === editor && !form.isConnected) active = null;
        }
    });
});
