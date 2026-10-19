/* 상태 전환과 완료 목록의 페이지 이동만 담당한다. 생성폼과 관리 액션은 그대로 둔다. */
(function () {
    function mount(doc) {
        const root = doc.querySelector('[data-plan-dashboard]');
        if (!root) return;
        const tabs = [...root.querySelectorAll('[data-plan-filter]')];
        const panel = root.querySelector('[role="tabpanel"]');
        const status = root.querySelector('[data-plan-list-status]');
        let loading = false;
        function select(filter) {
            root.dataset.selectedFilter = filter;
            tabs.forEach(tab => {
                const selected = tab.dataset.planFilter === filter;
                tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1;
                if (selected) panel.setAttribute('aria-labelledby', tab.id);
            });
            const cards = [...root.querySelectorAll('[data-plan-status]')];
            cards.forEach(card => { card.hidden = filter !== 'all' && card.dataset.planStatus !== filter; });
            let empty = root.querySelector('[data-plan-empty]');
            if (!empty) {
                empty = doc.createElement('p'); empty.className = 'travel-plan-empty'; empty.dataset.planEmpty = '';
                root.querySelector('[data-plan-grid]').after(empty);
            }
            empty.hidden = cards.some(card => !card.hidden);
            empty.textContent = filter === 'active' ? '진행 중인 여행이 없어요.'
                : filter === 'completed' ? '아직 완료된 여행이 없어요.' : '아직 함께한 여행이 없어요.';
            root.querySelectorAll('[data-completed-pagination], [data-completed-note]').forEach(node => { node.hidden = filter === 'active'; });
            root.querySelectorAll('[data-plan-popover][open]').forEach(menu => { menu.open = false; });
        }
        root.addEventListener('click', async event => {
            const tab = event.target.closest('[data-plan-filter]');
            if (tab) { select(tab.dataset.planFilter); return; }
            const link = event.target.closest('[data-completed-pagination] a');
            if (!link || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
            event.preventDefault();
            if (loading) return;
            loading = true; status.textContent = '여행 목록을 불러오고 있어요.'; panel.setAttribute('aria-busy', 'true');
            try {
                const response = await fetch(link.href, {credentials: 'same-origin', headers: {Accept: 'text/html', 'X-Requested-With': 'XMLHttpRequest'}});
                if (!response.ok) throw new Error();
                const next = new DOMParser().parseFromString(await response.text(), 'text/html').querySelector('[data-plan-dashboard]');
                if (!next) throw new Error();
                const confirmation = doc.querySelector('[data-plan-manage-dialog]');
                if (confirmation?.open) {
                    await new Promise(resolve => confirmation.addEventListener('close', resolve, {once: true}));
                }
                root.querySelector('[data-plan-grid]').replaceWith(doc.importNode(next.querySelector('[data-plan-grid]'), true));
                tabs.forEach(tab => {
                    const nextTab = next.querySelector(`[data-plan-filter="${tab.dataset.planFilter}"]`);
                    tab.replaceChildren(...[...nextTab.childNodes].map(node => doc.importNode(node, true)));
                });
                for (const selector of ['[data-completed-note]', '[data-completed-pagination]']) {
                    const current = root.querySelector(selector), replacement = next.querySelector(selector);
                    if (replacement) {
                        const node = doc.importNode(replacement, true);
                        if (current) current.replaceWith(node); else panel.append(node);
                    } else current?.remove();
                }
                select(root.dataset.selectedFilter); status.textContent = '';
                panel.focus({preventScroll: true});
            } catch (_) {
                status.textContent = '목록을 불러오지 못했어요. 로그인 상태를 확인하거나 다시 시도해 주세요.';
            } finally {
                loading = false; panel.removeAttribute('aria-busy');
            }
        });
        root.addEventListener('keydown', event => {
            const index = tabs.indexOf(event.target);
            if (index < 0) return;
            let next;
            if (event.key === 'ArrowRight') next = (index + 1) % tabs.length;
            if (event.key === 'ArrowLeft') next = (index + tabs.length - 1) % tabs.length;
            if (event.key === 'Home') next = 0;
            if (event.key === 'End') next = tabs.length - 1;
            if (next === undefined) return;
            event.preventDefault(); select(tabs[next].dataset.planFilter); tabs[next].focus();
        });
        // 스크립트가 없는 페이지 이동의 완료 목록 링크도 이어서 볼 수 있게 한다.
        select(doc.defaultView.location.hash === '#tp-trips-panel' ? 'completed' : 'active');
    }
    if (typeof module !== 'undefined' && module.exports) module.exports = {mount};
    if (typeof document !== 'undefined') {
        if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => mount(document));
        else mount(document);
    }
}());
