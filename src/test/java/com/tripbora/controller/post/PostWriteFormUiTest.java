package com.tripbora.controller.post;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.file.FileUploadService;
import com.tripbora.service.post.PostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 질문/팁 작성폼은 카테고리 select 없이, 진입 단계에서 정해진 종류를 배지로 보여주고
 * 같은 값을 그대로 저장한다.
 */
@WebMvcTest(PostController.class)
@Import(SecurityConfig.class)
class PostWriteFormUiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PostService postService;
    @MockitoBean
    private FileUploadService fileUploadService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    @Test
    void tipEntryShowsTheTipBadgeAndSubmitsTheSameTypeValue() throws Exception {
        String body = render("TIP");

        assertThat(body)
                .contains("여행 팁")
                .contains("name=\"postType\"")
                .contains("value=\"TIP\"")
                // 카테고리 select 는 더 이상 없다
                .doesNotContain("postTypeSelect")
                .doesNotContain("<select");
    }

    @Test
    void questionIsUsedWhenNoTypeIsGivenAndTheChooserIsAvailable() throws Exception {
        String noParam = render(null);
        String questionEntry = render("QUESTION");

        assertThat(noParam).contains("value=\"QUESTION\"").contains("여행 질문");
        assertThat(questionEntry).contains("value=\"QUESTION\"").contains("여행 질문");
        // 종류 변경 버튼과 선택 모달이 함께 렌더링된다
        assertThat(questionEntry)
                .contains("write-type-change")
                .contains("data-board-write-open")
                .contains("id=\"board-write-modal\"")
                .contains("/post/write?postType=TIP")
                .doesNotContain("/course/write", "/courses/new");
    }

    @Test
    void guideTextAndEditorPlaceholderFollowTheSelectedType() throws Exception {
        assertThat(render("QUESTION"))
                .contains("여행 중 궁금했던 내용을 다른 여행자에게 물어보세요.")
                .contains("data-placeholder=\"궁금한 내용을 자세히 작성해 주세요.\"");
        assertThat(render("TIP"))
                .contains("직접 경험한 유용한 여행 정보를 다른 여행자와 나눠보세요.")
                .contains("data-placeholder=\"다른 여행자에게 도움이 될 여행 팁을 공유해 주세요.\"");
    }

    @Test
    void titleAndContentHaveLabelsAndTheTitleKeepsItsExistingMaxLength() throws Exception {
        String body = render("QUESTION");

        assertThat(body)
                .contains(">제목</label>")
                .contains(">내용</span>")
                .contains("placeholder=\"제목을 입력해 주세요.\"")
                // 최대 길이는 기존 값(255)을 그대로 쓴다
                .contains("maxlength=\"255\"")
                .contains("id=\"title-length\"")
                .contains(">0</span>/255");
    }

    private String render(String postType) throws Exception {
        var request = get("/post/write").with(user("tester").roles("USER"));
        if (postType != null) {
            request = request.param("postType", postType);
        }
        return mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
