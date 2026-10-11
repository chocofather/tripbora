/*
  방 채팅 화면.

  연결은 들고 있지 않다. STOMP client 는 travel-plan-realtime.js 하나뿐이고,
  여기서는 그 연결이 내어 준 작은 API 위에서 화면·기록·안 읽은 개수만 다룬다.

  대화 기록의 기준은 언제나 DB 다. 화면은 서버에서 받은 것만 그린다.
  사용자가 쓴 글이므로 DOM 에 넣을 때는 textContent 만 쓴다.
*/
document.addEventListener("DOMContentLoaded", () => {
    const root = document.querySelector("[data-travel-plan-chat]");
    if (!root) return;

    const planId = root.getAttribute("data-chat-plan-id");
    // 내 메시지인지 가리는 데만 쓰는 방 안 번호다. 계정 정보가 아니다.
    const myMemberId = root.getAttribute("data-current-member-id");
    if (!planId) return;

    const toggle = root.querySelector("[data-travel-plan-chat-toggle]");
    const badge = root.querySelector("[data-travel-plan-chat-badge]");
    const panel = document.querySelector("[data-travel-plan-chat-panel]");
    if (!panel) return;

    const body = panel.querySelector("[data-travel-plan-chat-body]");
    const list = panel.querySelector("[data-travel-plan-chat-list]");
    const empty = panel.querySelector("[data-travel-plan-chat-empty]");
    const start = panel.querySelector("[data-travel-plan-chat-start]");
    const jump = panel.querySelector("[data-travel-plan-chat-jump]");
    const form = panel.querySelector("[data-travel-plan-chat-form]");
    const input = panel.querySelector("[data-travel-plan-chat-input]");
    const notice = panel.querySelector("[data-travel-plan-chat-error]");

    /** 맨 위에서 이만큼 안쪽에 들어오면 이전 대화를 미리 가져온다. */
    const OLDER_TRIGGER_PX = 80;

    let loaded = false;
    let loading = false;
    // 이전 대화는 한 번에 한 페이지만 가져온다. 위에서 스크롤이 여러 번 튀어도 겹치지 않는다.
    let loadingOlder = false;
    let hasMoreOlder = false;
    // 앞 페이지를 물어볼 기준. 대화와 투표가 표가 달라 각자 하나씩 든다.
    let beforeMessageId = null;
    let beforePollId = null;
    let sending = false;
    let unreadCount = 0;
    // 아래를 보고 있지 않을 때 도착한 메시지 수. [새 메시지 N개] 에 쓴다.
    let pendingNew = 0;

    function realtime() {
        return window.travelPlanRealtime;
    }

    /* 여닫는 상태는 하나뿐이다. 떠 있는 버튼으로 열고 × 로 닫는다. */
    function isOpen() {
        return !panel.hidden;
    }

    /** 지금 이 화면이 대화를 실제로 읽고 있는 상태인지. 닫혀 있으면 읽음 처리하지 않는다. */
    function isReading() {
        return isOpen() && document.visibilityState === "visible";
    }

    // ── 안 읽은 개수 ────────────────────────────────────────

    function renderUnread() {
        if (!badge) return;
        badge.textContent = unreadCount > 99 ? "99+" : String(unreadCount);
        badge.hidden = unreadCount <= 0;
    }

    function setUnread(count) {
        unreadCount = Math.max(0, Number(count) || 0);
        renderUnread();
    }

    async function loadUnread() {
        try {
            const response = await fetch(`/travel-plans/${planId}/chat/unread`, {
                headers: { "X-Requested-With": "XMLHttpRequest" }
            });
            if (!response.ok) return;
            setUnread((await response.json()).unreadCount);
        } catch (error) {
            // 개수를 못 읽어도 대화 자체는 쓸 수 있어야 한다.
        }
    }

    /** 지금 화면에 그려진 마지막 메시지까지 읽었다고 서버에 알린다. */
    function markRead() {
        if (!isReading()) return;
        const last = list.lastElementChild;
        if (!last) return;
        const lastId = Number(last.getAttribute("data-message-id"));
        if (!lastId) return;
        // 서버가 이미 그 자리를 읽은 것으로 알고 있으면 UPDATE 하지 않는다.
        realtime()?.markChatRead(lastId);
    }

    // ── 메시지 그리기 ───────────────────────────────────────

    function isMine(message) {
        return myMemberId != null && String(message.memberId) === String(myMemberId);
    }

    function timeTextOf(createdAt) {
        if (!createdAt) return "";
        return new Date(createdAt).toLocaleTimeString("ko-KR", {
            hour: "numeric",
            minute: "2-digit"
        });
    }

    /**
     * 지운 메시지에는 메뉴를 달지 않는다. 남의 메시지에도 달지 않는다.
     *
     * <p>메시지 객체가 아니라 번호만 받는다. 기록에서 온 줄과 실시간으로 온 줄은
     * 담긴 필드 이름이 서로 달라(messageId / id), 여기서 객체를 다시 뒤지면
     * 한쪽에서 undefined 가 나가 서버가 어느 메시지인지 알 수 없게 된다.
     * 어느 번호를 지울지는 부르는 쪽에서 정해 넘긴다.
     *
     * @param messageId 지울 메시지 번호
     */
    function deleteMenuOf(messageId) {
        const menu = document.createElement("div");
        menu.className = "travel-plan-chat-menu";
        menu.setAttribute("data-travel-plan-chat-menu", "");

        const button = document.createElement("button");
        button.type = "button";
        button.className = "travel-plan-chat-menu-button";
        button.setAttribute("aria-haspopup", "true");
        button.setAttribute("aria-expanded", "false");
        button.setAttribute("aria-label", "메시지 메뉴");
        button.textContent = "⋯";

        const panelEl = document.createElement("div");
        panelEl.className = "travel-plan-chat-menu-list";
        panelEl.hidden = true;

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "travel-plan-chat-menu-action";
        remove.textContent = "삭제";
        remove.addEventListener("click", () => {
            panelEl.hidden = true;
            button.setAttribute("aria-expanded", "false");
            if (!window.confirm("이 메시지를 삭제할까요?")) return;
            realtime()?.deleteChatMessage(messageId);
        });

        button.addEventListener("click", event => {
            event.stopPropagation();
            const willOpen = panelEl.hidden;
            closeMenus(null);
            panelEl.hidden = !willOpen;
            button.setAttribute("aria-expanded", String(willOpen));
        });

        panelEl.append(remove);
        menu.append(button, panelEl);
        return menu;
    }

    /**
     * 대화 한 줄.
     *
     * <p>내 것은 오른쪽, 남의 것은 왼쪽에 놓인다. 가르는 것은 class 하나뿐이고
     * 자리는 CSS 가 잡는다. 보낸 사람 이름과 시각은 늘 만들어 두고,
     * 어느 것을 보여 줄지는 아래 regroup() 이 이웃한 줄을 보고 정한다.
     */
    function messageNode(message) {
        const item = document.createElement("li");
        item.className = "travel-plan-chat-message";
        item.setAttribute("data-message-id", message.messageId);
        // 묶음을 다시 셀 때 쓰는 값. 화면에 글자로 나가지 않는다.
        item.setAttribute("data-member-id", message.memberId ?? "");
        if (message.createdAt) item.setAttribute("data-created-at", message.createdAt);
        if (isMine(message)) item.classList.add("is-mine");
        if (message.deleted) item.classList.add("is-deleted");

        const sender = document.createElement("p");
        sender.className = "travel-plan-chat-sender";
        // 이름도 사용자가 정한 값이라 그대로 글자로만 넣는다.
        // 내 메시지는 오른쪽에 서는 것으로 이미 구분되므로 이름을 적지 않는다.
        sender.textContent = isMine(message) ? "" : message.displayName;

        const row = document.createElement("div");
        row.className = "travel-plan-chat-row";

        const bubble = document.createElement("div");
        bubble.className = "travel-plan-chat-bubble";

        const content = document.createElement("p");
        content.className = "travel-plan-chat-content";
        content.setAttribute("data-travel-plan-chat-content", "");
        // 채팅은 사용자 입력이다. HTML 로 넣으면 태그가 화면에서 실행된다.
        content.textContent = message.deleted
            ? "삭제된 메시지입니다."
            : message.content;

        const time = document.createElement("time");
        time.className = "travel-plan-chat-time";
        if (message.createdAt) {
            time.dateTime = new Date(message.createdAt).toISOString();
        }
        time.textContent = timeTextOf(message.createdAt);

        const reactions = document.createElement("div");
        reactions.className = "travel-plan-chat-reactions";
        reactions.setAttribute("data-travel-plan-chat-reactions", "");

        bubble.append(content);
        row.append(bubble, time);
        item.append(sender, row, reactions);
        if (!message.deleted) {
            // 지워진 메시지에는 반응을 달 수도, 이미 달린 것을 볼 수도 없다.
            row.append(reactionPickerOf(message));
            renderReactions(item, message.reactions);
        }
        if (isMine(message) && !message.deleted) {
            // 줄에 적어 둔 것과 같은 번호를 넘긴다.
            row.append(deleteMenuOf(message.messageId));
        }
        return item;
    }

    // ── 반응 ────────────────────────────────────────────────

    /*
      쓸 수 있는 반응. 서버의 TravelPlanChatReactionType 과 같은 이름을 쓴다.
      보내는 것은 이름뿐이고, 무엇이 허용되는지는 서버가 다시 본다.
      전체 이모지 목록(EMOJI_ROWS)과는 다른 자리다.
    */
    const REACTION_TYPES = [
        { type: "LIKE", emoji: "👍" },
        { type: "HEART", emoji: "❤️" },
        { type: "LAUGH", emoji: "😂" },
        { type: "WOW", emoji: "😮" },
        { type: "SAD", emoji: "😢" },
        { type: "PARTY", emoji: "🎉" }
    ];

    function toggleReaction(messageId, reactionType) {
        realtime()?.reactChatMessage(messageId, reactionType);
    }

    /** 말풍선 옆의 작은 진입점. 눌러야 여섯 가지가 펼쳐진다 */
    function reactionPickerOf(message) {
        const holder = document.createElement("div");
        holder.className = "travel-plan-chat-react";
        holder.setAttribute("data-travel-plan-chat-react", "");

        const button = document.createElement("button");
        button.type = "button";
        button.className = "travel-plan-chat-react-button";
        button.setAttribute("aria-haspopup", "true");
        button.setAttribute("aria-expanded", "false");
        button.setAttribute("aria-label", "반응 남기기");
        button.textContent = "☺";

        const menu = document.createElement("div");
        menu.className = "travel-plan-chat-react-menu";
        menu.hidden = true;

        REACTION_TYPES.forEach(({ type, emoji }) => {
            const choice = document.createElement("button");
            choice.type = "button";
            choice.className = "travel-plan-chat-react-choice";
            choice.setAttribute("aria-label", type);
            choice.textContent = emoji;
            choice.addEventListener("click", event => {
                event.stopPropagation();
                menu.hidden = true;
                button.setAttribute("aria-expanded", "false");
                toggleReaction(message.messageId, type);
            });
            menu.append(choice);
        });

        button.addEventListener("click", event => {
            event.stopPropagation();
            const willOpen = menu.hidden;
            closeMenus(null);
            closeReactionMenus(null);
            menu.hidden = !willOpen;
            button.setAttribute("aria-expanded", String(willOpen));
        });

        holder.append(button, menu);
        return holder;
    }

    function closeReactionMenus(except) {
        list.querySelectorAll("[data-travel-plan-chat-react]").forEach(holder => {
            const menu = holder.querySelector(".travel-plan-chat-react-menu");
            if (!menu || menu === except) return;
            menu.hidden = true;
            holder.querySelector(".travel-plan-chat-react-button")
                ?.setAttribute("aria-expanded", "false");
        });
    }

    /**
     * 말풍선 아래의 반응 알약.
     *
     * <p>서버가 준 요약을 그대로 그린다. 개수를 화면에서 더하거나 빼지 않는다.
     * 하나도 없으면 그 자리째 비워 둔다.
     */
    function renderReactions(item, reactions) {
        const holder = item.querySelector("[data-travel-plan-chat-reactions]");
        if (!holder) return;
        const messageId = item.getAttribute("data-message-id");
        const summary = Array.isArray(reactions) ? reactions : [];

        holder.replaceChildren(...summary
            .filter(reaction => reaction && reaction.count > 0)
            .map(reaction => {
                const pill = document.createElement("button");
                pill.type = "button";
                pill.className = "travel-plan-chat-reaction";
                if (reaction.reacted) pill.classList.add("is-mine");
                pill.setAttribute("data-reaction-type", reaction.type);
                pill.setAttribute("aria-pressed", String(Boolean(reaction.reacted)));
                pill.setAttribute("aria-label", `${reaction.type} ${reaction.count}`);
                // 이모지도 개수도 사용자에게 보이는 값이라 글자로만 넣는다.
                pill.textContent = `${reaction.emoji} ${reaction.count}`;
                // 알약을 눌러도 같은 반응을 남기거나 거둘 수 있다.
                pill.addEventListener("click", event => {
                    event.stopPropagation();
                    toggleReaction(Number(messageId), reaction.type);
                });
                return pill;
            }));
    }

    /**
     * 반응이 달라졌다는 알림을 받았다.
     *
     * <p>개수를 더하지 않고 그 메시지의 요약을 서버에서 다시 읽는다.
     * 그래서 같은 알림이 두 번 와도 숫자가 어긋나지 않는다.
     */
    async function refreshReactions(messageId) {
        const item = nodeOf(messageId);
        if (!item || item.classList.contains("is-deleted")) return;
        try {
            const response = await fetch(
                `/travel-plans/${planId}/chat/messages/${messageId}/reactions`,
                { headers: { "X-Requested-With": "XMLHttpRequest" } });
            if (!response.ok) return;
            renderReactions(item, (await response.json()).reactions);
        } catch (error) {
            // 못 읽어도 대화 자체는 그대로 쓸 수 있어야 한다.
        }
    }

    // ── 연속 메시지 묶기 ────────────────────────────────────

    /** 같은 사람이 이만큼 안에 이어서 보내면 한 덩어리로 본다. */
    const GROUP_WINDOW_MS = 3 * 60 * 1000;

    function timeValueOf(node) {
        const raw = node.getAttribute("data-created-at");
        if (!raw) return null;
        const value = new Date(raw).getTime();
        return Number.isNaN(value) ? null : value;
    }

    /** 그 줄이 속한 날. 날짜가 바뀌는 자리에만 구분선을 넣는 데 쓴다. */
    function dayKeyOf(node) {
        const value = timeValueOf(node);
        return value == null ? null : new Date(value).toDateString();
    }

    function isMessageNode(node) {
        return node.classList.contains("travel-plan-chat-message");
    }

    /** 같은 사람이 짧은 사이에 이어서 보낸 줄인지. */
    function continues(previous, node) {
        const sender = previous.getAttribute("data-member-id");
        if (!sender || sender !== node.getAttribute("data-member-id")) return false;
        const before = timeValueOf(previous);
        const now = timeValueOf(node);
        if (before == null || now == null) return false;
        return now - before <= GROUP_WINDOW_MS;
    }

    function dateDividerNode(node) {
        const divider = document.createElement("li");
        divider.className = "travel-plan-chat-date";
        divider.setAttribute("data-travel-plan-chat-date", "");
        const label = document.createElement("span");
        label.textContent = new Date(timeValueOf(node)).toLocaleDateString("ko-KR", {
            year: "numeric",
            month: "long",
            day: "numeric",
            weekday: "short"
        });
        divider.append(label);
        return divider;
    }

    /**
     * 이웃한 줄을 보고 묶음을 다시 센다.
     *
     * <p>DB 의 메시지를 합치거나 고쳐 쓰지 않는다. 여기서 정하는 것은
     * 이름을 어느 줄에 보일지, 시각을 어느 줄에 보일지, 날짜 구분선을
     * 어디에 둘지뿐이다. 그래서 언제 다시 불러도 결과가 같다.
     *
     * <p>앞에 끼워 넣은 뒤에도 반드시 다시 부른다. 이어 붙인 자리의
     * 묶음은 새로 온 줄과 원래 있던 줄을 함께 봐야 정해진다.
     */
    function regroup() {
        list.querySelectorAll("[data-travel-plan-chat-date]").forEach(node => node.remove());

        let previous = null;
        let previousDay = null;
        Array.from(list.children).forEach(node => {
            const day = dayKeyOf(node);
            if (day && day !== previousDay) {
                if (previousDay !== null) list.insertBefore(dateDividerNode(node), node);
                previousDay = day;
                // 날짜가 바뀌면 묶음도 거기서 끊는다.
                previous = null;
            }
            if (!isMessageNode(node)) {
                // 투표 알림이 끼면 대화의 묶음도 끊긴다.
                previous = null;
                return;
            }

            const continued = previous !== null && continues(previous, node);
            node.classList.toggle("is-continued", continued);
            // 시각은 묶음의 마지막 줄에만 남긴다.
            if (previous) previous.classList.toggle("is-group-end", !continued);
            previous = node;
        });
        if (previous) previous.classList.add("is-group-end");
    }

    /*
      "OO님이 새 투표를 만들었어요".

      대화가 아니라 그 사이에 있었던 일이라, 말풍선이 아니라 차분한 알림 줄로 둔다.
      투표 자체는 travel_plan_polls 에 있고 여기에는 옮겨 적지 않는다.
      누르면 투표 센터가 열린다(여는 것은 투표 쪽이 맡는다).
    */
    function pollNoticeNode(item) {
        const node = document.createElement("li");
        node.className = "travel-plan-chat-notice";
        node.setAttribute("data-poll-notice-id", item.pollId);
        // 날짜 구분선이 알림 줄도 함께 보고 자리를 잡는다.
        if (item.createdAt) node.setAttribute("data-created-at", item.createdAt);

        const button = document.createElement("button");
        button.type = "button";
        button.className = "travel-plan-chat-notice-body";

        const head = document.createElement("span");
        head.className = "travel-plan-chat-notice-head";
        // 이름도 사용자가 정한 값이라 그대로 글자로만 넣는다.
        head.textContent = `📊 ${item.creatorDisplayName}님이 새 투표를 만들었어요.`;

        const pollTitle = document.createElement("span");
        pollTitle.className = "travel-plan-chat-notice-title";
        pollTitle.textContent = item.pollTitle;

        const time = document.createElement("time");
        time.className = "travel-plan-chat-time";
        if (item.createdAt) {
            time.dateTime = new Date(item.createdAt).toISOString();
        }
        time.textContent = timeTextOf(item.createdAt);

        button.append(head, pollTitle);
        button.addEventListener("click", () => {
            document.dispatchEvent(new CustomEvent("travelplan:poll-center-open", {
                detail: { pollId: item.pollId }
            }));
        });

        node.append(button, time);
        return node;
    }

    /** 대화와 투표 알림이 한 줄기로 온다. 종류에 맞는 줄을 만든다. */
    function itemNode(item) {
        return item.type === "POLL_CREATED" ? pollNoticeNode(item) : messageNode(item);
    }

    /** WebSocket 으로 온 메시지를 타임라인 한 줄과 같은 모양으로 맞춘다. */
    function messageItem(message) {
        return {
            type: "MESSAGE",
            createdAt: message.createdAt,
            messageId: message.id,
            memberId: message.memberId,
            displayName: message.displayName,
            content: message.content,
            deleted: message.deleted,
            // 갓 도착한 메시지에는 아직 아무 반응도 없다.
            reactions: []
        };
    }

    function nodeOf(messageId) {
        return list.querySelector(`[data-message-id="${messageId}"]`);
    }

    function noticeNodeOf(pollId) {
        return list.querySelector(`[data-poll-notice-id="${pollId}"]`);
    }

    function renderEmptyState() {
        if (empty) empty.hidden = list.childElementCount > 0;
        // 더 가져올 것이 없을 때만 대화의 시작을 알린다.
        if (start) start.hidden = hasMoreOlder || list.childElementCount === 0;
    }

    // ── 스크롤 ──────────────────────────────────────────────

    /** 사용자가 지금 맨 아래를 보고 있는지. 위에서 옛 대화를 읽는 중이면 끌어내리지 않는다. */
    function isAtBottom() {
        return body.scrollHeight - body.scrollTop - body.clientHeight < 40;
    }

    function scrollToBottom() {
        body.scrollTop = body.scrollHeight;
        pendingNew = 0;
        renderJump();
    }

    function renderJump() {
        if (!jump) return;
        jump.hidden = pendingNew <= 0;
        jump.textContent = `새 메시지 ${pendingNew}개`;
    }

    // ── 기록 조회 ───────────────────────────────────────────

    function showError(message) {
        if (!notice) return;
        notice.textContent = message || "처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        notice.hidden = false;
    }

    function clearError() {
        if (!notice) return;
        notice.hidden = true;
        notice.textContent = "";
    }

    /**
     * 대화와 투표 알림을 시간 순서로 함께 받아 온다.
     * 표가 둘이라 기준도 둘이다. 대화 번호만 보내면 그 사이의 투표 알림이 빠진다.
     */
    async function fetchTimeline(beforeMessageId, beforePollId) {
        const query = new URLSearchParams();
        if (beforeMessageId != null) query.set("beforeMessageId", beforeMessageId);
        if (beforePollId != null) query.set("beforePollId", beforePollId);
        const suffix = query.toString() ? `?${query}` : "";

        const response = await fetch(`/travel-plans/${planId}/chat/timeline${suffix}`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        if (!response.ok) throw new Error("chat timeline unavailable");
        return response.json();
    }

    /** 다음 앞 페이지를 물어볼 기준. 서버가 알려 준 것을 그대로 들고 있는다. */
    function rememberCursor(payload) {
        hasMoreOlder = !!payload.hasMore;
        // 그 쪽이 끝났으면 null 이 온다. 그때는 다시 묻지 않는다.
        beforeMessageId = payload.nextBeforeMessageId ?? null;
        beforePollId = payload.nextBeforePollId ?? null;
    }

    /** 처음 열 때. 이미 그려 둔 것이 있으면 지우고 서버 내용으로 다시 맞춘다. */
    async function loadRecent() {
        if (loading) return;
        loading = true;
        try {
            const payload = await fetchTimeline(null, null);
            list.replaceChildren(...payload.items.map(itemNode));
            regroup();
            rememberCursor(payload);
            loaded = true;
            renderEmptyState();
            scrollToBottom();
            markRead();
            clearError();
        } catch (error) {
            showError("대화를 불러오지 못했습니다.");
        } finally {
            loading = false;
        }
    }

    /**
     * 위로 올려 이전 대화를 가져온다. 누를 버튼은 없다.
     *
     * <p>앞에 끼워 넣으면 보고 있던 메시지가 그만큼 아래로 밀린다.
     * 늘어난 높이만큼 스크롤을 내려 같은 메시지가 같은 자리에 남게 한다.
     */
    async function loadOlder() {
        // 한 번에 한 페이지만. 더 없으면 다시 묻지 않는다.
        if (loadingOlder || !hasMoreOlder) return;
        loadingOlder = true;
        try {
            const payload = await fetchTimeline(beforeMessageId, beforePollId);
            const before = body.scrollHeight;
            list.prepend(...payload.items.map(itemNode));
            /*
              묶음을 다시 세면 이름·시각·날짜 줄이 생기거나 사라져 높이가 달라진다.
              자리를 맞추기 전에 끝내야 보고 있던 메시지가 제자리에 남는다.
            */
            regroup();
            body.scrollTop += body.scrollHeight - before;
            rememberCursor(payload);
            renderEmptyState();
        } catch (error) {
            showError("이전 대화를 불러오지 못했습니다.");
        } finally {
            loadingOlder = false;
        }
    }

    // ── 실시간 수신 ─────────────────────────────────────────

    /** 새 줄 하나를 지금 보고 있는 자리에 맞춰 붙인다. */
    function appendItem(node, countsAsNew) {
        const stick = isAtBottom();
        list.append(node);
        // 앞 줄과 이어지는지는 붙여 놓고 봐야 안다.
        regroup();
        renderEmptyState();
        if (stick) {
            scrollToBottom();
        } else if (countsAsNew) {
            pendingNew += 1;
            renderJump();
        }
    }

    /**
     * 투표가 만들어졌다.
     * 기록을 다시 읽어도 서버가 같은 알림을 같은 자리에 돌려주므로,
     * 여기서는 지금 화면에 한 줄 붙이기만 한다.
     */
    function onPollCreated(poll) {
        if (!poll || poll.id == null) return;
        // 저장 응답과 방 알림이 겹쳐도 알림 줄은 하나다.
        if (!loaded || noticeNodeOf(poll.id)) return;
        appendItem(pollNoticeNode({
            type: "POLL_CREATED",
            createdAt: poll.createdAt,
            pollId: poll.id,
            creatorDisplayName: poll.createdByDisplayName,
            pollTitle: poll.title
        }), true);
    }

    /**
     * 방으로 온 새 메시지.
     *
     * <p>여기 오는 것은 서버가 보낸 원본(TravelPlanChatMessageDto)이라 번호가 id 다.
     * 기록에서 오는 줄은 messageId 라 이름이 다르므로, 화면에 넣기 전에
     * messageItem() 이 둘을 같은 모양으로 맞춘다. 그 뒤로는 messageId 하나만 쓴다.
     */
    function onMessageCreated(message) {
        if (!message) return;
        // 아직 기록을 읽지 않았다면 그릴 자리가 없다. 열 때 서버에서 통째로 받는다.
        if (loaded && !nodeOf(message.id)) {
            appendItem(messageNode(messageItem(message)), !isMine(message));
        }

        if (isMine(message)) return;
        if (isReading()) {
            markRead();
        } else {
            // 패널이 닫혀 있는 동안 온 메시지를 읽음으로 처리하지 않는다.
            setUnread(unreadCount + 1);
        }
    }

    function onMessageDeleted(messageId) {
        const node = nodeOf(messageId);
        if (!node) return;
        node.classList.add("is-deleted");
        const content = node.querySelector("[data-travel-plan-chat-content]");
        if (content) content.textContent = "삭제된 메시지입니다.";
        // 지운 뒤에는 메뉴가 필요 없다.
        node.querySelector("[data-travel-plan-chat-menu]")?.remove();
        /*
          지워진 메시지에는 반응을 달 수도, 이미 달린 것을 볼 수도 없다.
          DB 의 반응 행을 지우지는 않는다(지움은 tombstone 이라 그대로 둔다).
        */
        node.querySelector("[data-travel-plan-chat-react]")?.remove();
        node.querySelector("[data-travel-plan-chat-reactions]")?.replaceChildren();
    }

    // ── 보내기 ──────────────────────────────────────────────

    function send() {
        const content = input.value.trim();
        if (sending || content === "") return;
        if (!realtime()?.isConnected()) {
            showError("연결이 끊겼습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }

        sending = true;
        clearError();
        const sent = realtime().sendChat(content);
        if (!sent) {
            sending = false;
            showError("연결이 끊겼습니다. 잠시 후 다시 시도해 주세요.");
            return;
        }
        // 보낸 내용은 저장이 끝난 뒤 방 알림으로 돌아온다. 미리 그려 두지 않는다.
        input.value = "";
        autoResize();
        sending = false;
        input.focus();
    }

    function autoResize() {
        input.style.height = "auto";
        input.style.height = `${Math.min(input.scrollHeight, 120)}px`;
    }

    // ── 이모지 고르기 ───────────────────────────────────────

    /*
      자주 쓰는 것과 여행에서 자주 나오는 것만 줄별로 모아 둔다.
      그림 파일이 아니라 그냥 글자다. 저장·전송·그리기가 지금과 똑같이 흘러간다.
      (message_type 을 새로 만들지도, 계약을 바꾸지도 않는다)
    */
    const EMOJI_ROWS = [
        ["😀", "😄", "😂", "🥹", "😊", "😍", "😎"],
        ["👍", "👎", "👏", "🙌", "👌"],
        ["❤️", "💕", "🔥", "🎉", "✨"],
        ["😮", "😢", "😭", "😡", "🤔"],
        ["🍽️", "☕", "🍺", "🏨", "✈️", "🚗", "🚌", "🚆"],
        ["🌊", "🌸", "⛰️", "🌙", "📸"]
    ];

    const emojiToggle = panel.querySelector("[data-travel-plan-chat-emoji-toggle]");
    const emojiPanel = panel.querySelector("[data-travel-plan-chat-emoji-panel]");
    const recentSection = panel.querySelector("[data-travel-plan-chat-emoji-recent]");
    const recentGrid = panel.querySelector("[data-travel-plan-chat-emoji-recent-grid]");
    const allGrid = panel.querySelector("[data-travel-plan-chat-emoji-all]");

    /**
     * 고른 이모지를 커서 자리에 끼워 넣는다. 보내지는 않는다.
     *
     * <p>고르고 있던 구간이 있으면 그 자리를 대신한다.
     * 넣은 뒤에는 입력칸으로 돌아가고 커서는 넣은 글자 뒤에 선다.
     */
    function insertEmoji(emoji) {
        if (!input) return;
        const start = input.selectionStart ?? input.value.length;
        const end = input.selectionEnd ?? start;
        input.value = input.value.slice(0, start) + emoji + input.value.slice(end);
        const caret = start + emoji.length;
        input.focus();
        input.setSelectionRange(caret, caret);
        autoResize();
    }

    /*
      최근 고른 이모지.

      이 브라우저에만 남는 편의값이다. 서버로 보내지 않고 DB 에도 두지 않는다.
      (사생활 보호 창이나 저장을 막아 둔 브라우저에서는 읽고 쓰기가 막힌다.
       그래도 고르는 것 자체는 되어야 하므로 실패는 조용히 넘긴다)
    */
    const RECENT_EMOJI_KEY = "travelPlan.chat.recentEmoji";
    const RECENT_EMOJI_LIMIT = 10;

    function readRecentEmoji() {
        try {
            const saved = JSON.parse(window.localStorage.getItem(RECENT_EMOJI_KEY) || "[]");
            if (!Array.isArray(saved)) return [];
            // 저장된 값도 남이 고쳐 넣을 수 있다. 글자만 남기고 개수도 다시 자른다.
            return saved
                .filter(emoji => typeof emoji === "string" && emoji !== "")
                .slice(0, RECENT_EMOJI_LIMIT);
        } catch (error) {
            return [];
        }
    }

    function rememberRecentEmoji(emoji) {
        // 같은 것을 다시 고르면 두 번 쌓지 않고 맨 앞으로 올린다.
        const next = [emoji, ...readRecentEmoji().filter(saved => saved !== emoji)]
            .slice(0, RECENT_EMOJI_LIMIT);
        try {
            window.localStorage.setItem(RECENT_EMOJI_KEY, JSON.stringify(next));
        } catch (error) {
            // 저장하지 못해도 이번에 고른 것은 그대로 들어간다.
        }
        renderRecentEmoji();
    }

    /** 격자에 놓이는 이모지 한 칸. 어느 격자에 있든 하는 일이 같다. */
    function emojiButton(emoji) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "travel-plan-chat-emoji-item";
        button.setAttribute("aria-label", emoji);
        button.textContent = emoji;
        button.addEventListener("click", () => {
            insertEmoji(emoji);
            rememberRecentEmoji(emoji);
            closeEmoji();
        });
        return button;
    }

    /** 최근 목록만 다시 그린다. 하나도 없으면 그 구역째 사라진다. */
    function renderRecentEmoji() {
        if (!recentGrid || !recentSection) return;
        const recent = readRecentEmoji();
        recentSection.hidden = recent.length === 0;
        recentGrid.replaceChildren(...recent.map(emojiButton));
    }

    /**
     * 전체 목록은 한 번만 만든다. 여는 때마다 다시 그리지 않는다.
     *
     * <p>위에 줄로 묶어 적어 둔 것은 고르기 쉬우라고 한 것이고,
     * 화면에서는 한 칸씩 이어 붙여 5칸 격자로 흐른다(칸 나누기는 CSS 가 한다).
     * 줄마다 상자를 만들면 그 상자가 각자 폭을 잡아 격자가 되지 않는다.
     */
    function buildEmojiPanel() {
        if (!allGrid || allGrid.childElementCount > 0) return;
        allGrid.append(...EMOJI_ROWS.flat().map(emojiButton));
    }

    function closeEmoji() {
        if (!emojiPanel) return;
        emojiPanel.hidden = true;
        emojiToggle?.setAttribute("aria-expanded", "false");
    }

    function openEmoji() {
        if (!emojiPanel) return;
        buildEmojiPanel();
        // 다른 탭에서 고른 것이 있을 수 있어 열 때마다 최근 목록은 다시 읽는다.
        renderRecentEmoji();
        emojiPanel.hidden = false;
        emojiToggle?.setAttribute("aria-expanded", "true");
    }

    emojiToggle?.addEventListener("click", event => {
        event.stopPropagation();
        if (emojiPanel?.hidden) openEmoji();
        else closeEmoji();
    });

    // 고르는 중에 바깥을 눌러 닫히지 않도록 창 안의 클릭은 여기서 멈춘다.
    emojiPanel?.addEventListener("click", event => event.stopPropagation());

    // ── 패널 열고 닫기 ──────────────────────────────────────

    function closeMenus(except) {
        list.querySelectorAll("[data-travel-plan-chat-menu]").forEach(menu => {
            const panelEl = menu.querySelector(".travel-plan-chat-menu-list");
            if (!panelEl || panelEl === except) return;
            panelEl.hidden = true;
            menu.querySelector(".travel-plan-chat-menu-button")
                ?.setAttribute("aria-expanded", "false");
        });
    }

    function openPanel() {
        panel.hidden = false;
        toggle?.setAttribute("aria-expanded", "true");
        // 열려 있는 동안에도 토글 버튼은 남아 있고, 누르면 닫힌다
        toggle?.setAttribute("aria-label", "채팅 닫기");
        if (!loaded) {
            loadRecent();
        } else {
            scrollToBottom();
            markRead();
        }
        input?.focus();
        /*
          채팅창이 열렸다고 알린다.
          투표 쪽(travel-plan-poll.js)이 이 신호를 듣고 진행 중인 투표를 그때 읽어 온다.
          (내부 변수를 밖에서 들여다보지 않도록 DOM 이벤트로만 알린다)
        */
        document.dispatchEvent(new CustomEvent("travelplan:chat-opened"));
    }

    function closePanel() {
        panel.hidden = true;
        toggle?.setAttribute("aria-expanded", "false");
        toggle?.setAttribute("aria-label", "채팅 열기");
        closeMenus(null);
    }

    toggle?.addEventListener("click", () => {
        if (panel.hidden) {
            openPanel();
        } else {
            closePanel();
        }
    });

    panel.querySelector("[data-travel-plan-chat-close]")
        ?.addEventListener("click", () => closePanel());

    jump?.addEventListener("click", () => {
        scrollToBottom();
        markRead();
    });

    body.addEventListener("scroll", () => {
        // 위쪽에 가까워지면 이전 대화를 이어서 가져온다.
        if (body.scrollTop <= OLDER_TRIGGER_PX) loadOlder();

        // 아래까지 내려 읽었으면 그때 읽음으로 본다.
        if (!isAtBottom()) return;
        pendingNew = 0;
        renderJump();
        markRead();
    });

    // 다른 탭에 있는 동안 온 메시지는 돌아왔을 때 읽음으로 본다.
    document.addEventListener("visibilitychange", () => {
        if (isReading()) markRead();
    });

    document.addEventListener("click", () => {
        closeMenus(null);
        closeReactionMenus(null);
        // 바깥을 누르면 이모지 창도 함께 닫는다.
        closeEmoji();
    });

    // Esc 로도 닫힌다. 닫은 뒤에는 쓰던 자리로 돌아간다.
    document.addEventListener("keydown", event => {
        if (event.key !== "Escape" || emojiPanel?.hidden !== false) return;
        closeEmoji();
        input?.focus();
    });

    // ── 입력 ────────────────────────────────────────────────

    form?.addEventListener("submit", event => {
        event.preventDefault();
        send();
    });

    let composing = false;
    input?.addEventListener("compositionstart", () => {
        composing = true;
    });
    input?.addEventListener("compositionend", () => {
        composing = false;
    });
    input?.addEventListener("input", autoResize);
    input?.addEventListener("keydown", event => {
        // 조합 중의 Enter 는 글자를 확정하는 것이라 전송으로 보지 않는다.
        // 판단 규칙은 일정 편집기와 같은 곳에 있다.
        if (window.travelPlanIme.isComposing(event, composing)) return;
        // Enter 는 전송, Shift+Enter 는 줄바꿈.
        if (event.key === "Enter" && !event.shiftKey) {
            event.preventDefault();
            send();
        }
    });

    // ── 연결 ────────────────────────────────────────────────

    realtime()?.subscribeChat(payload => {
        if (payload.type === "MESSAGE_CREATED") {
            onMessageCreated(payload.message);
            return;
        }
        if (payload.type === "MESSAGE_DELETED") {
            onMessageDeleted(payload.messageId);
            return;
        }
        if (payload.type === "MESSAGE_REACTION_CHANGED") {
            refreshReactions(payload.messageId);
        }
    }, payload => {
        if (payload.type === "UNREAD") {
            setUnread(payload.unreadCount);
            return;
        }
        // 보내기/지우기 실패는 나에게만 온다. 입력한 내용은 그대로 남아 있다.
        sending = false;
        showError(payload.message);
    });

    /*
      투표가 만들어졌다는 것은 대화 사이에 있었던 일이라 채팅 흐름에도 한 줄 남는다.
      투표 자체를 여기서 다루지는 않는다. 그것은 투표 센터가 맡는다.
    */
    realtime()?.subscribePolls(payload => {
        if (payload.type === "POLL_CREATED") onPollCreated(payload.poll);
    });

    /*
      끊겨 있던 사이의 채팅은 다시 받을 수 없다.
      지난 알림을 되돌려 주는 장치를 만들지 않고, 그때 서버에서 다시 읽는다.
    */
    realtime()?.onReconnected(() => {
        loadUnread();
        if (isOpen()) loadRecent();
    });

    loadUnread();
});
