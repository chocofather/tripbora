/*
  채팅 패널 옮기기.

  넓은 화면에서만 머리글을 잡고 패널을 원하는 자리로 옮긴다.
  처음 자리는 CSS 의 오른쪽 아래 그대로이고, 한 번 옮기면 그때부터 left/top 으로 바꿔 둔다
  (right/bottom 은 .is-placed 가 auto 로 풀어 둘이 부딪히지 않는다).

  옮긴 자리는 이 브라우저에만 남는 화면 설정이다. 서버로 보내지 않는다.
  저장하는 것은 사용자가 놓은 자리 그대로이고, 화면이 작아져 넘치면 보여 줄 때만 안쪽으로 당긴다.
  그래야 창을 잠깐 줄였다 다시 키워도 원래 놓은 자리로 돌아온다.

  좁은 화면에서는 옮기지 않고 기존 반응형 자리(CSS)를 그대로 쓴다.
  채팅 송수신·열고 닫기는 travel-plan-chat.js 가 그대로 맡고, 여기서는 자리만 다룬다.
*/
document.addEventListener("DOMContentLoaded", () => {
    const panel = document.querySelector("[data-travel-plan-chat-panel]");
    const handle = panel?.querySelector("[data-travel-plan-chat-drag-handle]");
    if (!panel || !handle) return;

    const POSITION_KEY = "travelPlan.chat.position";
    // 이 폭 이하에서는 옮기지 않는다. CSS 의 같은 값(travel-plan.css)과 맞춘다
    const narrowScreen = window.matchMedia("(max-width: 768px)");
    // 머리글 안이라도 누르는 것들 위에서는 잡지 않는다
    const INTERACTIVE = "button, a, input, textarea, select, label, [role='button']";

    let placed = readPosition();
    let drag = null;

    function readPosition() {
        try {
            const saved = JSON.parse(window.localStorage.getItem(POSITION_KEY) || "null");
            // 남이 고쳐 넣었거나 깨진 값이면 기본 자리(오른쪽 아래)로 둔다
            if (saved && Number.isFinite(saved.left) && Number.isFinite(saved.top)) {
                return {left: saved.left, top: saved.top};
            }
        } catch (error) {
            // 읽을 수 없으면 기본 자리
        }
        return null;
    }

    function savePosition() {
        try {
            window.localStorage.setItem(POSITION_KEY, JSON.stringify(placed));
        } catch (error) {
            // 저장이 막힌 브라우저에서도 옮기는 것 자체는 된다
        }
    }

    /*
      페이지 머리(.main-nav)는 패널보다 위층이라, 그 밑으로 올라가면 머리글과 닫기 버튼이 가려진다.
      머리의 실제 높이를 읽어 그 아래까지만 올라가게 한다(높이를 여기 옮겨 적지 않는다).
    */
    function topLimit() {
        const nav = document.querySelector(".main-nav");
        if (!nav) return 0;
        const rect = nav.getBoundingClientRect();
        const pinned = ["fixed", "sticky"].includes(window.getComputedStyle(nav).position);
        // 문서 맨 위에 붙은 머리는 화면을 내려도 다시 올리면 그 자리에 오므로 문서 기준 높이로 본다
        return Math.max(0, pinned ? rect.bottom : rect.bottom + window.scrollY);
    }

    // 지금 화면과 패널의 실제 크기 안에서 가장 가까운 자리
    function clamp(left, top) {
        const viewport = document.documentElement;
        const rect = panel.getBoundingClientRect();
        const maxLeft = Math.max(0, viewport.clientWidth - rect.width);
        const minTop = Math.min(topLimit(), Math.max(0, viewport.clientHeight - rect.height));
        const maxTop = Math.max(minTop, viewport.clientHeight - rect.height);
        return {
            left: Math.min(Math.max(left, 0), maxLeft),
            top: Math.min(Math.max(top, minTop), maxTop)
        };
    }

    function applyPosition(left, top) {
        panel.classList.add("is-placed");
        panel.style.left = `${left}px`;
        panel.style.top = `${top}px`;
    }

    function resetPosition() {
        panel.classList.remove("is-placed");
        panel.style.left = "";
        panel.style.top = "";
    }

    // 저장한 자리를 지금 화면에 맞춰 보여 준다. 저장 값은 바꾸지 않는다
    function render() {
        if (narrowScreen.matches || !placed) {
            resetPosition();
            return;
        }
        // 닫혀 있으면 크기를 잴 수 없으니, 열릴 때(travelplan:chat-opened) 다시 맞춘다
        if (panel.hidden) {
            applyPosition(placed.left, placed.top);
            return;
        }
        const next = clamp(placed.left, placed.top);
        applyPosition(next.left, next.top);
    }

    function endDrag(event) {
        if (!drag || event.pointerId !== drag.pointerId) return;
        const moved = drag.moved;
        // 놓는 순간 lostpointercapture 가 다시 불려도 두 번 처리하지 않도록 먼저 비운다
        drag = null;
        if (handle.hasPointerCapture?.(event.pointerId)) {
            handle.releasePointerCapture(event.pointerId);
        }
        document.documentElement.classList.remove("travel-plan-chat-dragging");
        if (!moved) return;
        const rect = panel.getBoundingClientRect();
        placed = {left: Math.round(rect.left), top: Math.round(rect.top)};
        savePosition();
    }

    handle.addEventListener("pointerdown", event => {
        if (narrowScreen.matches || event.button !== 0 || event.target.closest(INTERACTIVE)) return;
        const rect = panel.getBoundingClientRect();
        drag = {
            pointerId: event.pointerId,
            offsetX: event.clientX - rect.left,
            offsetY: event.clientY - rect.top,
            moved: false
        };
        handle.setPointerCapture(event.pointerId);
        // 잡는 동안 글자가 선택되지 않게 한다
        event.preventDefault();
    });

    handle.addEventListener("pointermove", event => {
        if (!drag || event.pointerId !== drag.pointerId) return;
        if (!drag.moved) {
            // 실제로 움직였을 때 처음으로 left/top 자리로 바꾼다(그냥 누르기만 하면 그대로)
            drag.moved = true;
            document.documentElement.classList.add("travel-plan-chat-dragging");
        }
        const next = clamp(event.clientX - drag.offsetX, event.clientY - drag.offsetY);
        applyPosition(next.left, next.top);
    });

    handle.addEventListener("pointerup", endDrag);
    handle.addEventListener("pointercancel", endDrag);
    handle.addEventListener("lostpointercapture", endDrag);

    // 닫았다 다시 열어도, 새로고침해도 옮겨 둔 자리에서 열린다
    document.addEventListener("travelplan:chat-opened", render);
    window.addEventListener("resize", render);
    // 좁은 화면으로 바뀌면 기존 반응형 자리로, 다시 넓어지면 저장한 자리로
    narrowScreen.addEventListener?.("change", render);

    render();
});
