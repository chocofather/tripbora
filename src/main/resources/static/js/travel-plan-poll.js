/*
  투표 센터.

  진행 중·지난 투표를 보는 곳과 새 투표를 만드는 곳이 창 하나 안에 있다.
  창 위에 창을 겹치지 않고, 두 화면이 같은 자리에서 서로 바뀐다.

  연결은 들고 있지 않다. STOMP client 는 travel-plan-realtime.js 하나뿐이고,
  저장은 기존 HTTP POST 로 한다.
  투표 질문과 선택지도 사용자가 쓴 글이므로 DOM 에 넣을 때는 textContent 만 쓴다.
*/
document.addEventListener("DOMContentLoaded", () => {
    const root = document.querySelector("[data-travel-plan-chat]");
    const modal = document.querySelector("[data-travel-plan-poll-modal]");
    if (!root || !modal) return;

    const planId = root.getAttribute("data-chat-plan-id");
    if (!planId) return;

    /** 선택지 최소/최대. 서버도 같은 수로 다시 본다. */
    const MIN_OPTIONS = 2;
    const MAX_OPTIONS = 10;

    /* 들어오는 길은 채팅 머리글의 [투표] 하나뿐이다. */
    const entry = document.querySelector("[data-travel-plan-poll-entry]");
    const entryCount = document.querySelector("[data-travel-plan-poll-count]");

    const title = modal.querySelector("[data-travel-plan-poll-modal-title]");
    const listView = modal.querySelector("[data-travel-plan-poll-list-view]");
    const detailView = modal.querySelector("[data-travel-plan-poll-detail-view]");
    const detailBody = modal.querySelector("[data-travel-plan-poll-detail]");
    const createView = modal.querySelector("[data-travel-plan-poll-create-view]");
    const list = modal.querySelector("[data-travel-plan-poll-list]");
    const empty = modal.querySelector("[data-travel-plan-poll-empty]");
    const tabs = Array.from(modal.querySelectorAll("[data-travel-plan-poll-tab]"));

    const form = modal.querySelector("[data-travel-plan-poll-form]");
    const question = modal.querySelector("[data-travel-plan-poll-question]");
    const optionList = modal.querySelector("[data-travel-plan-poll-options]");
    const addButton = modal.querySelector("[data-travel-plan-poll-add]");
    const submitButton = modal.querySelector("[data-travel-plan-poll-submit]");
    const notice = modal.querySelector("[data-travel-plan-poll-error]");

    const EMPTY_TEXT = {
        OPEN: "진행 중인 투표가 아직 없어요.",
        CLOSED: "지난 투표가 아직 없어요."
    };

    let activeTab = "OPEN";
    let submitting = false;
    let voting = false;
    // 지금 상세로 열어 둔 투표. 닫혀 있으면 null.
    let openedPollId = null;
    // 탭마다 한 번 읽어 두고, 새 투표가 생기면 그때 고쳐 그린다.
    const loadedTabs = new Set();
    const pollsByTab = { OPEN: [], CLOSED: [] };
    /*
      탭에 붙는 숫자. 목록과 따로 둔다.
      목록은 그 탭을 열어 볼 때 읽지만, 숫자는 열지 않아도 맞아야 한다.
      언제나 서버가 준 값을 그대로 넣고 여기서 더하거나 빼지 않는다.
    */
    const pollCounts = { OPEN: 0, CLOSED: 0 };

    function realtime() {
        return window.travelPlanRealtime;
    }

    // ── 선택지 줄 ───────────────────────────────────────────

    /**
     * 선택지 한 줄.
     * 화면의 몇 번째 줄인지는 저장할 때의 순서로만 쓴다. DB id 로 삼지 않는다.
     */
    function optionRow(value) {
        const row = document.createElement("li");
        row.className = "travel-plan-poll-option-row";
        row.setAttribute("data-travel-plan-poll-option-row", "");

        const number = document.createElement("span");
        number.className = "travel-plan-poll-option-number";
        number.setAttribute("data-travel-plan-poll-option-number", "");

        const input = document.createElement("input");
        input.type = "text";
        input.className = "travel-plan-poll-option";
        input.autocomplete = "off";
        input.maxLength = 200;
        input.placeholder = "선택지 입력...";
        input.value = value || "";
        input.setAttribute("aria-label", "선택지");
        input.setAttribute("data-travel-plan-poll-option", "");

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "travel-plan-poll-option-remove";
        remove.setAttribute("aria-label", "선택지 삭제");
        remove.setAttribute("data-travel-plan-poll-option-remove", "");
        remove.textContent = "×";
        remove.addEventListener("click", () => {
            // 두 개까지는 남겨 둔다. 하나만 남으면 고를 것이 없다.
            if (rows().length <= MIN_OPTIONS) return;
            row.remove();
            renderOptionControls();
        });

        row.append(number, input, remove);
        return row;
    }

    function rows() {
        return Array.from(optionList.querySelectorAll("[data-travel-plan-poll-option-row]"));
    }

    function renderOptionControls() {
        const current = rows();
        current.forEach((row, index) => {
            // 01, 02 ... 화면에서도 순서가 보이게 한다.
            const number = row.querySelector("[data-travel-plan-poll-option-number]");
            if (number) number.textContent = String(index + 1).padStart(2, "0");
            // 최소 개수일 때는 지울 수 없다는 것이 보이게 둔다.
            const remove = row.querySelector("[data-travel-plan-poll-option-remove]");
            if (remove) remove.disabled = current.length <= MIN_OPTIONS;
        });
        // 상한에 닿으면 더 만들 수 없다.
        if (addButton) addButton.hidden = current.length >= MAX_OPTIONS;
    }

    addButton?.addEventListener("click", () => {
        if (rows().length >= MAX_OPTIONS) return;
        const row = optionRow("");
        optionList.append(row);
        renderOptionControls();
        row.querySelector("[data-travel-plan-poll-option]")?.focus();
    });

    // ── 두 화면 ─────────────────────────────────────────────

    function showError(message) {
        if (!notice) return;
        notice.textContent = message || "투표를 만들지 못했습니다. 잠시 후 다시 시도해 주세요.";
        notice.hidden = false;
    }

    function clearError() {
        if (!notice) return;
        notice.hidden = true;
        notice.textContent = "";
    }

    /** 열 때마다 빈 상태로 시작한다. 지난번에 쓰다 만 값이 남지 않는다. */
    function resetForm() {
        if (question) question.value = "";
        optionList.replaceChildren(optionRow(""), optionRow(""));
        renderOptionControls();
        modal.querySelectorAll("[data-travel-plan-poll-selection]").forEach(radio => {
            radio.checked = radio.value === "SINGLE";
        });
        modal.querySelectorAll("[data-travel-plan-poll-visibility]").forEach(radio => {
            radio.checked = radio.value === "REALTIME";
        });
        clearError();
    }

    function showListView() {
        openedPollId = null;
        listView.hidden = false;
        detailView.hidden = true;
        createView.hidden = true;
        if (title) title.textContent = "여행 투표";
    }

    function showDetailView() {
        listView.hidden = true;
        detailView.hidden = false;
        createView.hidden = true;
        if (title) title.textContent = "여행 투표";
    }

    function showCreateView() {
        resetForm();
        openedPollId = null;
        listView.hidden = true;
        detailView.hidden = true;
        createView.hidden = false;
        if (title) title.textContent = "새 투표 만들기";
        question?.focus();
    }

    /*
      창은 언제나 목록에서 시작한다.
      만들기 화면으로 바로 여는 길은 두지 않는다.
      새 투표는 이 창 안의 [투표 만들기] 에서 이어 간다.
    */
    function openModal() {
        modal.hidden = false;
        showListView();
        /*
          열 때 지금 상태를 한 번 맞춘다.
          보고 있는 탭의 목록과, 두 탭의 숫자를 함께 읽는다.
          숫자를 목록에서만 얻으면 열어 보지 않은 탭이 0 으로 남는다.
        */
        loadTab(activeTab, true);
        loadCounts();
    }

    function closeModal() {
        modal.hidden = true;
        showListView();
        resetForm();
    }

    entry?.addEventListener("click", () => openModal());
    modal.querySelector("[data-travel-plan-poll-close]")
        ?.addEventListener("click", () => closeModal());
    modal.querySelector("[data-travel-plan-poll-create-open]")
        ?.addEventListener("click", () => showCreateView());
    // 취소와 [← 투표 목록] 은 같은 곳으로 간다. 창 전체를 닫지 않는다.
    modal.querySelectorAll("[data-travel-plan-poll-back]").forEach(button => {
        button.addEventListener("click", () => showListView());
    });

    // 바깥의 어두운 곳을 누르면 닫는다. 창 안쪽 클릭은 그대로 둔다.
    modal.addEventListener("click", event => {
        if (event.target === modal) closeModal();
    });

    // ESC 순서는 채팅의 단일 핸들러에서 정한다. 생성/참여 상태 처리는 같은 닫기를 재사용한다.
    document.addEventListener("travelplan:poll-close", () => closeModal());

    // ── 목록 ────────────────────────────────────────────────

    const SELECTION_LABELS = {
        SINGLE: "하나만 선택",
        MULTIPLE: "여러 개 선택"
    };

    /*
      목록 한 줄.
      선택지는 여기서 펼치지 않는다. 무엇을 정하는지와 얼마나 참여했는지까지다.
      선택지와 표는 눌러서 상세로 들어갔을 때 읽는다.
    */
    function pollNode(poll) {
        const item = document.createElement("li");
        item.setAttribute("data-poll-id", poll.id);

        const card = document.createElement("button");
        card.type = "button";
        card.className = "travel-plan-poll-card";

        const text = document.createElement("span");
        text.className = "travel-plan-poll-card-body";

        const cardTitle = document.createElement("span");
        cardTitle.className = "travel-plan-poll-card-title";
        // 사용자가 쓴 글이다. 글자로만 넣어 태그가 실행되지 않게 한다.
        cardTitle.textContent = poll.title;

        const author = document.createElement("span");
        author.className = "travel-plan-poll-card-author";
        author.textContent = poll.createdByDisplayName;

        const meta = document.createElement("span");
        meta.className = "travel-plan-poll-card-meta";
        if (poll.status === "CLOSED") {
            // 끝난 투표는 참여 인원 대신 결과만 보여 준다.
            const label = document.createElement("span");
            label.className = "travel-plan-poll-card-result-label";
            label.textContent = "결과";
            const winner = document.createElement("span");
            winner.textContent = poll.winnerSummary || "투표 결과 없음";
            meta.append(label, winner);
        } else {
            meta.textContent =
                `${poll.votedMemberCount} / ${poll.activeMemberCount}명 투표`;
        }

        const chevron = document.createElement("span");
        chevron.className = "travel-plan-poll-card-chevron";
        chevron.setAttribute("aria-hidden", "true");
        chevron.textContent = "›";

        text.append(cardTitle, author, meta);
        card.append(text, chevron);
        card.addEventListener("click", () => openDetail(poll.id));

        item.append(card);
        return item;
    }

    function renderTabs() {
        tabs.forEach(tab => {
            const name = tab.getAttribute("data-travel-plan-poll-tab");
            const isActive = name === activeTab;
            tab.classList.toggle("is-active", isActive);
            tab.setAttribute("aria-selected", String(isActive));
        });
        modal.querySelectorAll("[data-travel-plan-poll-tab-count]").forEach(node => {
            const name = node.getAttribute("data-travel-plan-poll-tab-count");
            node.textContent = String(pollCounts[name]);
        });
        // 채팅 머리글의 작은 숫자는 진행 중인 투표 개수다.
        if (entryCount) {
            entryCount.textContent = String(pollCounts.OPEN);
            entryCount.hidden = pollCounts.OPEN === 0;
        }
    }

    function renderList() {
        const polls = pollsByTab[activeTab];
        list.replaceChildren(...polls.map(pollNode));
        if (empty) {
            empty.textContent = EMPTY_TEXT[activeTab];
            empty.hidden = polls.length > 0;
        }
        renderTabs();
    }

    /**
     * 두 탭의 숫자만 읽는다.
     * 목록을 열지 않아도 숫자는 맞아야 하고, 숫자 때문에 목록 전체를 가져오지도 않는다.
     */
    async function loadCounts() {
        try {
            const response = await fetch(`/travel-plans/${planId}/polls/counts`, {
                headers: { "X-Requested-With": "XMLHttpRequest" }
            });
            if (!response.ok) return;
            const payload = await response.json();
            pollCounts.OPEN = payload.open || 0;
            pollCounts.CLOSED = payload.closed || 0;
        } catch (error) {
            // 못 읽어도 채팅 자체는 그대로 쓸 수 있어야 한다.
        }
        renderTabs();
    }

    async function loadTab(name, force) {
        if (loadedTabs.has(name) && !force) {
            renderList();
            return;
        }
        try {
            const path = name === "OPEN" ? "open" : "closed";
            const response = await fetch(`/travel-plans/${planId}/polls/${path}`, {
                headers: { "X-Requested-With": "XMLHttpRequest" }
            });
            if (!response.ok) return;
            pollsByTab[name] = (await response.json()).polls || [];
            // 목록을 읽었으면 그 길이가 곧 그 탭의 숫자다. 둘이 어긋나지 않게 함께 맞춘다.
            pollCounts[name] = pollsByTab[name].length;
            loadedTabs.add(name);
        } catch (error) {
            // 못 읽어도 채팅 자체는 그대로 쓸 수 있어야 한다.
        }
        renderList();
    }

    tabs.forEach(tab => {
        tab.addEventListener("click", () => {
            activeTab = tab.getAttribute("data-travel-plan-poll-tab");
            renderList();
            loadTab(activeTab, false);
        });
    });

    /**
     * 새 투표가 생겼다.
     *
     * <p>알림에 실려 온 값을 그대로 목록에 끼우지 않는다.
     * 목록 한 줄에는 참여 인원이 필요한데 그것은 서버가 세는 값이라,
     * 저장 응답이든 방 알림이든 "다시 읽어라" 는 신호로만 쓴다.
     * 같은 투표가 두 번 들어가지 않는 것도 이렇게 하면 저절로 지켜진다.
     */
    function onPollCreated() {
        loadTab("OPEN", true);
        loadCounts();
    }

    // ── 상세 ────────────────────────────────────────────────

    function detailHeadOf(poll) {
        const head = document.createElement("div");
        head.className = "travel-plan-poll-detail-head";

        const detailTitle = document.createElement("p");
        detailTitle.className = "travel-plan-poll-detail-title";
        detailTitle.textContent = poll.title;

        const meta = document.createElement("p");
        meta.className = "travel-plan-poll-detail-meta";
        const label = SELECTION_LABELS[poll.selectionType] || "";
        meta.textContent = poll.status === "CLOSED" || !label
            ? poll.createdByDisplayName
            : `${poll.createdByDisplayName} · ${label}`;

        head.append(detailTitle, meta);

        if (poll.status !== "CLOSED") {
            const joined = document.createElement("p");
            joined.className = "travel-plan-poll-detail-joined";
            // 표를 가리는 투표에서도 참여 인원은 보여 준다.
            joined.textContent =
                `현재 ${poll.votedMemberCount} / ${poll.activeMemberCount}명 참여`;
            head.append(joined);

            if (!poll.resultsVisible) {
                const hint = document.createElement("p");
                hint.className = "travel-plan-poll-detail-hint";
                hint.textContent = "투표가 끝나면 결과가 공개돼요.";
                head.append(hint);
            }
        }
        return head;
    }

    /**
     * 진행 중인 투표의 선택지 한 줄.
     * 하나만 고르는 투표는 radio, 여러 개는 checkbox 다.
     */
    function optionChoiceOf(poll, option) {
        const row = document.createElement("label");
        row.className = "travel-plan-poll-choice-row";

        const input = document.createElement("input");
        input.type = poll.selectionType === "MULTIPLE" ? "checkbox" : "radio";
        input.name = "travelPlanPollChoice";
        input.value = option.id;
        input.checked = (poll.selectedOptionIds || [])
            .some(selected => String(selected) === String(option.id));
        input.setAttribute("data-travel-plan-poll-choice", "");

        const text = document.createElement("span");
        text.className = "travel-plan-poll-choice-text";
        text.textContent = option.content;

        row.append(input, text);

        // 마감 뒤에 공개하는 투표라면 진행 중에는 서버가 표를 아예 주지 않는다.
        if (option.voteCount != null) {
            const count = document.createElement("span");
            count.className = "travel-plan-poll-choice-count";
            count.textContent = `${option.voteCount}표`;
            row.append(count);
        }
        return row;
    }

    /** 끝난 투표는 읽기 전용이다. 고르는 칸을 두지 않는다. */
    function resultRowOf(option) {
        const row = document.createElement("li");
        row.className = "travel-plan-poll-result-row";

        const text = document.createElement("span");
        text.textContent = option.content;

        const count = document.createElement("span");
        count.className = "travel-plan-poll-choice-count";
        count.textContent = `${option.voteCount}표`;

        row.append(text, count);
        return row;
    }

    /**
     * 지우기.
     * 서버가 지울 수 있다고 한 사람에게만 보인다(만든 사람과 방장).
     * 되돌릴 수 없으므로 한 번 물어본다.
     */
    function deleteActionOf(poll) {
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "travel-plan-poll-delete";
        remove.setAttribute("data-travel-plan-poll-delete", "");
        remove.textContent = "투표 삭제";
        remove.addEventListener("click", () => deletePoll(poll.id));
        return remove;
    }

    function renderDetail(poll) {
        openedPollId = poll.id;
        const parts = [detailHeadOf(poll)];
        // 진행 중인 투표에만 있는 버튼 줄. 오류 문구 아래에 붙인다.
        let openActions = null;

        if (poll.status === "CLOSED") {
            const heading = document.createElement("p");
            heading.className = "travel-plan-poll-label";
            heading.textContent = "투표 결과";

            const results = document.createElement("ol");
            results.className = "travel-plan-poll-results";
            (poll.options || []).forEach(option => results.append(resultRowOf(option)));

            const finalHeading = document.createElement("p");
            finalHeading.className = "travel-plan-poll-label";
            finalHeading.textContent = "최종 선택";

            const winner = document.createElement("p");
            winner.className = "travel-plan-poll-winner";
            winner.textContent = poll.winnerSummary || "투표 결과 없음";

            parts.push(heading, results, finalHeading);
            parts.push(winner);
        } else {
            const choices = document.createElement("div");
            choices.className = "travel-plan-poll-choices";
            choices.setAttribute("data-travel-plan-poll-choices", "");
            (poll.options || []).forEach(option =>
                choices.append(optionChoiceOf(poll, option)));

            const actions = document.createElement("div");
            actions.className = "travel-plan-poll-actions";

            // 누구에게 보일지는 서버가 정한다(만든 사람과 방장).
            // 화면은 그 답을 그대로 쓰고, 눌렀을 때 서버가 다시 확인한다.
            if (poll.closable) {
                const closeButton = document.createElement("button");
                closeButton.type = "button";
                closeButton.className = "travel-plan-poll-cancel";
                closeButton.setAttribute("data-travel-plan-poll-close-action", "");
                closeButton.textContent = "투표 마감";
                closeButton.addEventListener("click", () => closePoll(poll.id));
                actions.append(closeButton);
            }

            const submitVote = document.createElement("button");
            submitVote.type = "button";
            submitVote.className = "travel-plan-poll-submit";
            submitVote.setAttribute("data-travel-plan-poll-vote", "");
            submitVote.textContent = "투표하기";
            submitVote.addEventListener("click", () => vote(poll.id));
            actions.append(submitVote);

            openActions = actions;
            parts.push(choices);
        }

        // 끝난 투표에서도 지울 수 있으므로 사유를 알릴 자리는 양쪽 모두에 둔다.
        const error = document.createElement("p");
        error.className = "travel-plan-poll-error";
        error.hidden = true;
        error.setAttribute("data-travel-plan-poll-vote-error", "");
        parts.push(error);
        if (openActions) parts.push(openActions);

        /*
          지우기는 목록 카드가 아니라 여기에만 둔다.
          목록에서 바로 누를 수 있으면 실수로 지우기 쉽다.
        */
        if (poll.deletable) {
            const footer = document.createElement("div");
            footer.className = "travel-plan-poll-detail-footer";
            footer.append(deleteActionOf(poll));
            parts.push(footer);
        }

        detailBody.replaceChildren(...parts);
    }

    async function fetchDetail(pollId) {
        const response = await fetch(`/travel-plans/${planId}/polls/${pollId}`, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        });
        if (!response.ok) throw new Error("poll unavailable");
        return response.json();
    }

    async function openDetail(pollId) {
        try {
            const poll = await fetchDetail(pollId);
            renderDetail(poll);
            showDetailView();
        } catch (error) {
            // 못 읽으면 목록에 그대로 머문다.
        }
    }

    /** 표가 바뀌었다는 알림을 받으면 지금 보고 있는 상세를 서버에서 다시 읽는다. */
    async function refreshDetail() {
        if (openedPollId == null) return;
        try {
            renderDetail(await fetchDetail(openedPollId));
        } catch (error) {
            // 못 읽어도 보고 있던 화면은 그대로 둔다.
        }
    }

    function showVoteError(message) {
        const error = detailBody.querySelector("[data-travel-plan-poll-vote-error]");
        if (!error) return;
        error.textContent = message || "투표하지 못했습니다. 잠시 후 다시 시도해 주세요.";
        error.hidden = false;
    }

    /**
     * 직접 마감.
     * 끝내고 나면 그 자리에서 결과 화면으로 바뀌고, 목록의 두 탭도 함께 맞춘다.
     */
    async function closePoll(pollId) {
        if (voting) return;
        if (!window.confirm("이 투표를 마감할까요?\n\n마감하면 더 이상 투표할 수 없습니다.")) {
            return;
        }
        voting = true;
        try {
            const response = await fetch(`/travel-plans/${planId}/polls/${pollId}/close`, {
                method: "POST",
                headers: csrfHeaders()
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => null);
                showVoteError(payload?.message);
                return;
            }
            renderDetail(await response.json());
            refreshLists();
        } catch (error) {
            showVoteError(null);
        } finally {
            voting = false;
        }
    }

    /*
      마감된 투표는 진행 중에서 빠지고 지난 투표로 옮겨 간다.
      두 숫자가 함께 바뀌어야 하므로 지금 보고 있지 않은 탭도 이때 다시 읽는다.
      보고 있는 탭만 읽으면 반대쪽 숫자가 그 탭을 열어 볼 때까지 옛 값으로 남는다.

      숫자는 여기서 더하거나 빼지 않고 서버가 준 목록의 길이로만 정한다.
      그래서 같은 알림을 두 번 받아도 숫자가 두 번 오르지 않는다.
    */
    function refreshLists() {
        loadTab("OPEN", true);
        loadTab("CLOSED", true);
        loadCounts();
    }

    /**
     * 투표 지우기.
     * 되돌릴 수 없으므로 한 번 물어보고, 지운 뒤에는 볼 상세가 없어 목록으로 돌아간다.
     */
    async function deletePoll(pollId) {
        if (voting) return;
        if (!window.confirm(
                "이 투표를 삭제할까요?\n\n삭제하면 지금까지의 투표 내용도 함께 사라집니다.")) {
            return;
        }
        voting = true;
        try {
            const response = await fetch(`/travel-plans/${planId}/polls/${pollId}/delete`, {
                method: "POST",
                headers: csrfHeaders()
            });
            if (!response.ok) {
                showVoteError("투표를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.");
                return;
            }
            removePoll(pollId);
        } catch (error) {
            showVoteError("투표를 삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        } finally {
            voting = false;
        }
    }

    /**
     * 지워진 투표를 화면에서 걷어 낸다.
     * 같은 번호로 두 번 와도 이미 없는 것을 지우려 할 뿐이라 그대로 두어도 안전하다.
     */
    function removePoll(pollId) {
        // 그 투표를 보고 있었다면 볼 것이 없다. 목록으로 돌아간다.
        if (String(openedPollId) === String(pollId)) showListView();
        refreshLists();
    }

    async function vote(pollId) {
        if (voting) return;
        const chosen = Array.from(
                detailBody.querySelectorAll("[data-travel-plan-poll-choice]:checked"))
            .map(input => Number(input.value));

        voting = true;
        const button = detailBody.querySelector("[data-travel-plan-poll-vote]");
        if (button) button.disabled = true;
        try {
            const response = await fetch(`/travel-plans/${planId}/polls/${pollId}/vote`, {
                method: "POST",
                headers: csrfHeaders(),
                body: JSON.stringify({ optionIds: chosen })
            });
            if (!response.ok) {
                const payload = await response.json().catch(() => null);
                showVoteError(payload?.message);
                return;
            }
            /*
              저장된 뒤의 값은 서버가 돌려준 그대로 쓴다.
              화면에서 표를 스스로 +1 하면 내 알림이 겹쳐 두 번 오를 수 있다.
            */
            renderDetail(await response.json());
            // 목록의 참여 인원도 함께 맞춘다.
            loadTab("OPEN", true);
        } catch (error) {
            showVoteError(null);
        } finally {
            voting = false;
            const current = detailBody.querySelector("[data-travel-plan-poll-vote]");
            if (current) current.disabled = false;
        }
    }

    // ── 저장 ────────────────────────────────────────────────

    /** 화면에 적힌 값 그대로. 빈 줄은 서버가 덜어 낸다. */
    function readForm() {
        return {
            question: question ? question.value : "",
            selectionType:
                modal.querySelector("[data-travel-plan-poll-selection]:checked")?.value || "SINGLE",
            options: rows().map(row =>
                row.querySelector("[data-travel-plan-poll-option]")?.value || ""),
            // 마감 방식은 보내지 않는다. 모든 투표가 같은 규칙으로 끝난다.
            resultVisibility:
                modal.querySelector("[data-travel-plan-poll-visibility]:checked")?.value
                    || "REALTIME"
        };
    }

    function csrfHeaders() {
        const token = document.querySelector("meta[name=\"_csrf\"]")?.content;
        const header = document.querySelector("meta[name=\"_csrf_header\"]")?.content;
        const headers = { "Content-Type": "application/json" };
        if (token && header) headers[header] = token;
        return headers;
    }

    async function submit() {
        if (submitting) return;
        submitting = true;
        if (submitButton) submitButton.disabled = true;
        clearError();

        try {
            const response = await fetch(`/travel-plans/${planId}/polls`, {
                method: "POST",
                headers: csrfHeaders(),
                body: JSON.stringify(readForm())
            });
            if (!response.ok) {
                // 만들기 화면과 입력한 값을 그대로 두고 사유만 알린다.
                const payload = await response.json().catch(() => null);
                showError(payload?.message);
                return;
            }
            // 방금 만든 것을 바로 볼 수 있게 목록으로 돌아간다. 창은 닫지 않는다.
            activeTab = "OPEN";
            showListView();
            onPollCreated();
        } catch (error) {
            showError(null);
        } finally {
            submitting = false;
            if (submitButton) submitButton.disabled = false;
        }
    }

    form?.addEventListener("submit", event => {
        event.preventDefault();
        submit();
    });

    /*
      한글 조합 중의 Enter 는 글자를 확정하는 것이지 제출이 아니다.
      판단 규칙은 일정 편집기·채팅과 같은 곳에 있다.
    */
    let composing = false;
    modal.addEventListener("compositionstart", () => {
        composing = true;
    });
    modal.addEventListener("compositionend", () => {
        composing = false;
    });
    form?.addEventListener("keydown", event => {
        if (event.key !== "Enter") return;
        if (window.travelPlanIme.isComposing(event, composing)) {
            // 조합 중에는 폼이 저절로 보내지지 않게 막기만 한다.
            event.preventDefault();
        }
    });

    // ── 연결 ────────────────────────────────────────────────

    /*
      만들어졌거나 표가 바뀌었다는 알림. 만든 사람·투표한 사람에게도 이 길로 온다.

      알림에는 숫자가 없다. 화면이 스스로 표를 올리면
      내 저장 응답과 내 알림이 겹쳐 두 번 오를 수 있어서,
      알림은 "다시 읽어라" 는 신호로만 쓴다.
    */
    realtime()?.subscribePolls(payload => {
        if (payload.type === "POLL_CREATED") {
            onPollCreated();
            return;
        }
        if (payload.type === "POLL_DELETED") {
            removePoll(payload.pollId);
            return;
        }
        if (payload.type === "POLL_CLOSED") {
            // 진행 중에서 빠지고 지난 투표로 옮겨 간다.
            refreshLists();
            // 그 투표를 보고 있었다면 결과 화면으로 바뀐다.
            if (String(openedPollId) === String(payload.pollId)) refreshDetail();
            return;
        }
        if (payload.type !== "POLL_VOTED") return;
        // 목록을 보고 있으면 참여 인원이, 상세를 보고 있으면 표가 바뀐다.
        if (loadedTabs.has("OPEN")) loadTab("OPEN", true);
        if (String(openedPollId) === String(payload.pollId)) refreshDetail();
    });

    /*
      방장이 바뀌었다. 마감·삭제를 누가 할 수 있는지가 함께 바뀐다.
      보고 있던 상세를 서버에서 다시 읽어 그 답을 새로 받는다.
      화면이 역할을 짐작해 버튼을 넣고 빼지 않는다.
    */
    document.addEventListener("travelplan:owner-actions-updated", () => refreshDetail());

    // 채팅의 "새 투표를 만들었어요" 를 누르면 여기가 열린다.
    document.addEventListener("travelplan:poll-center-open", event => {
        const pollId = event.detail?.pollId;
        openModal();
        // 어떤 투표인지 알면 그 상세로 바로 들어간다.
        if (pollId != null) openDetail(pollId);
    });

    // 끊겨 있던 사이에 만들어지거나 끝난 투표는 다시 받을 수 없다. 그때 서버에서 다시 읽는다.
    realtime()?.onReconnected(() => {
        loadedTabs.clear();
        loadTab(activeTab, true);
        loadCounts();
    });

    /*
      머리글의 숫자는 채팅창을 열 때 한 번 맞춘다.
      숫자만 있으면 되므로 목록까지 가져오지 않는다.
    */
    document.addEventListener("travelplan:chat-opened", () => loadCounts());
});
