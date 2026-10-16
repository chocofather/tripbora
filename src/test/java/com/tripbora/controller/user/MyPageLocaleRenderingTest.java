package com.tripbora.controller.user;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.MyPageProfileDto;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.board.BoardService;
import com.tripbora.service.bookmark.MyPageBookmarkService;
import com.tripbora.service.comment.MyPageCommentService;
import com.tripbora.service.user.MyPageService;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 마이페이지 문구가 요청 언어를 따르는지 본다. 링크·메뉴 식별은 언어와 무관하게 그대로여야 한다.
 */
@WebMvcTest(MyPageController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class MyPageLocaleRenderingTest {

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
    void englishRequestGetsEnglishNavigationWhileRoutesAndUserDataStayPut() throws Exception {
        when(myPageService.getProfile(7L)).thenReturn(profile("여행자", "member@example.com"));
        when(userMapper.hasLocalPasswordById(7L)).thenReturn(true);

        mockMvc.perform(get("/mypage").with(user(principal(7L)))
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, "en")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("a[href=/mypage/posts]").text())
                            .contains("My content");
                    assertThat(document.select("a[href=/mypage/comments]").text())
                            .contains("My comments");
                    assertThat(document.select("a[href=/mypage/bookmarks]").text())
                            .contains("Bookmarks");
                    assertThat(document.select("a[href=/mypage/account]").text())
                            .contains("Account & security");
                    assertThat(document.select(
                            ".mypage-navigation-title.is-active[aria-current=page]").text())
                            .isEqualTo("My Page");
                    // 사용자 데이터는 번역 대상이 아니다
                    assertThat(document.text()).contains("여행자", "member@example.com");
                });
    }

    @Test
    void japaneseProfileFormLocalizesLabelsAndTheClientSideNicknameMessages() throws Exception {
        when(myPageService.getProfile(7L)).thenReturn(profile("여행자", "member@example.com"));
        when(userMapper.hasLocalPasswordById(7L)).thenReturn(true);

        mockMvc.perform(get("/mypage/profile").with(user(principal(7L)))
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, "ja")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("label[for=nickname]").text())
                            .isEqualTo("ニックネーム");
                    assertThat(document.select("#profileSaveButton").text()).isEqualTo("保存");
                    // 스크립트가 쓰는 문구도 화면이 요청 언어로 내려 준다
                    assertThat(document.select("#nickname").attr("data-message-current"))
                            .isEqualTo("現在使用中のニックネームです。");
                    assertThat(document.select("#nickname").attr("data-message-too-short"))
                            .isEqualTo("ニックネームは2文字以上で入力してください。");
                    assertThat(document.select("#nickname").attr("data-current-nickname"))
                            .isEqualTo("여행자");
                    // 파일 칸은 남아 있고, 화면에 보이는 것은 커스텀 버튼과 파일명이다
                    assertThat(document.select("input#profileImageFile[type=file]"))
                            .singleElement()
                            .satisfies(input -> {
                                assertThat(input.attr("name")).isEqualTo("profileImageFile");
                                assertThat(input.className()).contains("mypage-file-input");
                            });
                    assertThat(document.select("label.mypage-file-button").attr("for"))
                            .isEqualTo("profileImageFile");
                    assertThat(document.select("label.mypage-file-button").text())
                            .isEqualTo("ファイルを選択");
                    assertThat(document.select("#profileImageFileName").text())
                            .isEqualTo("選択されたファイルはありません");
                });
    }

    private MyPageProfileDto profile(String nickname, String email) {
        MyPageProfileDto profile = new MyPageProfileDto();
        profile.setNickname(nickname);
        profile.setUserEmail(email);
        profile.setProfileImage("/images/default.png");
        return profile;
    }

    private CustomUserDetails principal(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserPassword("password");
        user.setUserRole(UserRole.USER);
        return new CustomUserDetails(user);
    }
}
