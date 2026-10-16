package com.tripbora.controller.board;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.controller.course.CourseController;
import com.tripbora.controller.post.PostController;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.dto.PostDetailDto;
import com.tripbora.model.PostType;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostService;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PostController.class, CourseController.class})
@Import(SecurityConfig.class)
class CommunityDetailMobileRenderingTest {
    @Autowired private MockMvc mvc;
    @MockitoBean private PostService postService;
    @MockitoBean private CourseService courseService;
    @MockitoBean private CountryCategoryService countryCategoryService;
    @MockitoBean private FileUploadService fileUploadService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Test
    void postReadingStylesPreserveRichTextAndRoleSpecificControls() throws Exception {
        for (String role : List.of("guest", "USER", "ADMIN")) {
            for (int variant = 0; variant < 5; variant++) {
                PostDetailDto post = new PostDetailDto();
                post.setId(10L);
                post.setUserId(5L);
                post.setPostType(variant % 2 == 0 ? PostType.QUESTION : PostType.TIP);
                post.setTitle("My Favorite Places to Visit in Seoul with SupercalifragilisticexpialidociousLongTitle");
                String paragraph = "<p>서울의 골목을 따라 걷고 여행의 순간을 기록합니다.</p>";
                String content = variant == 0 ? paragraph : paragraph.repeat(8)
                        + "<p><br></p><blockquote>여행의 작은 순간</blockquote><ul><li>첫 장소</li><li>두 번째 장소</li></ul>";
                if (variant >= 2) content += "<p><img src='/fixture-landscape.svg' width='1200' height='900'></p>";
                if (variant >= 3) content += "<p><img src='/fixture-portrait.svg' width='600' height='1000'></p>";
                post.setContent(content);
                post.setNickname("귀여운곰돌이");
                post.setCreatedAt(Timestamp.valueOf("2026-09-09 23:23:00"));
                post.setUpdatedAt(Timestamp.valueOf("2026-10-01 10:00:00"));
                post.setViews(7);
                post.setMyPost(!role.equals("guest"));
                when(postService.getPostDetail(eq(10L), nullable(Long.class))).thenReturn(post);
                String html = render("post", role, variant);
                var document = Jsoup.parse(html);
                assertThat(document.selectFirst("#user-post-content").html())
                        .isEqualTo(Jsoup.parseBodyFragment(content).body().html());
                assertThat(document.selectFirst(".back-to-list").attr("href"))
                        .isEqualTo("/board/list?boardType=post");
                verifyControls(html, role, "post");
            }
        }
    }

    @Test
    void itineraryKeepsDataOrderAndDestinationLinksForEveryStopCount() throws Exception {
        for (String role : List.of("guest", "USER", "ADMIN")) {
            for (int count : List.of(1, 2, 3, 5)) {
                CourseDetailDto course = new CourseDetailDto();
                course.setId(10L);
                course.setUserId(5L);
                course.setTitle("서울 궁궐과 골목을 따라 떠나는 하루 여행코스");
                course.setContent("<p>궁궐과 한강을 함께 즐기는 여행입니다.</p>");
                course.setNickname("귀여운곰돌이");
                course.setCreatedAt(Timestamp.valueOf("2026-09-17 00:36:00"));
                course.setUpdatedAt(Timestamp.valueOf("2026-10-01 10:00:00"));
                course.setViews(5);
                course.setMyCourse(!role.equals("guest"));
                List<CourseStopDto> stops = new ArrayList<>();
                for (int index = 1; index <= count; index++) {
                    CourseStopDto stop = new CourseStopDto();
                    stop.setDestinationId((long) index);
                    stop.setVisitOrder(index);
                    stop.setName(index == 2 ? "A very long destination name without spaces Supercalifragilisticexpialidocious" : "여행지 " + index);
                    stop.setRegionName(index == 1 ? "공주시" : "종로구");
                    stop.setShortDescription("여행 중 들러보고 싶은 장소의 소개를 그대로 유지합니다.");
                    stop.setImageUrl(index == 3 ? null : "/fixture-landscape.svg");
                    stops.add(stop);
                }
                course.setStops(stops);
                when(courseService.getCourseDetail(eq(10L), nullable(Long.class), any())).thenReturn(course);
                String html = render("course", role, count);
                var document = Jsoup.parse(html);
                assertThat(document.select(".course-stop-marker").eachText())
                        .containsExactlyElementsOf(stops.stream().map(s -> s.getVisitOrder().toString()).toList());
                assertThat(document.select(".course-stop-card").eachAttr("href"))
                        .containsExactlyElementsOf(stops.stream().map(s -> "/destinations/" + s.getDestinationId()).toList());
                assertThat(document.select(".course-stop-description")).hasSize(count);
                verifyControls(html, role, "course");
            }
        }
    }

    private String render(String type, String role, int variant) throws Exception {
        var request = get("/" + ("course".equals(type) ? "courses" : type) + "/10");
        if (!role.equals("guest")) request.with(user("reader").roles(role));
        String html = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String directory = System.getenv("COMMUNITY_DETAIL_BROWSER_FIXTURES");
        if (directory != null) {
            Path path = Path.of(directory);
            Files.createDirectories(path);
            Files.writeString(path.resolve(type + "-" + role + "-" + variant + ".html"), html);
        }
        return html;
    }

    private void verifyControls(String html, String role, String type) {
        var document = Jsoup.parse(html);
        assertThat(document.select("link[href='/css/community-detail-mobile.css']")).hasSize(1);
        assertThat(document.select("dd[data-mobile-date]")).hasSize(2);
        assertThat(document.select("[data-guest-comment-redirect]")).hasSize(role.equals("guest") ? 1 : 0);
        assertThat(document.select("#" + type + "-comment-form")).hasSize(role.equals("guest") ? 0 : 1);
        assertThat(document.select("[data-" + type + "-hide-open]")).hasSize(role.equals("ADMIN") ? 1 : 0);
    }
}
