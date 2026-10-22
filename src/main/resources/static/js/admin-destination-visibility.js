document.addEventListener("DOMContentLoaded", () => {
    const errorMessage = document.querySelector("[data-destination-visibility-error]");
    const buttons = document.querySelectorAll("[data-destination-visibility]");

    buttons.forEach(button => {
        button.addEventListener("click", async () => {
            if (button.disabled) return;
            const publicVisible = button.dataset.publicVisible !== "true";
            if (!publicVisible && !window.confirm("이 여행지를 일반 사용자에게 숨기시겠습니까?")) return;
            button.disabled = true;
            button.setAttribute("aria-busy", "true");
            if (errorMessage) errorMessage.hidden = true;

            try {
                const token = document.querySelector('meta[name="_csrf"]')?.content;
                const header = document.querySelector('meta[name="_csrf_header"]')?.content;
                if (!token || !header) throw new Error("CSRF token unavailable");

                const response = await fetch(button.dataset.visibilityUrl, {
                    method: "POST",
                    credentials: "same-origin",
                    headers: {"Content-Type": "application/json", "Accept": "application/json", [header]: token},
                    body: JSON.stringify({publicVisible})
                });
                if (!response.ok || response.redirected) throw new Error("Visibility update failed");
                const result = await response.json();
                if (result.publicVisible !== publicVisible) throw new Error("Unexpected visibility response");

                // 현재 조건으로 다시 조회해 필터에서 빠진 행과 건수를 함께 갱신한다.
                window.location.reload();
            } catch (error) {
                if (errorMessage) {
                    errorMessage.textContent = "공개 상태를 변경하지 못했습니다. 로그인 상태를 확인하고 다시 시도해 주세요.";
                    errorMessage.hidden = false;
                }
                button.disabled = false;
                button.removeAttribute("aria-busy");
            }
        });
    });

    window.addEventListener("pageshow", event => {
        if (!event.persisted) return;
        buttons.forEach(button => {
            button.disabled = false;
            button.removeAttribute("aria-busy");
        });
    });
});
