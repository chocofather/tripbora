package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자·마이페이지 좁은 화면 정리의 연결 고리.
 * 넓은 화면 배치는 그대로 두고, 좁은 화면 규칙과 스크립트가 제자리에서 이어져 있는지만 본다.
 */
class AdminMobileLayoutContractTest {

    @Test
    void adminMenuDrawerIsWiredToTheExistingSidebar() throws IOException {
        String layout = file("src/main/resources/templates/layout/admin.html");
        String sidebar = file("src/main/resources/templates/fragments/admin/sidebar.html");
        String script = file("src/main/resources/static/js/admin-mobile.js");

        // [메뉴] 버튼이 같은 sidebar 조각을 서랍으로 연다(메뉴 링크·권한은 sidebar 그대로)
        assertThat(layout)
                .contains("data-admin-menu-toggle")
                .contains("aria-controls=\"admin-sidebar\"")
                .contains("aria-expanded=\"false\"")
                .contains("data-admin-menu-backdrop");
        assertThat(sidebar)
                .contains("id=\"admin-sidebar\"")
                .contains("data-admin-menu-close");
        assertThat(script)
                .contains("(max-width: 960px)")
                .contains("aria-expanded")
                .contains("'Escape'")
                .contains("data-label");

        // 좁은 화면 규칙은 화면별 CSS 를 덮어써야 하므로 headFragment 뒤에서 읽힌다
        assertThat(layout.indexOf("/css/admin-mobile.css"))
                .isGreaterThan(layout.indexOf("th:replace=\"${headFragment}\""));
    }

    @Test
    void narrowScreenRulesStayInsideMediaQueries() throws IOException {
        String css = file("src/main/resources/static/css/admin-mobile.css");

        // 미디어 쿼리 밖에는 좁은 화면 전용 버튼을 넓은 화면에서 숨기는 규칙 하나뿐이다
        String beforeFirstMedia = css.substring(0, css.indexOf("@media ("));
        assertThat(beforeFirstMedia.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("\\s+", " ").trim())
                .isEqualTo(".admin-menu-toggle, .admin-sidebar-close { display: none; }");
        assertThat(css)
                .contains("@media (max-width: 960px)")
                .contains("@media (max-width: 680px)");
    }

    @Test
    void mypageNavigationKeepsEveryMenuAndScrollsToTheCurrentOne() throws IOException {
        String navigation = file("src/main/resources/templates/fragments/mypage/navigation.html");

        assertThat(navigation)
                .contains("th:href=\"@{/mypage/profile}\"")
                .contains("th:href=\"@{/mypage/posts}\"")
                .contains("th:href=\"@{/mypage/comments}\"")
                .contains("th:href=\"@{/mypage/bookmarks}\"")
                .contains("th:href=\"@{/mypage/account}\"")
                .contains("/js/mypage-navigation.js");
    }

    private String file(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
