(() => {
    const nav = document.querySelector('.main-nav');
    const groups = Array.from(document.querySelectorAll('.main-menu > .menu-item > .submenu-col'));
    if (!nav || !groups.length) return;

    // X 정렬은 부모 기준 CSS만 담당한다. 공통 배경은 실제 그룹의 Y 범위에 맞춘다.
    const sizeBackdrop = () => {
        const rects = groups.map(group => group.getBoundingClientRect());
        const top = Math.min(...rects.map(rect => rect.top));
        const bottom = Math.max(...rects.map(rect => rect.top + rect.height));
        const height = Math.ceil(bottom - top);
        nav.style.setProperty('--header-submenu-top', `${top - nav.getBoundingClientRect().top}px`);
        nav.style.setProperty('--header-submenu-height', `${height}px`);
    };
    sizeBackdrop();
    window.addEventListener('resize', sizeBackdrop);
    document.fonts?.ready.then(sizeBackdrop);
    if (typeof ResizeObserver !== 'undefined') {
        const observer = new ResizeObserver(sizeBackdrop);
        groups.forEach(group => observer.observe(group));
    }
})();
