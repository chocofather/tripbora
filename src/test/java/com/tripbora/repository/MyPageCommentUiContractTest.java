package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MyPageCommentUiContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void commentsPageUsesTheExistingLayoutAndSafeServerRenderedLinks() throws IOException {
        String template = resource("templates/mypage/comments.html");

        assertThat(template)
                .contains("layout/main :: layout")
                .contains("fragments/mypage/navigation :: navigation('comments')")
                .contains("/css/mypage-layout.css")
                .contains("/css/mypage-comments.css")
                .contains("th:text=\"${comment.contentPreview}\"")
                .contains("th:text=\"${comment.targetTitle}\"")
                .contains("@{/mypage/comments(type=${type},page=${currentPage + 1})}")
                .doesNotContain("th:utext")
                .doesNotContain("<script");
        assertThat(occurrences(template, "@{/destinations/{id}")).isEqualTo(2);
        assertThat(occurrences(template, "@{/post/{id}")).isEqualTo(2);
        assertThat(occurrences(template, "@{/courses/{id}")).isEqualTo(2);
        assertThat(occurrences(template, "commentId=${comment.commentId}")).isEqualTo(6);
        assertThat(template)
                .doesNotContain("target=\"_blank\"")
                .doesNotContain("#comment-");
    }

    @Test
    void commentsStylesClampPreviewAndStackOnTheExistingMobileBreakpoint() throws IOException {
        String css = resource("static/css/mypage-comments.css");

        assertThat(css)
                .contains(".mypage-comment-preview")
                .contains(".mypage-comment-target a:focus-visible")
                .contains(".mypage-comment-target a:hover")
                .contains("-webkit-line-clamp: 3")
                .contains("overflow-wrap: anywhere")
                .contains("@media (max-width: 680px)")
                .contains("flex-direction: column")
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .doesNotContain("overflow-x: auto");
    }

    @Test
    void navigationAndMainActivateOnlyTheNewCommentsEntry() throws IOException {
        String navigation = resource("templates/fragments/mypage/navigation.html");
        String index = resource("templates/mypage/index.html");

        // 활성 판정은 activeMenu 코드로 하고, 메뉴 문구는 메시지 번들에서 온다
        assertThat(navigation)
                .contains("activeMenu == 'comments'")
                .contains("th:href=\"@{/mypage/comments}\"")
                .contains("#{mypage.nav.comments}")
                .contains("activeMenu == 'bookmarks'")
                .contains("th:href=\"@{/mypage/bookmarks}\"")
                .contains("#{mypage.nav.bookmarks}")
                .contains("activeMenu == 'account'")
                .contains("th:href=\"@{/mypage/account}\"")
                .contains("#{mypage.nav.account.social} : #{mypage.nav.account}");
        assertThat(index)
                .contains("<a class=\"mypage-menu-item\" th:href=\"@{/mypage/comments}\">")
                .contains("#{mypage.nav.bookmarks}")
                .contains("#{mypage.nav.account.social} : #{mypage.nav.account}");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(RESOURCES.resolve(relativePath), StandardCharsets.UTF_8);
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
