package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MyPageBookmarkUiContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void pageUsesExistingLayoutSectionsSafeTextAndServerRenderedUrls() throws IOException {
        String template = resource("templates/mypage/bookmarks.html");

        assertThat(template)
                .contains("layout/main :: layout")
                .contains("navigation('bookmarks')")
                .contains("section='destination'", "section='community'", "section='travel-info'")
                .contains("scope='domestic'", "scope='international'")
                .contains("type='question'", "type='tip'", "type='course'")
                .contains("/destinations/{id}", "/post/{id}", "/courses/{id}", "/travel-info/{id}")
                .contains("data-bookmark-delete-url", "data-bookmark-remove")
                .contains("role=\"status\"", "aria-live=\"polite\"")
                .doesNotContain("th:utext")
                .doesNotContain("target=\"_blank\"");
    }

    @Test
    void removalUsesCsrfAndHandlesTheLastItemWithoutAlert() throws IOException {
        String javascript = resource("static/js/mypage-bookmarks.js");

        assertThat(javascript)
                .contains("method: 'DELETE'")
                .contains("[csrfHeader]: csrfToken")
                .contains("button.closest('[data-bookmark-item]')?.remove()")
                .contains("currentPage > 1")
                .contains("currentPage - 1")
                .contains("new URL('/mypage/bookmarks', window.location.origin)")
                .contains("section === 'community'")
                .contains("window.location.pathname + window.location.search")
                .doesNotContain("alert(");
    }

    @Test
    void stylesUseCompactResponsiveCardGridWithoutChangingCommunityRows() throws IOException {
        String css = resource("static/css/mypage-bookmarks.css");

        assertThat(css)
                .contains(".mypage-bookmark-sections")
                .contains(".mypage-bookmark-filters")
                .contains("grid-template-columns: repeat(3, minmax(0, 1fr))")
                .contains("aspect-ratio: 16 / 9")
                .contains("@media (max-width: 900px)")
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .contains("@media (max-width: 680px)")
                .contains("grid-template-columns: minmax(0, 1fr)")
                .contains(".mypage-bookmark-community-list")
                .contains(".mypage-bookmark-community-row")
                .contains(":focus-visible");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(RESOURCES.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
