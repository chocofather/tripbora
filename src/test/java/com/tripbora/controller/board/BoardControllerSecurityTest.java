package com.tripbora.controller.board;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.BoardListDto;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.board.BoardService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BoardController.class)
@Import(SecurityConfig.class)
class BoardControllerSecurityTest {
    @Autowired private MockMvc mockMvc;
    @MockitoBean private BoardService boardService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void guestCanOpenCommunityWithoutWriteActionOrCourseChoice() throws Exception {
        when(boardService.getBoardList(null, null, "all", null, "latest", 1, 10)).thenReturn(List.of());
        String html = mockMvc.perform(get("/board/list"))
                .andExpect(status().isOk()).andExpect(view().name("board/list"))
                .andExpect(model().attribute("pageTitle", "여행 커뮤니티"))
                .andReturn().getResponse().getContentAsString();
        var page = Jsoup.parse(html);
        assertThat(page.select("[data-board-write-open], #board-write-modal, .board-country-filter")).isEmpty();
        assertThat(page.select(".board-sidebar a").eachAttr("href"))
                .containsExactly("/board/list", "/board/list?boardType=post&postType=question",
                        "/board/list?boardType=post&postType=tip");
    }

    @Test
    void memberSeesQuestionAndTipChoicesAtBothWriteActions() throws Exception {
        String html = mockMvc.perform(get("/board/list").with(user("member")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var page = Jsoup.parse(html);
        assertThat(page.select("[data-board-write-open]")).hasSize(2);
        assertThat(page.select(".board-write-option").eachAttr("href"))
                .containsExactly("/post/write?postType=QUESTION", "/post/write?postType=TIP");
        assertThat(html.indexOf("board-list-actions")).isGreaterThan(html.indexOf("board-fragment-container"));
        assertThat(html.indexOf("board-mobile-write-action")).isLessThan(html.indexOf("board-fragment-container"));
    }

    @Test
    void onlyLegacyCourseUrlsRedirectAndNeverQueryCommunity() throws Exception {
        for (String endpoint : List.of("list", "fragment")) {
            mockMvc.perform(get("/board/" + endpoint).param("boardType", "COURSE")
                            .param("countryId", "8").param("sort", "views"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/courses?sort=views&countryId=8"));
            mockMvc.perform(get("/board/" + endpoint).param("boardType", "course")
                            .param("countryId", "-1").param("scope", "domestic").param("sort", "comments"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/courses?sort=latest"));
        }
        verifyNoInteractions(boardService);
    }

    @Test
    void ordinaryCommunityIgnoresObsoleteGeographyAndClampsPagination() throws Exception {
        mockMvc.perform(get("/board/fragment").param("boardType", "post")
                        .param("postType", "question").param("scope", "overseas").param("countryId", "8")
                        .param("page", "0").param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("pageSize", 100))
                .andExpect(model().attribute("currentPage", 1));
        verify(boardService).getBoardList("post", "question", "all", null, "latest", 1, 100);
        verify(boardService).getBoardCount("post", "question", "all", null);
    }

    @Test
    void unsupportedSortAndBookmarksShowTheMatchingActiveButton() throws Exception {
        for (String sort : List.of("unsupported", "bookmarks")) {
            String html = mockMvc.perform(get("/board/list").param("sort", sort))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(Jsoup.parse(html).select(".board-sort-button[aria-pressed=true]").eachAttr("data-sort"))
                    .containsExactly("unsupported".equals(sort) ? "latest" : "bookmarks");
        }
    }

    @Test
    void smartphonePaginationKeepsQuestionsTipsAndEverySortForPageAndFragment() throws Exception {
        String[][] filters = {{null, null}, {"post", "question"}, {"post", "tip"}};
        int filterIndex = 0;
        for (String[] filter : filters) {
            for (String sort : List.of("latest", "oldest", "views", "comments", "bookmarks")) {
                for (int page = 1; page <= 3; page++) {
                    int count = page == 1 ? 8 : page == 2 ? 1 : 0;
                    var rows = new java.util.ArrayList<BoardListDto>();
                    for (int row = 0; row < count; row++) {
                        BoardListDto item = new BoardListDto();
                        item.setId((long) row + 1);
                        item.setBoardType("post");
                        item.setPostType("tip".equals(filter[1]) ? "TIP" : "QUESTION");
                        item.setTitle("서울 여행 질문과 LongTravelTitleWithoutSpaces12345678901234567890");
                        item.setNickname("귀여운곰돌이와아주긴작성자이름");
                        item.setUserId(1L);
                        item.setCreatedAt("2026-09-17 00:36:00");
                        item.setViews(28);
                        item.setCommentCount(4);
                        item.setBookmarkCount(2);
                        rows.add(item);
                    }
                    when(boardService.getBoardList(filter[0], filter[1], "all", null, sort, page, 8)).thenReturn(rows);
                    when(boardService.getBoardCount(filter[0], filter[1], "all", null)).thenReturn(page == 3 ? 0 : 9);
                    for (String endpoint : List.of("list", "fragment")) {
                        var request = get("/board/" + endpoint).param("sort", sort)
                                .param("page", String.valueOf(page)).param("size", "8");
                        if (filter[0] != null) request.param("boardType", filter[0]);
                        if (filter[1] != null) request.param("postType", filter[1]);
                        String html = mockMvc.perform(request).andExpect(status().isOk())
                                .andExpect(model().attribute("pageSize", 8))
                                .andExpect(model().attribute("totalPages", page == 3 ? 0 : 2))
                                .andReturn().getResponse().getContentAsString();
                        var document = Jsoup.parse(html);
                        assertThat(document.select(".board-list-row")).hasSize(count);
                        assertThat(document.select(".board-title a").eachAttr("href"))
                                .allMatch(href -> href.startsWith("/post/"));
                        assertThat(document.select(".board-list-row--course, .course-theme-badges")).isEmpty();
                        assertThat(html).contains("data-page-size=\"8\"");
                        if (count > 0) assertThat(html).contains("26.09.17 00:36", "board-bookmarks");
                        String exportPath = System.getenv("BOARD_BROWSER_FIXTURES");
                        if (exportPath != null) {
                            Path directory = Path.of(exportPath);
                            Files.createDirectories(directory);
                            Files.writeString(directory.resolve(filterIndex + "-" + sort + "-" + page + "-" + endpoint + ".html"), html);
                        }
                    }
                }
            }
            filterIndex++;
        }
    }
}
