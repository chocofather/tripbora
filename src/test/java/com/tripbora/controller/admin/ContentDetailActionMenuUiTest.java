package com.tripbora.controller.admin;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.course.CourseController;
import com.tripbora.controller.post.PostController;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.PostDetailDto;
import com.tripbora.model.PostType;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostService;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 상세페이지 상단 액션 메뉴 권한 계약.
 * 작성자 ⋯ 는 본인 글에만, 관리자 관리 ▾ 는 ADMIN 에게만 나오고 둘은 서로 섞이지 않는다.
 * 하단에는 수정/삭제/숨김 중복 버튼이 남지 않는다.
 */
@WebMvcTest({PostController.class, CourseController.class})
@Import(SecurityConfig.class)
class ContentDetailActionMenuUiTest {

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

    /* ---------- 게시글 ---------- */

    @Test
    void postOwnerSeesOnlyTheOwnerMenu() throws Exception {
        Document document = postDetail(31L, true, "USER");

        assertOwnerMenu(document, "post", true);
        assertAdminMenu(document, "post", false);
    }

    @Test
    void postNonOwnerSeesNeitherMenu() throws Exception {
        Document document = postDetail(32L, false, "USER");

        assertOwnerMenu(document, "post", false);
        assertAdminMenu(document, "post", false);
    }

    @Test
    void adminOnSomeoneElsesPostSeesOnlyTheAdminMenu() throws Exception {
        Document document = postDetail(33L, false, "ADMIN");

        assertOwnerMenu(document, "post", false);
        assertAdminMenu(document, "post", true);
    }

    @Test
    void adminOnOwnPostSeesBothMenus() throws Exception {
        Document document = postDetail(34L, true, "ADMIN");

        assertOwnerMenu(document, "post", true);
        assertAdminMenu(document, "post", true);
    }

    @Test
    void postFooterKeepsNoDuplicateOwnerOrAdminActions() throws Exception {
        Document document = postDetail(35L, true, "ADMIN");

        assertThat(document.select(".post-detail-footer .post-edit-button")).isEmpty();
        assertThat(document.select(".post-detail-footer .post-delete-form")).isEmpty();
        assertThat(document.select(".post-detail-footer .post-hide-button")).isEmpty();
        assertThat(document.select(".post-detail-footer [data-post-hide-open]")).isEmpty();
        // 숨김 버튼은 관리 메뉴 안에 한 번만 존재한다.
        assertThat(document.select("[data-post-hide-open]")).hasSize(1);
    }

    /* ---------- 여행코스 ---------- */

    @Test
    void courseOwnerSeesOnlyTheOwnerMenu() throws Exception {
        Document document = courseDetail(41L, true, "USER");

        assertOwnerMenu(document, "course", true);
        assertAdminMenu(document, "course", false);
    }

    @Test
    void courseNonOwnerSeesNeitherMenu() throws Exception {
        Document document = courseDetail(42L, false, "USER");

        assertOwnerMenu(document, "course", false);
        assertAdminMenu(document, "course", false);
    }

    @Test
    void adminOnSomeoneElsesCourseSeesOnlyTheAdminMenu() throws Exception {
        Document document = courseDetail(43L, false, "ADMIN");

        assertOwnerMenu(document, "course", false);
        assertAdminMenu(document, "course", true);
    }

    @Test
    void adminOnOwnCourseSeesBothMenus() throws Exception {
        Document document = courseDetail(44L, true, "ADMIN");

        assertOwnerMenu(document, "course", true);
        assertAdminMenu(document, "course", true);
    }

    @Test
    void courseFooterKeepsNoDuplicateOwnerOrAdminActions() throws Exception {
        Document document = courseDetail(45L, true, "ADMIN");

        assertThat(document.select(".course-detail-footer .course-edit-button")).isEmpty();
        assertThat(document.select(".course-detail-footer .course-delete-form")).isEmpty();
        assertThat(document.select(".course-detail-footer .course-hide-button")).isEmpty();
        assertThat(document.select(".course-detail-footer [data-course-hide-open]")).isEmpty();
        assertThat(document.select("[data-course-hide-open]")).hasSize(1);
    }

    /* ---------- 공통 검증 ---------- */

    /** 작성자 메뉴에는 수정/삭제만 있고 숨김은 절대 섞이지 않는다. */
    private void assertOwnerMenu(Document document, String feature, boolean expected) {
        var menus = document.select("." + feature + "-owner-menu:not(." + feature + "-admin-menu)");
        assertThat(menus).hasSize(expected ? 1 : 0);
        if (!expected) {
            return;
        }
        var menu = menus.first();
        assertThat(menu.selectFirst("." + feature + "-edit-button")).isNotNull();
        assertThat(menu.selectFirst("." + feature + "-delete-button")).isNotNull();
        assertThat(menu.selectFirst("." + feature + "-hide-button")).isNull();
    }

    /** 관리 메뉴는 공개 상태 상세에서 숨김만 제공한다(숨김 해제는 /admin/contents 담당). */
    private void assertAdminMenu(Document document, String feature, boolean expected) {
        var menus = document.select("." + feature + "-admin-menu");
        assertThat(menus).hasSize(expected ? 1 : 0);
        if (!expected) {
            assertThat(document.select("[data-" + feature + "-hide-open]")).isEmpty();
            return;
        }
        var menu = menus.first();
        assertThat(menu.selectFirst("summary").text()).contains("관리");
        assertThat(menu.selectFirst("[data-" + feature + "-hide-open]")).isNotNull();
        assertThat(menu.selectFirst("." + feature + "-hide-button").text()).isEqualTo("숨김");
        assertThat(menu.selectFirst("." + feature + "-edit-button")).isNull();
        assertThat(menu.selectFirst("." + feature + "-delete-button")).isNull();
    }

    private Document postDetail(long id, boolean myPost, String role) throws Exception {
        PostDetailDto post = new PostDetailDto();
        post.setId(id);
        post.setPostType(PostType.TIP);
        post.setTitle("여행 게시글 제목");
        post.setContent("<p>본문</p>");
        post.setNickname("여행자");
        post.setCreatedAt(Timestamp.valueOf("2026-08-07 10:00:00"));
        post.setUpdatedAt(Timestamp.valueOf("2026-08-07 11:00:00"));
        post.setImages(List.of());
        post.setMyPost(myPost);
        when(postService.getPostDetail(eq(id), isNull())).thenReturn(post);

        return Jsoup.parse(mockMvc.perform(get("/post/{id}", id).with(user("tester").roles(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private Document courseDetail(long id, boolean myCourse, String role) throws Exception {
        CourseDetailDto course = new CourseDetailDto();
        course.setId(id);
        course.setTitle("여행 코스 제목");
        course.setContent("<p>코스 소개</p>");
        course.setNickname("여행자");
        course.setCreatedAt(Timestamp.valueOf("2026-08-07 10:00:00"));
        course.setUpdatedAt(Timestamp.valueOf("2026-08-07 11:00:00"));
        course.setStops(List.of());
        course.setMyCourse(myCourse);
        when(courseService.getCourseDetail(eq(id), isNull(), any())).thenReturn(course);

        return Jsoup.parse(mockMvc.perform(get("/courses/{id}", id).with(user("tester").roles(role)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
