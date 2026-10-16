package com.tripbora.controller.board;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BoardUiContractTest {
    @Test
    void communityKeepsSortHistoryAndAuthenticatedWriteWithoutCourseControls() throws IOException {
        assertThat(resource("/static/js/board-ajax.js"))
                .contains("['latest', 'oldest', 'views', 'comments', 'bookmarks']",
                        "updateBoardSortState(activeSort)", "new URLSearchParams(window.location.search)",
                        "window.history.pushState", "window.addEventListener('popstate'")
                .doesNotContain("changeBoardScope", "changeBoardCountry", "CountryCombobox");
        assertThat(resource("/templates/board/list.html"))
                .contains("data-sort=\"bookmarks\"", "aria-pressed=${sort == 'bookmarks'}",
                        "sec:authorize=\"isAuthenticated()\"")
                .doesNotContain("boardType=course", "board-country-filter", "overseasCourseCountries",
                        "country-combobox.js", "직접 만든 코스");
        assertThat(resource("/templates/board/fragment.html"))
                .contains("/post/${item.id}", "board-bookmarks", "board-profile-link")
                .doesNotContain("/course/", "courseKind", "item.themes", "board-list-row--course");
    }

    @Test
    void writeChooserOffersOnlyQuestionAndTipAndKeepsCloseControls() throws IOException {
        assertThat(resource("/templates/board/list.html"))
                .contains("class=\"board-write-btn\" data-board-write-open", "~{board/write-modal :: writeModal}");
        assertThat(resource("/templates/board/write-modal.html"))
                .contains("th:fragment=\"writeModal\"", "sec:authorize=\"isAuthenticated()\"",
                        "@{/post/write(postType='QUESTION')}", "@{/post/write(postType='TIP')}")
                .doesNotContain("/course/write", "나의 여행코스");
        assertThat(resource("/static/js/board-write-modal.js"))
                .contains("[data-board-write-open]", "modal.hidden = false", "event.target === modal",
                        "event.target.closest('[data-board-write-close]')", "event.key === 'Escape'");
        assertThat(resource("/static/css/board-write-modal.css"))
                .contains(".board-write-modal[hidden]", ".board-write-option");
    }

    @Test
    void postWriteFormPreservesTheChosenTypeAndUsesTheSameChooser() throws IOException {
        assertThat(resource("/templates/post/write.html"))
                .doesNotContain("id=\"postTypeSelect\"", "<select")
                .contains("<input type=\"hidden\" name=\"postType\" th:value=\"${currentPostType}\">",
                        "param.postType[0] == 'TIP'", "<h1 class=\"write-title\">글쓰기</h1>",
                        "class=\"write-type-badge\"", "${currentPostType == 'TIP' ? '여행 팁' : '여행 질문'}",
                        "class=\"write-type-change\" data-board-write-open", "~{board/write-modal :: writeModal}",
                        "/js/board-write-modal.js");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
