document.addEventListener("DOMContentLoaded", () => {
    let active = null;

    function close(restoreFocus = false) {
        if (!active) return;
        const {root, button, panel} = active;
        panel.hidden = true;
        root.appendChild(panel);
        button.setAttribute("aria-expanded", "false");
        active = null;
        if (restoreFocus) button.focus();
    }

    document.querySelectorAll("[data-destination-actions]").forEach(root => {
        const button = root.querySelector("[data-destination-action-toggle]");
        const panel = root.querySelector("[data-destination-action-panel]");
        if (!button || !panel) return;

        button.addEventListener("click", () => {
            const wasOpen = active?.button === button;
            close();
            if (wasOpen) return;

            // 테이블의 overflow 영역 밖으로 옮겨 마지막 행과 모바일에서도 메뉴가 잘리지 않게 한다.
            document.body.appendChild(panel);
            panel.hidden = false;
            button.setAttribute("aria-expanded", "true");
            active = {root, button, panel};
            const anchor = button.getBoundingClientRect();
            const bounds = panel.getBoundingClientRect();
            const width = document.documentElement.clientWidth;
            const height = window.innerHeight;
            const top = anchor.bottom + 4 + bounds.height <= height - 8
                ? anchor.bottom + 4 : anchor.top - bounds.height - 4;
            panel.style.left = `${Math.max(8, Math.min(anchor.right - bounds.width, width - bounds.width - 8))}px`;
            panel.style.top = `${Math.max(8, Math.min(top, height - bounds.height - 8))}px`;
            panel.querySelector("button:not([disabled])")?.focus();
        });

        panel.addEventListener("keydown", event => {
            if (event.key !== "ArrowDown" && event.key !== "ArrowUp") return;
            event.preventDefault();
            const items = Array.from(panel.querySelectorAll("button:not([disabled])"));
            const step = event.key === "ArrowDown" ? 1 : -1;
            const index = items.indexOf(document.activeElement);
            items[(index + step + items.length) % items.length]?.focus();
        });
    });

    document.addEventListener("click", event => {
        if (active && !active.panel.contains(event.target) && !active.button.contains(event.target)) close();
    });
    document.addEventListener("keydown", event => {
        if (event.key === "Escape" && active) {
            event.preventDefault();
            close(true);
        }
    });
    document.addEventListener("focusin", event => {
        if (active && !active.panel.contains(event.target) && !active.button.contains(event.target)) close();
    });
    document.addEventListener("scroll", event => {
        if (active && !active.panel.contains(event.target)) close();
    }, true);
    window.addEventListener("resize", () => close());
    window.addEventListener("pageshow", event => { if (event.persisted) close(); });
});
