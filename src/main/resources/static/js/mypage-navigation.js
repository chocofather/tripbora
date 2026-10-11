/*
 * 좁은 화면에서 마이페이지 메뉴는 한 줄 탭으로 가로로 넘긴다.
 * 지금 보고 있는 메뉴가 줄 밖에 있으면 처음 한 번만 보이는 자리로 넘겨 둔다.
 * 메뉴가 줄에 다 들어가는 넓은 화면에서는 아무것도 하지 않는다.
 */
(() => {
    const navigation = document.querySelector('.mypage-navigation');
    const active = navigation && navigation.querySelector('.mypage-navigation-link.is-active');
    if (!active || navigation.scrollWidth <= navigation.clientWidth) {
        return;
    }
    const navigationBox = navigation.getBoundingClientRect();
    const activeBox = active.getBoundingClientRect();
    navigation.scrollLeft += activeBox.left - navigationBox.left
            - (navigationBox.width - activeBox.width) / 2;
})();
