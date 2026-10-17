/*
  공동 여행계획의 실시간 연결.
  STOMP 연결은 이 파일 하나가 들고 있고, 그 위에서 아래를 구독한다.

    presence : 누가 접속해 있는지
    members  : 방에 누가 속해 있는지 ("참여자 N/8" — 접속 표시와 다른 숫자다)
    schedule : 어떤 DAY 가 바뀌었는지
    editor   : 누가 어디를 쓰고 있는지
    chat     : 방 채팅
    polls    : 방 투표

  브라우저 하나가 /ws 에 두 번 붙지 않도록 client 는 하나만 만든다.
  일정 편집(travel-plan-scheduler.js)과는 DOM 이벤트로만 이야기하고,
  채팅 화면(travel-plan-chat.js)에는 아래의 작은 연결 API 만 내어 준다.
  이 파일은 연결만 맡고, 채팅 UI/기록/안 읽은 개수는 채팅 쪽이 맡는다.
*/
document.addEventListener("DOMContentLoaded", () => {
    const planner = document.querySelector("[data-plan-id]");
    if (!planner || typeof StompJs === "undefined") return;

    const planId = planner.getAttribute("data-plan-id");
    if (!planId) return;

    // ── 접속 표시 ───────────────────────────────────────────
    /*
      참여자 줄은 명단이 바뀔 때 통째로 새로 그려진다.
      그래서 처음의 목록을 들고 있지 않고 그릴 때마다 다시 찾는다.
      (한 번 담아 두면 새로 그린 뒤 화면에 없는 옛 줄에만 표시하게 된다)
    */
    /** 마지막으로 받은 접속 상황. 명단을 새로 그린 뒤 그대로 다시 입힌다. */
    let lastPresence = { onlineMemberIds: [], onlineCount: 0 };

    // 연결되기 전에는 누가 붙어 있는지 알 수 없다. 아무도 온라인이라고 추측하지 않는다.
    function renderPresence(onlineMemberIds, onlineCount) {
        lastPresence = {
            onlineMemberIds: onlineMemberIds || [],
            onlineCount: onlineCount || 0
        };
        const online = new Set(lastPresence.onlineMemberIds.map(String));
        document.querySelectorAll("[data-travel-plan-member-row]").forEach(row => {
            const isOnline = online.has(row.getAttribute("data-member-id"));
            row.classList.toggle("is-online", isOnline);
            const dot = row.querySelector("[data-travel-plan-presence-dot]");
            if (!dot) return;
            const label = isOnline ? "온라인" : "오프라인";
            dot.setAttribute("title", label);
            dot.setAttribute("aria-label", label);
        });
        const countLabel = document.querySelector("[data-travel-plan-online-count]");
        if (countLabel) {
            countLabel.textContent = `${lastPresence.onlineCount}명 접속 중`;
            countLabel.hidden = false;
        }
    }

    // ── 참여자 명단 ─────────────────────────────────────────
    /*
      "참여자 N/8" 과 팝오버 목록.
      "접속 중 N명" 과는 다른 숫자다. 저쪽은 지금 창을 열어 둔 사람 수고
      이쪽은 방에 속한 사람 수라 서로 섞지 않는다.

      알림은 "명단이 바뀌었다" 는 신호일 뿐이라 사람 수는 서버에서 다시 받는다.
      화면에서 +1/-1 하지 않으므로 같은 알림을 두 번 받아도 숫자가 두 번 늘지 않는다.
    */
    let membersRequest = 0;

    async function refreshMembers() {
        const panel = document.querySelector("[data-travel-plan-members-panel]");
        if (!panel) return;

        const sequence = ++membersRequest;
        const response = await fetch(`/travel-plans/${planId}/members/fragment`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        // 명단을 읽을 자격조차 없다는 것은 이 방에서 빠졌다는 뜻이다.
        if (response.status === 403 || response.status === 404) {
            revokeAccess();
            return;
        }
        if (!response.ok) return;
        const html = await response.text();

        // 늦게 도착한 예전 응답이 최신 명단을 덮지 않게 한다.
        if (membersRequest !== sequence) return;

        /*
          패널 자체는 그대로 두고 속만 갈아 끼운다.
          팝오버를 열어 둔 채였다면 열린 그대로 내용만 바뀐다.
        */
        panel.innerHTML = html;

        // 토글 글자는 서버가 만들어 보낸 값을 그대로 쓴다.
        const label = panel.querySelector("[data-members-label]")
            ?.getAttribute("data-members-label");
        const toggle = document.querySelector("[data-travel-plan-members-toggle]");
        if (label && toggle) toggle.textContent = label;

        // 새로 그린 줄에는 접속 표시가 없다. 지금 알고 있는 상황을 다시 입힌다.
        renderPresence(lastPresence.onlineMemberIds, lastPresence.onlineCount);
    }

    // ── 방장 전용 자리 ──────────────────────────────────────
    /*
      방장은 바뀐다. 멤버로 그려진 화면에는 방장 전용 markup 이 아예 없으므로
      감추고 보이는 것만으로는 넘겨받은 사람 화면에 나타나게 할 수 없다.
      그래서 지금 누가 방장인지를 서버에 다시 물어 그 자리를 통째로 갈아 끼운다.
      화면은 역할을 짐작하지 않는다.
    */
    let ownerActionsRequest = 0;

    async function refreshOwnerActions() {
        const slot = document.querySelector("[data-travel-plan-owner-actions]");
        if (!slot) return;

        const sequence = ++ownerActionsRequest;
        const response = await fetch(`/travel-plans/${planId}/owner-actions/fragment`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        if (!response.ok) return;
        const html = await response.text();

        // 늦게 도착한 예전 응답이 최신 상태를 덮지 않게 한다.
        if (ownerActionsRequest !== sequence) return;

        /*
          방장이면 markup 이 오고, 아니면 빈 조각이 와서 그 자리가 비워진다.
          넘겨준 쪽에서는 이것만으로 버튼이 사라진다.
        */
        slot.innerHTML = html;
        // 갈아 끼운 markup 에는 동작이 붙어 있지 않다. 붙이는 쪽에 알린다.
        document.dispatchEvent(new CustomEvent("travelplan:owner-actions-updated"));
    }

    // ── 방에서 빠졌을 때 ────────────────────────────────────
    /*
      접근을 막는 것은 서버다. 서버가 이 연결을 끊으므로
      여기서 아무것도 하지 않아도 이후 내용은 더 오지 않는다.

      이 처리는 그 화면이 멈춘 채로 남지 않게 하는 안내일 뿐이다.
      다시 붙으려 하지 않고(구독이 다시 거절될 뿐이다) 목록으로 돌아간다.
    */
    let revoked = false;

    function revokeAccess(message) {
        if (revoked) return;
        revoked = true;

        // 라이브러리가 5초마다 다시 붙으려 하는 것을 멈춘다.
        client.deactivate();

        const notice = document.createElement("p");
        notice.className = "travel-plan-completed-notice";
        notice.setAttribute("role", "status");
        notice.textContent = message || "더 이상 이 여행 계획에 참여하고 있지 않습니다.";
        (document.querySelector(".travel-plan-page") || document.body).prepend(notice);

        // 읽을 틈은 주되 그 자리에 머무르게 두지 않는다.
        window.setTimeout(() => { window.location.href = "/travel-plans"; }, 1500);
    }

    // ── 일정 갱신 ───────────────────────────────────────────
    // DAY 마다 마지막 요청 번호를 기억한다.
    // 빠르게 여러 번 바뀌면 응답이 뒤섞여 도착할 수 있어, 최신 요청의 응답만 반영한다.
    const dayRequests = new Map();
    // 편집 중이라 미뤄 둔 DAY. 편집이 끝나면 그때 새로 고친다.
    const pendingDays = new Set();
    let pendingResync = false;

    function dayElement(dayId) {
        return document.querySelector(`[data-travel-plan-day-id="${dayId}"]`);
    }

    /*
      새로 받은 markup 으로 그 자리를 갈아 끼운다.
      갈아 끼운 요소는 전부 새것이라 동작이 붙어 있지 않으므로,
      편집 쪽(travel-plan-scheduler.js)이 다시 붙일 수 있게 그 요소를 함께 알린다.
    */
    function replaceWith(target, html) {
        const holder = document.createElement("div");
        holder.innerHTML = html.trim();
        const fresh = holder.firstElementChild;
        if (!fresh) return;
        target.replaceWith(fresh);
        document.dispatchEvent(new CustomEvent("travelplan:schedule-updated", {
            detail: { root: fresh }
        }));
        // 갈아 끼운 markup 에는 "편집 중" 표시가 없다.
        // 아직 붙잡혀 있는 자리는 다시 표시해 준다.
        remoteLocks.forEach(renderRemote);
    }

    /** 그 DAY 안에서 지금 무언가 편집 중인지. 편집 중이면 덮어쓰지 않는다. */
    function isEditing(element) {
        return !!element && !!element.querySelector(".is-editing");
    }

    function anyEditing() {
        return !!document.querySelector("[data-travel-plan-days] .is-editing");
    }

    async function refreshDay(dayId) {
        const element = dayElement(dayId);
        if (!element) return;
        // 작성 중이던 내용을 날리지 않도록 편집이 끝날 때까지 미룬다.
        if (isEditing(element)) {
            pendingDays.add(String(dayId));
            return;
        }

        const sequence = (dayRequests.get(String(dayId)) || 0) + 1;
        dayRequests.set(String(dayId), sequence);

        const response = await fetch(`/travel-plans/${planId}/days/${dayId}/fragment`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        if (!response.ok) return;
        const html = await response.text();

        // 늦게 도착한 예전 응답이 최신 화면을 덮지 않게 한다.
        if (dayRequests.get(String(dayId)) !== sequence) return;
        const target = dayElement(dayId);
        if (!target || isEditing(target)) {
            pendingDays.add(String(dayId));
            return;
        }
        replaceWith(target, html);
    }

    /** 끊겨 있던 동안 놓친 변경은 다시 받을 수 없어 전체를 한 번에 맞춘다. */
    async function resyncSchedule() {
        const container = document.querySelector("[data-travel-plan-days]");
        if (!container) return;
        if (anyEditing()) {
            pendingResync = true;
            return;
        }

        const response = await fetch(`/travel-plans/${planId}/schedule/fragment`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        if (!response.ok) return;
        const html = await response.text();

        const target = document.querySelector("[data-travel-plan-days]");
        if (!target || anyEditing()) {
            pendingResync = true;
            return;
        }
        replaceWith(target, html);
    }

    // 편집이 끝나면 미뤄 둔 갱신을 그때 반영한다.
    document.addEventListener("travelplan:editor-idle", () => {
        if (pendingResync) {
            pendingResync = false;
            resyncSchedule();
            return;
        }
        const days = Array.from(pendingDays);
        pendingDays.clear();
        days.forEach(refreshDay);
    });

    // ── 작성 중 상태 ────────────────────────────────────────
    // 서버가 붙잡아 준 자리만 편집기를 열 수 있다. 화면이 혼자 판단하지 않는다.
    const pendingLocks = new Map();
    // lockKey -> 원격에서 작성 중인 사람. 내 자리는 여기 넣지 않는다.
    const remoteLocks = new Map();
    /** 내가 지금 붙잡고 있는 자리. 한 번에 하나뿐이다. */
    let heldLock = null;
    /** 그 자리의 자세한 정보. 어떤 A 밑의 대안인지 알아야 할 때 쓴다. */
    let heldLockDetail = null;
    let requestSeq = 0;

    /** 자리 이름. 서버가 쓰는 규칙과 같아야 한다. */
    function lockKeyOf(spot) {
        if (spot.mode === "ALT_EDIT") return `ALT:${spot.alternativeId}`;
        if (spot.mode === "ALT_ADD") return `ALT_ADD:${spot.itemId}`;
        return spot.itemId ? `ITEM:${spot.itemId}` : `ADD:${spot.dayId}`;
    }

    /** 그 자리에 해당하는 화면 줄. */
    function lineOf(lock) {
        if (!lock) return null;
        if (lock.mode === "ALT_EDIT") {
            return document.querySelector(`[data-alternative-id="${lock.alternativeId}"]`);
        }
        if (lock.mode === "ALT_ADD") {
            return document.querySelector(
                `[data-item-id="${lock.itemId}"] [data-travel-plan-alt-new]`);
        }
        if (lock.mode === "ADD") {
            return document.querySelector(
                `[data-travel-plan-day-id="${lock.dayId}"] [data-travel-plan-slot]`);
        }
        return document.querySelector(`[data-item-id="${lock.itemId}"]`);
    }

    const REMOTE_LABELS = {
        ADD: "일정 작성 중",
        EDIT: "편집 중",
        ALT_ADD: "대안 작성 중",
        ALT_EDIT: "편집 중"
    };

    /** 다른 사람이 쓰고 있다는 표시와 작성 중 글자를 그 자리에 보여 준다. */
    function renderRemote(lock) {
        const line = lineOf(lock);
        if (!line) return;
        // 내 편집기와 구분되는 class 를 쓴다.
        // (.is-editing 으로 표시하면 정식 갱신이 영원히 미뤄진다)
        line.classList.add("is-remote-editing");

        /*
          남이 이 자리에 새 일정을 쓰는 중이면 "+ 일정 추가" 는 내려 둔다.
          작성 중이라는 말과 추가하라는 말이 한자리에 함께 있으면 뜻이 어긋난다.
          이 표시는 lock 이 살아 있는 동안에만 붙고 clearRemote 가 걷어 낸다.
        */
        if (lock.mode === "ADD") {
            line.classList.add("is-remote-adding");
        }

        let note = line.querySelector("[data-travel-plan-remote-note]");
        if (!note) {
            note = document.createElement("p");
            note.className = "travel-plan-remote-note";
            note.setAttribute("data-travel-plan-remote-note", "");
            const body = line.querySelector(".travel-plan-line-body")
                || line.querySelector(".travel-plan-alt-body");
            if (body) {
                body.prepend(note);
            } else {
                /*
                  추가 슬롯에는 본문 칸이 따로 없다.
                  내려 둔 "+ 일정 추가" 자리에 그대로 넣어야 글자가 같은 줄에 선다.
                */
                const hint = line.querySelector(".travel-plan-slot-hint");
                if (hint) hint.insertAdjacentElement("afterend", note);
                else line.prepend(note);
            }
        }

        // 대안은 조건과 내용 두 줄을 함께 보여 준다. 서버가 준 값을 그대로 쓴다.
        const lines = [`${lock.displayName}님이 ${REMOTE_LABELS[lock.mode] || "편집 중"}`];
        const condition = (lock.conditionLabel || "").trim();
        const draft = (lock.content || "").trim();
        if (condition) lines.push(`조건: ${condition}`);
        if (draft) lines.push(draft);
        note.textContent = lines.join("\n");

        // 대안을 쓰는 동안에는 그 A 줄을 없애는 동작을 잠시 막는다.
        if (lock.mode === "ALT_ADD" || lock.mode === "ALT_EDIT") {
            const item = document.querySelector(`[data-item-id="${lock.itemId}"]`);
            item?.classList.add("is-alt-editing");
            // 대안 목록은 접혀 있는 것이 기본이라, 펴 두지 않으면 작성 중 글자가 보이지 않는다.
            const list = item?.querySelector("[data-travel-plan-alt-list]");
            if (list) {
                list.hidden = false;
                item.querySelector("[data-travel-plan-alt-toggle]")
                    ?.setAttribute("aria-expanded", "true");
            }
        }
    }

    function clearRemote(lock) {
        const line = lineOf(lock);
        if (line) {
            line.classList.remove("is-remote-editing");
            // 자리가 풀렸으니 "+ 일정 추가" 가 다시 나온다.
            line.classList.remove("is-remote-adding");
            line.querySelector("[data-travel-plan-remote-note]")?.remove();
        }
        if (lock && (lock.mode === "ALT_ADD" || lock.mode === "ALT_EDIT")) {
            document.querySelector(`[data-item-id="${lock.itemId}"]`)
                ?.classList.remove("is-alt-editing");
        }
    }

    function applyLock(lock) {
        if (!lock || !lock.lockKey) return;
        // 내가 붙잡고 있는 자리는 내 입력칸이 이미 있으므로 덮어 그리지 않는다.
        if (heldLock === lock.lockKey) return;
        remoteLocks.set(lock.lockKey, lock);
        renderRemote(lock);
    }

    function dropLock(lock) {
        if (!lock || !lock.lockKey) return;
        remoteLocks.delete(lock.lockKey);
        clearRemote(lock);
    }

    function clearAllRemote() {
        Array.from(remoteLocks.values()).forEach(clearRemote);
        remoteLocks.clear();
    }

    function handleEditorEvent(payload) {
        if (payload.type === "SNAPSHOT") {
            // 끊겨 있던 사이의 옛 표시가 남지 않게 통째로 다시 그린다.
            clearAllRemote();
            (payload.locks || []).forEach(applyLock);
            return;
        }
        if (payload.type === "LOCKED" || payload.type === "DRAFT") {
            applyLock(payload.lock);
            return;
        }
        if (payload.type === "UNLOCKED") {
            dropLock(payload.lock);
            // 취소였다면 원본이 다시 보여야 하므로 그 DAY 를 서버에서 다시 읽는다.
            if (payload.lock?.dayId) refreshDay(payload.lock.dayId);
        }
    }

    function handleLockReply(payload) {
        if (payload.type === "SNAPSHOT") {
            handleEditorEvent(payload);
            return;
        }
        const waiting = pendingLocks.get(payload.requestId);
        if (!waiting) return;
        pendingLocks.delete(payload.requestId);
        if (payload.granted) {
            heldLock = payload.lock?.lockKey || null;
            heldLockDetail = payload.lock || null;
            remoteLocks.delete(heldLock);
            // 방 전체 알림이 이 답보다 먼저 도착했을 수 있다.
            // 그때 내 화면에 붙은 "편집 중" 표시를 내 자리로 확정되는 지금 걷어낸다.
            clearRemote(payload.lock);
        }
        waiting(payload);
    }

    // ── 채팅 연결 ───────────────────────────────────────────
    // 구독은 이 파일이 하고, 받은 것을 채팅 화면에 그대로 넘긴다.
    const chatListeners = [];
    const chatReplyListeners = [];
    const pollListeners = [];
    const reconnectListeners = [];

    function notify(listeners, payload) {
        listeners.forEach(listener => {
            try {
                listener(payload);
            } catch (error) {
                // 한 화면의 처리 실패가 다른 구독까지 끊지 않게 한다.
            }
        });
    }

    /** 연결돼 있을 때만 보낸다. 끊겨 있으면 보낸 척하지 않는다. */
    function publishChat(action, body) {
        if (!client.connected) return false;
        client.publish({
            destination: `/app/travel-plans/${planId}/chat/${action}`,
            body: JSON.stringify(body)
        });
        return true;
    }

    /*
      편집 쪽(travel-plan-scheduler.js)과 채팅 쪽(travel-plan-chat.js)이 쓰는 창구.
      연결은 이 파일 하나가 들고 있으므로 여기로만 오간다.
    */
    window.travelPlanRealtime = {
        /** 서버가 자리를 내줄 때까지 기다린다. 내주지 않으면 편집기를 열지 않는다. */
        requestLock(spot) {
            if (!client.connected) return Promise.resolve({ granted: false });
            const requestId = `lock-${++requestSeq}`;
            return new Promise(resolve => {
                pendingLocks.set(requestId, resolve);
                client.publish({
                    destination: `/app/travel-plans/${planId}/editor/lock`,
                    body: JSON.stringify({
                        requestId,
                        // 어떤 종류의 자리인지만 알린다. B 인지 C 인지는 서버가 정한다.
                        mode: spot.mode || null,
                        dayId: spot.dayId ?? null,
                        itemId: spot.itemId ?? null,
                        alternativeId: spot.alternativeId ?? null
                    })
                });
                // 답이 오지 않으면 열지 않는다.
                window.setTimeout(() => {
                    if (!pendingLocks.has(requestId)) return;
                    pendingLocks.delete(requestId);
                    resolve({ granted: false });
                }, 3000);
            });
        },

        /**
         * 작성 중 값을 보낸다.
         * 대안은 조건과 내용이 함께 오므로 상대 화면이 두 칸을 같은 시점 값으로 본다.
         */
        sendDraft(draft) {
            if (!heldLock || !client.connected) return;
            const payload = typeof draft === "string" ? { content: draft } : (draft || {});
            client.publish({
                destination: `/app/travel-plans/${planId}/editor/draft`,
                body: JSON.stringify({
                    lockKey: heldLock,
                    conditionLabel: payload.conditionLabel || "",
                    content: payload.content || ""
                })
            });
        },

        releaseLock() {
            const lockKey = heldLock;
            heldLock = null;
            heldLockDetail = null;
            if (!lockKey || !client.connected) return;
            client.publish({
                destination: `/app/travel-plans/${planId}/editor/unlock`,
                body: JSON.stringify({ lockKey })
            });
        },

        /** 다른 사람이 붙잡고 있는 자리인지. 열기 전에 화면이 먼저 확인한다. */
        isLockedByOther(spot) {
            const key = lockKeyOf(spot);
            return key !== heldLock && remoteLocks.has(key);
        },

        /** 이 A 일정 밑에서 누군가 대안을 쓰고 있는지. 그 줄을 없애는 동작을 잠시 막는다. */
        hasAlternativeEditing(itemId) {
            const prefix = [`ALT_ADD:${itemId}`];
            if (remoteLocks.has(prefix[0]) || heldLock === prefix[0]) return true;
            return Array.from(remoteLocks.values())
                    .concat(heldLockDetail ? [heldLockDetail] : [])
                    .some(lock => lock.mode === "ALT_EDIT"
                            && String(lock.itemId) === String(itemId));
        },

        refreshDay,

        /*
          채팅 화면이 쓰는 연결 API.
          새 StompJs.Client 를 만들지 않고 이 연결 위에 얹는다.

          @param onEvent 방 전체로 오는 채팅 알림(MESSAGE_CREATED / MESSAGE_DELETED)
          @param onReply 나에게만 오는 처리 결과(실패 사유, 안 읽은 개수)
        */
        subscribeChat(onEvent, onReply) {
            if (onEvent) chatListeners.push(onEvent);
            if (onReply) chatReplyListeners.push(onReply);
        },

        /**
         * 투표 알림을 받는다.
         * 만들기는 기존 HTTP POST 로 저장하므로 보내는 쪽은 없다.
         */
        subscribePolls(onEvent) {
            if (onEvent) pollListeners.push(onEvent);
        },

        /** 끊겼다 다시 붙었을 때. 놓친 채팅을 그때 다시 맞춘다. */
        onReconnected(handler) {
            if (handler) reconnectListeners.push(handler);
        },

        /** 보낸 사람은 서버가 정한다. 여기서는 내용만 보낸다. */
        sendChat(content) {
            return publishChat("send", { content });
        },

        deleteChatMessage(messageId) {
            return publishChat("delete", { messageId });
        },

        /**
         * 반응 남기기 / 거두기. 같은 것을 다시 보내면 거둔다(가르는 것은 서버다).
         * 누가 눌렀는지도 지금 개수도 보내지 않는다.
         */
        reactChatMessage(messageId, reactionType) {
            return publishChat("react", { messageId, reactionType });
        },

        /** @param lastReadMessageId 없으면 서버가 이 방의 마지막 메시지로 본다 */
        markChatRead(lastReadMessageId) {
            return publishChat("read", { lastReadMessageId: lastReadMessageId ?? null });
        },

        isConnected() {
            return client.connected;
        }
    };

    // ── 연결 ────────────────────────────────────────────────
    const client = new StompJs.Client({
        brokerURL: `${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`,
        // 라이브러리가 알아서 다시 붙는다. 직접 재시도 루프를 만들지 않는다.
        reconnectDelay: 5000,
        heartbeatIncoming: 10000,
        heartbeatOutgoing: 10000
    });

    // 처음 그린 화면은 이미 최신이라 따라잡을 것이 없다. 다시 붙었을 때만 맞춘다.
    let connectedBefore = false;

    client.onConnect = () => {
        // 다시 붙을 때마다 구독과 인사를 새로 한다.
        // 서버는 연결(sessionId) 단위로 세므로 끊긴 연결이 쌓이지 않는다.
        client.subscribe(`/topic/travel-plans/${planId}/presence`, message => {
            try {
                const payload = JSON.parse(message.body);
                renderPresence(payload.onlineMemberIds, payload.onlineCount);
            } catch (error) {
                // 알 수 없는 형식이면 표시를 건드리지 않는다.
            }
        });

        // 변경 알림은 "이 DAY 를 다시 읽어라" 는 신호일 뿐이다. 내용은 서버에서 다시 가져온다.
        client.subscribe(`/topic/travel-plans/${planId}/schedule`, message => {
            try {
                const payload = JSON.parse(message.body);
                if (payload.type === "PLAN_COMPLETED") {
                    // 이 방은 더 이상 고칠 수 없다. 완료 쪽이 화면을 바꾼다.
                    document.dispatchEvent(new CustomEvent("travelplan:plan-completed"));
                    return;
                }
                if (payload.type === "PLAN_DELETED") {
                    // 방장이 방을 접었다. 완료와 달리 남는 것이 없어 그대로 목록으로 나간다.
                    document.dispatchEvent(new CustomEvent("travelplan:plan-deleted"));
                    return;
                }
                if (payload.changeType === "DASHBOARD_UPDATED") {
                    document.dispatchEvent(new CustomEvent("travelplan:dashboard-updated"));
                    return;
                }
                (payload.affectedDayIds || []).forEach(refreshDay);
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });

        /*
          참여자 명단. 누가 들어오고 나갔는지는 싣지 않고 "다시 읽어라" 만 온다.
          방장이 바뀌었을 수도 있으므로 방장 전용 자리도 함께 다시 읽는다.
          (누가 방장인지는 서버가 답한다. 알림에는 들어 있지 않다)
        */
        client.subscribe(`/topic/travel-plans/${planId}/members`, () => {
            refreshMembers();
            refreshOwnerActions();
        });

        // 내가 이 방에서 빠졌다. 곧 서버가 이 연결을 끊는다.
        client.subscribe("/user/queue/travel-plan-access", message => {
            try {
                const payload = JSON.parse(message.body);
                if (payload.type === "ACCESS_REVOKED") revokeAccess(payload.message);
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다. 연결은 서버가 끊는다.
            }
        });

        // 작성 중 상태. 방 전체 알림과, 내 잠금 요청의 답이 오는 개인 큐 두 갈래다.
        client.subscribe(`/topic/travel-plans/${planId}/editor`, message => {
            try {
                handleEditorEvent(JSON.parse(message.body));
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });
        client.subscribe("/user/queue/travel-plan-editor", message => {
            try {
                handleLockReply(JSON.parse(message.body));
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });

        // 채팅. 방 전체 알림과, 나에게만 오는 처리 결과 두 갈래다.
        // 패널이 닫혀 있어도 구독은 유지한다. 그래야 안 읽은 개수가 쌓인다.
        client.subscribe(`/topic/travel-plans/${planId}/chat`, message => {
            try {
                notify(chatListeners, JSON.parse(message.body));
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });
        client.subscribe("/user/queue/travel-plan-chat", message => {
            try {
                notify(chatReplyListeners, JSON.parse(message.body));
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });

        // 투표. 만들기는 HTTP 로 저장하고, 저장된 뒤의 알림만 여기로 온다.
        client.subscribe(`/topic/travel-plans/${planId}/polls`, message => {
            try {
                notify(pollListeners, JSON.parse(message.body));
            } catch (error) {
                // 알 수 없는 형식이면 화면을 건드리지 않는다.
            }
        });

        client.publish({ destination: `/app/travel-plans/${planId}/presence/join` });

        if (connectedBefore) {
            document.dispatchEvent(new CustomEvent("travelplan:dashboard-updated"));
            resyncSchedule();
            // 끊겨 있던 사이의 명단 변경은 다시 받을 수 없다. 지금 명단을 다시 읽는다.
            refreshMembers();
            // 그사이 방장이 바뀌었을 수도 있다.
            refreshOwnerActions();
            // 끊겨 있던 사이의 채팅은 다시 받을 수 없다. 채팅 쪽이 그때 다시 읽는다.
            notify(reconnectListeners, null);
            // 끊겨 있던 사이에 사라진 옛 "편집 중" 표시가 남지 않게 지금 상태를 다시 받는다.
            heldLock = null;
            heldLockDetail = null;
            clearAllRemote();
            client.publish({ destination: `/app/travel-plans/${planId}/editor/sync` });
        }
        connectedBefore = true;
    };

    client.onWebSocketClose = () => {
        // 끊긴 동안에는 아무도 온라인으로 두지 않는다.
        renderPresence([], 0);
    };

    /*
      서버가 구독을 거절했다. 방에서 빠진 뒤 다시 붙으려 할 때 여기로 온다.
      (개인 알림을 놓쳤더라도 이 길로 정리된다)
      다시 붙기를 되풀이하지 않고 목록으로 돌아간다.
    */
    client.onStompError = frame => {
        const reason = frame?.headers?.message || "";
        if (reason.includes("참여")) revokeAccess();
    };

    client.activate();

    // 페이지를 떠날 때는 연결을 정리해 서버가 곧바로 알아채게 한다.
    window.addEventListener("pagehide", () => client.deactivate());
});
