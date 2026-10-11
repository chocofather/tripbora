/*
 * 관리자 화면의 좁은 화면 보조 동작. 넓은 화면에서는 아무 모양도 바꾸지 않는다.
 *
 * 1. 메뉴 서랍
 *    960px 이하에서는 왼쪽 메뉴가 화면 밖에 있다가 [메뉴] 를 누르면 서랍으로 열린다.
 *    뒷배경·× ·Esc 로 닫히고, 열려 있는 동안 본문은 넘어가지 않는다.
 *    메뉴 링크와 권한은 그대로다(같은 sidebar 조각을 쓴다).
 *
 * 2. 표 → 목록 이름표
 *    좁은 화면에서는 표의 각 칸을 "이름 값" 줄로 보여 준다(admin-mobile.css).
 *    이름은 그 표의 머리글(th) 글자를 그대로 data-label 로 옮긴 것이다.
 *    스크립트가 나중에 그리는 표(가져오기 미리보기 등)도 줄이 생길 때마다 붙인다.
 */
(() => {
    const MOBILE_QUERY = window.matchMedia('(max-width: 960px)');
    const OPEN_CLASS = 'admin-menu-open';

    const toggle = document.querySelector('[data-admin-menu-toggle]');
    const sidebar = document.getElementById('admin-sidebar');
    const backdrop = document.querySelector('[data-admin-menu-backdrop]');
    const close = document.querySelector('[data-admin-menu-close]');

    function isOpen() {
        return document.documentElement.classList.contains(OPEN_CLASS);
    }

    function setOpen(open, { restoreFocus = true } = {}) {
        if (!toggle || !sidebar) {
            return;
        }
        const wasOpen = isOpen();
        document.documentElement.classList.toggle(OPEN_CLASS, open);
        toggle.setAttribute('aria-expanded', String(open));
        if (backdrop) {
            backdrop.hidden = !open;
        }
        if (open && close) {
            close.focus();
        } else if (!open && wasOpen && restoreFocus) {
            toggle.focus();
        }
    }

    if (toggle && sidebar) {
        toggle.addEventListener('click', () => setOpen(!isOpen()));
        if (backdrop) {
            backdrop.addEventListener('click', () => setOpen(false));
        }
        if (close) {
            close.addEventListener('click', () => setOpen(false));
        }
        document.addEventListener('keydown', (event) => {
            if (event.key === 'Escape' && isOpen()) {
                setOpen(false);
            }
        });
        // 넓은 화면으로 바뀌면 서랍 상태를 걷어 낸다(사이드바는 원래 자리로 돌아간다)
        MOBILE_QUERY.addEventListener('change', (event) => {
            if (!event.matches) {
                setOpen(false, { restoreFocus: false });
            }
        });
    }

    function labelRows(table) {
        const headings = [...table.querySelectorAll(':scope > thead th')]
                .map((th) => th.textContent.replace(/\s+/g, ' ').trim());
        if (headings.length === 0) {
            return;
        }
        table.querySelectorAll(':scope > tbody > tr').forEach((row) => {
            [...row.children].forEach((cell, index) => {
                if (cell.tagName !== 'TD' || cell.hasAttribute('data-label')
                        || cell.hasAttribute('colspan')) {
                    return;
                }
                const label = headings[index];
                if (label) {
                    cell.setAttribute('data-label', label);
                }
            });
        });
    }

    document.querySelectorAll('table.admin-table').forEach((table) => {
        labelRows(table);
        // 줄을 다시 그리거나 tbody 를 통째로 바꾸는 표도 있어 표 전체를 본다
        if ('MutationObserver' in window) {
            new MutationObserver(() => labelRows(table))
                    .observe(table, { childList: true, subtree: true });
        }
    });
})();
