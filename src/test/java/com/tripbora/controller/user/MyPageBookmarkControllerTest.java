package com.tripbora.controller.user;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.dto.MyPageBookmarkPageDto;
import com.tripbora.dto.MyPageCommunityBookmarkDto;
import com.tripbora.dto.MyPageDestinationBookmarkDto;
import com.tripbora.dto.MyPageTravelInfoBookmarkDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.board.BoardService;
import com.tripbora.service.bookmark.MyPageBookmarkService;
import com.tripbora.service.comment.MyPageCommentService;
import com.tripbora.service.user.MyPageService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(MyPageController.class)
@Import(SecurityConfig.class)
class MyPageBookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyPageService myPageService;
    @MockitoBean
    private BoardService boardService;
    @MockitoBean
    private MyPageCommentService myPageCommentService;
    @MockitoBean
    private MyPageBookmarkService myPageBookmarkService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void guestIsRedirectedAndUserAdminUseOnlyTheirPrincipalIds() throws Exception {
        MyPageBookmarkPageDto empty = page(List.of(), "destination", "all", "all", 1, 0, 0);
        when(myPageBookmarkService.getBookmarks(7L, "destination", "all", "all", 1))
                .thenReturn(empty);
        when(myPageBookmarkService.getBookmarks(99L, "destination", "all", "all", 1))
                .thenReturn(empty);

        mockMvc.perform(get("/mypage/bookmarks"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?redirect=/mypage/bookmarks"));

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("userId", "999")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(view().name("mypage/bookmarks"));

        mockMvc.perform(get("/mypage/bookmarks")
                        .with(user(principal(99L, UserRole.ADMIN))))
                .andExpect(status().isOk());

        verify(myPageBookmarkService).getBookmarks(7L, "destination", "all", "all", 1);
        verify(myPageBookmarkService).getBookmarks(99L, "destination", "all", "all", 1);
    }

    @Test
    void controllerUsesCanonicalServiceResultForInvalidParameters() throws Exception {
        MyPageBookmarkPageDto empty = page(List.of(), "destination", "all", "all", 1, 0, 0);
        when(myPageBookmarkService.getBookmarks(7L, "ABC", "bad", "wrong", -2))
                .thenReturn(empty);

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("section", "ABC")
                        .param("scope", "bad")
                        .param("type", "wrong")
                        .param("page", "-2")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(model().attribute("section", "destination"))
                .andExpect(model().attribute("scope", "all"))
                .andExpect(model().attribute("type", "all"))
                .andExpect(model().attribute("currentPage", 1));
    }

    @Test
    void destinationRendersImageDetailDeleteAndFilterPreservingPagination() throws Exception {
        MyPageDestinationBookmarkDto item = new MyPageDestinationBookmarkDto();
        item.setTargetId(10L);
        item.setName("경복궁");
        item.setRegionName("서울특별시");
        item.setThumbnailUrl("/uploads/destinations/palace.jpg");
        when(myPageBookmarkService.getBookmarks(7L, "destination", "domestic", "all", 2))
                .thenReturn(page(List.of(item), "destination", "domestic", "all", 2, 2, 11));

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("section", "destination")
                        .param("scope", "domestic")
                        .param("page", "2")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("a[href=/destinations/10]")).hasSize(2);
                    assertThat(document.select("img[src=/uploads/destinations/palace.jpg]")).hasSize(1);
                    assertThat(document.select("[data-bookmark-delete-url=/bookmarks/destinations/10]")).hasSize(1);
                    assertThat(document.select("a[href='/mypage/bookmarks?section=destination&scope=domestic&page=1']")).hasSize(1);
                    assertThat(document.select(".mypage-navigation-link.is-active").text()).isEqualTo("북마크");
                });
    }

    @Test
    void communityRendersQuestionTipCourseLinksAndEscapedValues() throws Exception {
        List<MyPageCommunityBookmarkDto> items = List.of(
                community(20L, "post", "QUESTION", "<script>질문</script>"),
                community(21L, "post", "TIP", "여행 팁"),
                community(22L, "course", null, "여행 코스")
        );
        when(myPageBookmarkService.getBookmarks(7L, "community", "all", "all", 1))
                .thenReturn(page(items, "community", "all", "all", 1, 1, 3));

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("section", "community")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("a[href=/post/20]")).hasSize(1);
                    assertThat(document.select("a[href=/post/21]")).hasSize(1);
                    assertThat(document.select("a[href=/courses/22]")).hasSize(1);
                    assertThat(document.select("[data-bookmark-delete-url=/bookmarks/posts/20]")).hasSize(1);
                    assertThat(document.select("[data-bookmark-delete-url=/bookmarks/courses/22]")).hasSize(1);
                    assertThat(document.select(".mypage-bookmark-community-row script")).isEmpty();
                    assertThat(document.select(".mypage-bookmark-type").text())
                            .contains("여행 질문", "여행 팁", "여행 코스");
                });
    }

    @Test
    void officialCourseBookmarkRendersTheProjectedBrandWithoutAPersonalAuthorLink() throws Exception {
        MyPageCommunityBookmarkDto official = community(22L, "course", null, "공식 서울 코스");
        official.setNickname("TripBora");
        official.setWriterWithdrawn(false);
        when(myPageBookmarkService.getBookmarks(7L, "community", "all", "course", 1))
                .thenReturn(page(List.of(official), "community", "all", "course", 1, 1, 1));

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("section", "community").param("type", "course")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    var row = document.selectFirst(".mypage-bookmark-community-row");
                    assertThat(row.select(".mypage-bookmark-community-meta").text()).contains("TripBora");
                    assertThat(row.select("a[href^=/users/]")).isEmpty();
                    assertThat(row.text()).doesNotContain("탈퇴한 회원");
                });
    }

    @Test
    void travelInfoRendersMetadataPlaceholderDetailAndDeleteUrl() throws Exception {
        MyPageTravelInfoBookmarkDto item = new MyPageTravelInfoBookmarkDto();
        item.setTargetId(30L);
        item.setTitle("해외 전압 안내");
        item.setScope("INTERNATIONAL");
        item.setContentType("GENERAL");
        item.setCategoryName("여행 준비");
        item.setCreatedAt(LocalDateTime.of(2026, 8, 13, 10, 0));
        when(myPageBookmarkService.getBookmarks(7L, "travel-info", "international", "all", 1))
                .thenReturn(page(List.of(item), "travel-info", "international", "all", 1, 1, 1));

        mockMvc.perform(get("/mypage/bookmarks")
                        .param("section", "travel-info")
                        .param("scope", "international")
                        .with(user(principal(7L, UserRole.USER))))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("a[href=/travel-info/30]")).hasSize(2);
                    assertThat(document.select("[data-bookmark-delete-url=/bookmarks/travel-info/30]")).hasSize(1);
                    assertThat(document.select(".mypage-bookmark-placeholder")).hasSize(1);
                    assertThat(document.text()).contains("해외", "여행 준비", "일반");
                });
    }

    private MyPageCommunityBookmarkDto community(Long id, String boardType,
                                                   String postType, String title) {
        MyPageCommunityBookmarkDto item = new MyPageCommunityBookmarkDto();
        item.setTargetId(id);
        item.setBoardType(boardType);
        item.setPostType(postType);
        item.setTitle(title);
        item.setNickname("여행자");
        item.setCreatedAt(LocalDateTime.of(2026, 8, 13, 10, 0));
        item.setViews(3L);
        return item;
    }

    private MyPageBookmarkPageDto page(List<?> bookmarks, String section,
                                        String scope, String type, int currentPage,
                                        int totalPages, int totalCount) {
        return new MyPageBookmarkPageDto(
                bookmarks, section, scope, type, currentPage, totalPages, totalCount);
    }

    private CustomUserDetails principal(Long id, UserRole role) {
        User user = new User();
        user.setId(id);
        user.setUserPassword("password");
        user.setUserRole(role);
        return new CustomUserDetails(user);
    }
}
