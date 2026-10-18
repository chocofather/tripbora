(function (root) {
    'use strict';

    // Unicode 16 / CLDR 46 data and Unicode-3.0 permission notice are bundled in
    // /data/travel-plan-emoji.json. Only system-rendered Unicode text is inserted.
    const CATEGORIES = [
        {id: 'recent', label: '최근 사용', icon: '◷'},
        {id: 'emotion', label: '표정과 감정', icon: '☺'},
        {id: 'people', label: '사람과 신체', icon: '☝'},
        {id: 'nature', label: '동물과 자연', icon: '❀'},
        {id: 'food', label: '음식과 음료', icon: '♨'},
        {id: 'travel', label: '여행과 장소', icon: '✈'},
        {id: 'activity', label: '활동', icon: '⚽'},
        {id: 'objects', label: '사물', icon: '♧'},
        {id: 'symbols', label: '기호', icon: '♡'},
        {id: 'flags', label: '깃발', icon: '⚑'}
    ];
    const RECENT_KEY = 'travelPlan.chat.recentEmoji';
    const RECENT_LIMIT = 24;
    const normalize = text => String(text || '').normalize('NFKC').toLowerCase().trim();

    function search(items, query) {
        const words = normalize(query).split(/\s+/).filter(Boolean);
        return items.filter(item => {
            const haystack = normalize(`${item.emoji} ${item.ko} ${item.en} ${item.tags}`);
            return words.every(word => haystack.includes(word));
        });
    }

    function cleanRecent(value, allowed) {
        try {
            const saved = JSON.parse(value || '[]');
            return Array.isArray(saved)
                ? [...new Set(saved.filter(emoji => typeof emoji === 'string' && allowed.has(emoji)))].slice(0, RECENT_LIMIT)
                : [];
        } catch (_) {
            return [];
        }
    }

    function insertAt(value, start, end, emoji, maxLength) {
        const next = value.slice(0, start) + emoji + value.slice(end);
        // Match textarea maxlength and the server's Java String length. Never
        // truncate an emoji sequence or silently remove an existing draft.
        return next.length > maxLength ? null : {value: next, caret: start + emoji.length};
    }

    let catalogPromise;
    function loadCatalog(view, url) {
        if (!catalogPromise) {
            catalogPromise = view.fetch(url, {credentials: 'same-origin'})
                .then(response => {
                    if (!response.ok) throw new Error('emoji data unavailable');
                    return response.json();
                })
                .then(data => data.emoji)
                .catch(error => { catalogPromise = null; throw error; });
        }
        return catalogPromise;
    }

    function mount({input, toggle, panel, onInsert, data, dataUrl = '/data/travel-plan-emoji.json'}) {
        if (!input || !toggle || !panel) return {close() {}};
        if (panel.travelPlanEmojiPicker) return panel.travelPlanEmojiPicker;
        const doc = panel.ownerDocument;
        const view = doc.defaultView;
        let items = data || [];
        let active = 'emotion';
        let composing = false;
        let savedStart = input.selectionStart || 0;
        let savedEnd = input.selectionEnd || 0;
        let pending;

        function element(tag, className, text) {
            const node = doc.createElement(tag);
            if (className) node.className = className;
            if (text) node.textContent = text;
            return node;
        }
        panel.classList.add('tp-emoji-picker');
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-label', '이모지 선택');
        if (!panel.id) panel.id = 'travel-plan-chat-emoji-picker';
        toggle.setAttribute('aria-controls', panel.id);
        toggle.setAttribute('aria-haspopup', 'dialog');
        toggle.setAttribute('aria-expanded', 'false');

        const heading = element('div', 'tp-emoji-heading');
        heading.append(element('strong', '', '이모지'));
        const dismiss = element('button', 'tp-emoji-dismiss', '×');
        dismiss.type = 'button';
        dismiss.setAttribute('aria-label', '이모지 선택 닫기');
        heading.append(dismiss);
        const searchInput = element('input', 'tp-emoji-search');
        searchInput.type = 'search';
        searchInput.placeholder = '이모지 검색 · 한글 / English';
        searchInput.setAttribute('aria-label', '이모지 검색');
        searchInput.setAttribute('autocomplete', 'off');
        searchInput.setAttribute('spellcheck', 'false');
        const tabs = element('div', 'tp-emoji-categories');
        tabs.setAttribute('role', 'group');
        tabs.setAttribute('aria-label', '이모지 카테고리');
        const tabButtons = new Map();
        CATEGORIES.forEach(category => {
            const button = element('button', 'tp-emoji-category', category.icon);
            const shortLabels = {recent: '최근', emotion: '표정', people: '사람', nature: '자연', food: '음식', travel: '여행', activity: '활동', objects: '사물', symbols: '기호', flags: '깃발'};
            button.append(element('span', '', shortLabels[category.id]));
            button.type = 'button';
            button.title = category.label;
            button.setAttribute('aria-label', category.label);
            button.dataset.emojiCategory = category.id;
            button.addEventListener('click', () => {
                active = category.id;
                searchInput.value = '';
                render();
            });
            tabButtons.set(category.id, button);
            tabs.append(button);
        });
        const caption = element('p', 'tp-emoji-caption');
        const scroller = element('div', 'tp-emoji-scroll');
        const grid = element('div', 'travel-plan-chat-emoji-grid');
        grid.setAttribute('data-travel-plan-chat-emoji-all', '');
        const empty = element('p', 'tp-emoji-empty');
        const status = element('p', 'tp-emoji-status');
        status.setAttribute('role', 'status');
        status.setAttribute('aria-live', 'polite');
        scroller.append(grid, empty);
        panel.replaceChildren(heading, searchInput, tabs, caption, scroller, status);

        function readRecent() {
            try { return cleanRecent(view.localStorage.getItem(RECENT_KEY), new Set(items.map(item => item.emoji))); }
            catch (_) { return []; }
        }
        function remember(emoji) {
            const next = [emoji, ...readRecent().filter(saved => saved !== emoji)].slice(0, RECENT_LIMIT);
            try { view.localStorage.setItem(RECENT_KEY, JSON.stringify(next)); }
            catch (_) { /* Storage restrictions must not block selection. */ }
        }
        function saveSelection() {
            savedStart = input.selectionStart ?? input.value.length;
            savedEnd = input.selectionEnd ?? savedStart;
        }
        function choose(item) {
            if (composing) {
                status.textContent = '한글 입력을 마친 뒤 이모지를 선택해 주세요.';
                input.focus();
                return;
            }
            const maxLength = input.maxLength > 0 ? Math.min(input.maxLength, 2000) : 2000;
            const next = insertAt(input.value, savedStart, savedEnd, item.emoji, maxLength);
            if (!next) {
                status.textContent = '메시지는 2,000자까지 입력할 수 있어요.';
                return;
            }
            input.value = next.value;
            input.focus({preventScroll: true});
            input.setSelectionRange(next.caret, next.caret);
            saveSelection();
            input.dispatchEvent(new view.Event('input', {bubbles: true}));
            onInsert?.();
            remember(item.emoji);
            status.textContent = '';
            // Keep the palette open for consecutive selections.
            if (active === 'recent') render();
        }
        function render() {
            const query = searchInput.value.trim();
            let visible;
            if (query) visible = search(items, query);
            else if (active === 'recent') {
                const catalog = new Map(items.map(item => [item.emoji, item]));
                visible = readRecent().map(emoji => catalog.get(emoji));
            } else visible = items.filter(item => item.category === active);
            tabButtons.forEach((button, id) => button.setAttribute('aria-pressed', String(!query && id === active)));
            caption.textContent = query ? `검색 결과 · ${visible.length}개` : CATEGORIES.find(category => category.id === active).label;
            const buttons = visible.map(item => {
                const button = element('button', 'travel-plan-chat-emoji-item', item.emoji);
                button.type = 'button';
                button.title = `${item.ko} · ${item.en}`;
                button.setAttribute('aria-label', button.title);
                // Preserve the textarea selection and avoid committing/interrupting IME.
                button.addEventListener('pointerdown', event => event.preventDefault());
                button.addEventListener('click', () => choose(item));
                return button;
            });
            grid.replaceChildren(...buttons);
            empty.hidden = buttons.length > 0;
            empty.textContent = query ? '검색 결과가 없어요.' : active === 'recent' ? '선택한 이모지가 여기에 표시돼요.' : '이모지를 불러오는 중이에요.';
            scroller.scrollTop = 0;
        }
        function position() {
            if (panel.hidden) return;
            const rect = toggle.getBoundingClientRect();
            const viewport = view.visualViewport;
            const leftEdge = viewport?.offsetLeft || 0;
            const topEdge = viewport?.offsetTop || 0;
            const width = viewport?.width || view.innerWidth;
            const height = viewport?.height || view.innerHeight;
            const popupWidth = Math.min(352, width - 24);
            const available = Math.max(0, rect.top - topEdge - 16);
            const popupHeight = Math.min(390, available);
            panel.style.width = `${popupWidth}px`;
            panel.style.maxHeight = `${popupHeight}px`;
            panel.style.left = `${Math.max(leftEdge + 12, Math.min(rect.left, leftEdge + width - popupWidth - 12))}px`;
            panel.style.top = `${Math.max(topEdge + 8, rect.top - popupHeight - 8)}px`;
            panel.style.bottom = 'auto';
        }
        function close() {
            panel.hidden = true;
            toggle.setAttribute('aria-expanded', 'false');
        }
        async function open() {
            saveSelection();
            panel.hidden = false;
            toggle.setAttribute('aria-expanded', 'true');
            status.textContent = '';
            position();
            render();
            // Keeping input focus on pointer open prevents mobile keyboards from
            // switching just to browse. Keyboard users can Tab into the search.
            if (!items.length) {
                try {
                    pending ||= loadCatalog(view, dataUrl);
                    items = await pending;
                    if (!panel.hidden) render();
                } catch (_) {
                    pending = null;
                    empty.textContent = '이모지를 불러오지 못했어요. 닫고 다시 열어 주세요.';
                }
            }
        }
        input.addEventListener('compositionstart', () => { composing = true; });
        input.addEventListener('compositionend', () => { composing = false; saveSelection(); });
        ['select', 'keyup', 'click', 'input', 'blur'].forEach(type => input.addEventListener(type, saveSelection));
        toggle.addEventListener('pointerdown', event => {
            saveSelection();
            if (!composing) event.preventDefault();
        });
        toggle.addEventListener('click', event => {
            event.stopPropagation();
            if (panel.hidden) open();
            else { close(); input.focus({preventScroll: true}); }
        });
        panel.addEventListener('click', event => event.stopPropagation());
        searchInput.addEventListener('input', render);
        searchInput.addEventListener('keydown', event => {
            if (event.key === 'Enter') event.preventDefault();
        });
        dismiss.addEventListener('click', () => { close(); input.focus({preventScroll: true}); });
        view.addEventListener('resize', position);
        view.visualViewport?.addEventListener('resize', position);
        view.visualViewport?.addEventListener('scroll', position);
        const api = {close, open};
        panel.travelPlanEmojiPicker = api;
        return api;
    }

    const api = {CATEGORIES, search, cleanRecent, insertAt, mount};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.TravelPlanEmojiPicker = api;
})(typeof window === 'undefined' ? globalThis : window);
