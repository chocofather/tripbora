// 기본 열기/닫기와 키보드 조작은 details/summary가 담당한다.
// course와 post의 동일한 작성자 메뉴, 비로그인 댓글 이동에 함께 쓴다.
(() => {
    const menus = Array.from(document.querySelectorAll('[data-owner-menu]'));

    document.addEventListener('click', event => {
        menus.forEach(menu => {
            if (!menu.contains(event.target)) menu.open = false;
        });
    });

    document.addEventListener('keydown', event => {
        if (event.key !== 'Escape') return;
        const openMenu = menus.find(menu => menu.open);
        if (!openMenu) return;
        openMenu.open = false;
        openMenu.querySelector('summary').focus();
    });

    menus.forEach(menu => menu.addEventListener('focusout', event => {
        if (!menu.contains(event.relatedTarget)) menu.open = false;
    }));

    document.querySelectorAll('[data-guest-comment-redirect]').forEach(textarea => {
        textarea.addEventListener('click', () => {
            const redirect = encodeURIComponent(window.location.pathname);
            window.location.href = `/login?redirect=${redirect}`;
        });
    });

    const days = Array.from(document.querySelectorAll('[data-course-day]'));
    document.querySelectorAll('[data-course-day-nav]').forEach(nav => {
        if (days.length < 2) return;
        const buttons = Array.from(nav.querySelectorAll('[data-course-day-filter]'));
        const selectDay = selected => {
            days.forEach(day => {
                day.hidden = selected !== 'all' && day.dataset.courseDay !== selected;
            });
            buttons.forEach(button => {
                button.setAttribute('aria-pressed', String(button.dataset.courseDayFilter === selected));
            });
        };
        buttons.forEach(button => button.addEventListener('click', () => selectDay(button.dataset.courseDayFilter)));
        selectDay('all');
        nav.hidden = false;
    });

    document.querySelectorAll('[data-course-hero-image]').forEach(image => {
        const hideFailedHero = () => {
            const hero = image.closest('[data-course-hero]');
            if (hero) hero.hidden = true;
        };
        image.addEventListener('error', hideFailedHero);
        if (image.complete && image.naturalWidth === 0) hideFailedHero();
    });
})();
