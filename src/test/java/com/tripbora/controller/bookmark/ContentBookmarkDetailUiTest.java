package com.tripbora.controller.bookmark;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.course.CourseController;
import com.tripbora.controller.post.PostController;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.PostDetailDto;
import com.tripbora.model.PostType;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PostController.class, CourseController.class})
@Import(SecurityConfig.class)
class ContentBookmarkDetailUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;
    @MockitoBean
    private CourseService courseService;
    @MockitoBean
    private CountryCategoryService countryCategoryService;
    @MockitoBean
    private FileUploadService fileUploadService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @ParameterizedTest
    @MethodSource("postBookmarkStates")
    void postBookmarkRendersInTitleRowForQuestionAndTip(
            long id, PostType postType, boolean bookmarked, String expectedType, String expectedLabel
    ) throws Exception {
        when(postService.getPostDetail(id, null)).thenReturn(post(id, postType, bookmarked));

        String body = mockMvc.perform(get("/post/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains(expectedType)
                .contains("content-detail-title-row post-title-row")
                .contains("data-bookmark-url=\"/bookmarks/posts/" + id + "\"")
                .contains("data-bookmarked=\"" + bookmarked + "\"")
                .contains("aria-pressed=\"" + bookmarked + "\"")
                .contains("content-bookmark-image")
                .contains(expectedLabel);
        assertBookmarkState(body, bookmarked);
        assertThat(footerSection(body, "post-detail-footer")).doesNotContain("content-bookmark-button");
    }

    @ParameterizedTest
    @MethodSource("courseBookmarkStates")
    void courseBookmarkRendersInTitleRow(boolean bookmarked, String expectedLabel, long id) throws Exception {
        when(courseService.getCourseDetail(eq(id), isNull(), any()))
                .thenReturn(course(id, bookmarked));

        String body = mockMvc.perform(get("/courses/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("content-detail-title-row course-title-row")
                .contains("data-bookmark-url=\"/bookmarks/courses/" + id + "\"")
                .contains("data-bookmarked=\"" + bookmarked + "\"")
                .contains("aria-pressed=\"" + bookmarked + "\"")
                .contains("content-bookmark-image")
                .contains(expectedLabel);
        assertBookmarkState(body, bookmarked);
        assertThat(footerSection(body, "course-detail-footer")).doesNotContain("content-bookmark-button");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void courseOwnerMenuRendersOnlyForOwnerAndKeepsDeleteCsrf(boolean myCourse) throws Exception {
        CourseDetailDto detail = course(20L, false);
        detail.setMyCourse(myCourse);
        when(courseService.getCourseDetail(eq(20L), isNull(), any())).thenReturn(detail);

        String body = mockMvc.perform(get("/courses/20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(body);
        var menus = document.select(".course-summary-header .course-owner-menu");
        assertThat(menus).hasSize(myCourse ? 1 : 0);
        assertThat(document.select(".course-detail-footer .course-delete-form")).isEmpty();
        if (myCourse) {
            var menu = menus.first();
            assertThat(menu.selectFirst("summary").attr("aria-label"))
                    .isNotBlank().doesNotContain("#{");
            assertThat(menu.selectFirst(".course-edit-button").attr("href")).isEqualTo("/courses/20/edit");
            var form = menu.selectFirst("form");
            assertThat(form.attr("action")).isEqualTo("/courses/20/delete");
            assertThat(form.attr("method")).isEqualTo("post");
            assertThat(form.attr("data-confirm")).isNotBlank();
            assertThat(form.selectFirst("input[name=_csrf]").attr("value")).isNotBlank();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void postOwnerMenuRendersOnlyForOwnerAndKeepsExistingDeleteConfirm(boolean myPost) throws Exception {
        PostDetailDto detail = post(10L, PostType.TIP, false);
        detail.setMyPost(myPost);
        when(postService.getPostDetail(10L, null)).thenReturn(detail);

        String body = mockMvc.perform(get("/post/10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = Jsoup.parse(body);
        var menus = document.select(".post-detail-header .post-owner-menu");
        assertThat(menus).hasSize(myPost ? 1 : 0);
        assertThat(document.select(".post-detail-footer .post-edit-button, "
                + ".post-detail-footer .post-delete-form")).isEmpty();
        if (myPost) {
            var menu = menus.first();
            assertThat(menu.selectFirst("summary")).isNotNull();
            assertThat(menu.selectFirst(".post-edit-button").attr("href")).isEqualTo("/post/10/edit");
            var form = menu.selectFirst("form");
            assertThat(form.attr("action")).isEqualTo("/post/10/delete");
            assertThat(form.attr("method")).isEqualTo("post");
            assertThat(form.attr("onsubmit")).contains("confirm(");
            assertThat(form.selectFirst("input[name=_csrf]").attr("value")).isNotBlank();
        }
    }

    @Test
    void anonymousPostAndCourseKeepReadonlyCommentTextareaWithLoginLink() throws Exception {
        when(postService.getPostDetail(10L, null)).thenReturn(post(10L, PostType.TIP, false));
        when(courseService.getCourseDetail(eq(20L), isNull(), any())).thenReturn(course(20L, false));

        String postBody = mockMvc.perform(get("/post/10"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String courseBody = mockMvc.perform(get("/courses/20"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertAnonymousCommentComposer(Jsoup.parse(postBody), ".post-comment-guest");
        assertAnonymousCommentComposer(Jsoup.parse(courseBody), ".course-comment-guest");
    }

    private void assertAnonymousCommentComposer(org.jsoup.nodes.Document document, String guestSelector) {
        var guest = document.selectFirst(guestSelector);
        assertThat(guest).isNotNull();
        var textarea = guest.selectFirst("textarea[readonly]");
        assertThat(textarea.attr("placeholder")).isNotBlank();
        assertThat(textarea.hasAttr("data-guest-comment-redirect")).isTrue();
        assertThat(guest.select("input[type=file], button[type=submit], .image-upload-label")).isEmpty();
        assertThat(guest.select("p, a")).isEmpty();
    }

    private static Stream<Arguments> postBookmarkStates() {
        return Stream.of(
                Arguments.of(10L, PostType.QUESTION, false, "여행 질문", "북마크 저장"),
                Arguments.of(11L, PostType.QUESTION, true, "여행 질문", "북마크 취소"),
                Arguments.of(12L, PostType.TIP, false, "여행 팁", "북마크 저장"),
                Arguments.of(13L, PostType.TIP, true, "여행 팁", "북마크 취소")
        );
    }

    private static Stream<Arguments> courseBookmarkStates() {
        return Stream.of(
                Arguments.of(false, "북마크 저장", 20L),
                Arguments.of(true, "북마크 취소", 21L)
        );
    }

    private PostDetailDto post(long id, PostType postType, boolean bookmarked) {
        PostDetailDto post = new PostDetailDto();
        post.setId(id);
        post.setPostType(postType);
        post.setTitle("아주 긴 여행 게시글 제목");
        post.setContent("<p>본문</p>");
        post.setNickname("여행자");
        post.setCreatedAt(Timestamp.valueOf("2026-08-07 10:00:00"));
        post.setUpdatedAt(Timestamp.valueOf("2026-08-07 11:00:00"));
        post.setImages(List.of());
        post.setBookmarked(bookmarked);
        return post;
    }

    private CourseDetailDto course(long id, boolean bookmarked) {
        CourseDetailDto course = new CourseDetailDto();
        course.setId(id);
        course.setTitle("아주 긴 여행 코스 제목");
        course.setContent("<p>코스 소개</p>");
        course.setNickname("여행자");
        course.setCreatedAt(Timestamp.valueOf("2026-08-07 10:00:00"));
        course.setUpdatedAt(Timestamp.valueOf("2026-08-07 11:00:00"));
        course.setStops(List.of());
        course.setBookmarked(bookmarked);
        return course;
    }

    private void assertBookmarkState(String body, boolean bookmarked) {
        int start = body.indexOf("<div class=\"content-detail-title-row");
        int end = body.indexOf("</div>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        String titleRow = body.substring(start, end);
        if (bookmarked) {
            assertThat(titleRow).contains("is-bookmarked");
        } else {
            assertThat(titleRow).doesNotContain("is-bookmarked");
        }
    }

    /** 북마크는 제목 우측 액션 영역에만 있고 하단 footer 로 내려오지 않는다. */
    private String footerSection(String body, String className) {
        int start = body.indexOf("<footer class=\"" + className + "\">");
        int end = body.indexOf("</footer>", start);
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(end).isGreaterThan(start);
        return body.substring(start, end);
    }
}
