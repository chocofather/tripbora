package com.tripbora.controller.course;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.CourseDetailDto;
import com.tripbora.dto.CourseStopDto;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.CountryCategoryService;
import com.tripbora.service.course.CourseService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 상세 화면의 작성자 표시.
 *
 * <p>최종 탈퇴 회원은 users.nickname 에 내부 익명값이 들어 있고 공개 프로필도 404 이므로,
 * 화면에는 그 값도 프로필 링크도 남으면 안 된다.
 */
@WebMvcTest(CourseController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class CourseDetailWithdrawnWriterRenderingTest {

    private static final String ANONYMIZED_NICKNAME = "탈퇴b62167acec";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;
    @MockitoBean
    private CountryCategoryService countryCategoryService;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean
    private UserMapper userMapper;

    /** 아직 탈퇴하지 않은 회원은 기존과 완전히 같다. 닉네임도 프로필 링크도 그대로다. */
    @Test
    void anOrdinaryWriterKeepsTheNicknameAndTheProfileLink() throws Exception {
        String body = render(course("여행자민준", false), SupportedLanguage.KOREAN);

        assertThat(body)
                .contains("여행자민준")
                .contains("href=\"/users/5\"")
                .doesNotContain("탈퇴한 회원");
    }

    @Test
    void aWithdrawnWriterShowsTheSharedLabelWithoutAnyProfileLink() throws Exception {
        String body = render(course(ANONYMIZED_NICKNAME, true), SupportedLanguage.KOREAN);

        assertThat(body)
                .contains("탈퇴한 회원")
                .doesNotContain(ANONYMIZED_NICKNAME)
                .doesNotContain("href=\"/users/5\"");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | 탈퇴한 회원",
            "en    | Former member",
            "ja    | 退会した会員",
            "zh-CN | 已注销会员",
            "zh-TW | 已註銷會員"
    })
    void theLabelIsRenderedInTheChosenLanguage(String languageTag, String expected)
            throws Exception {
        SupportedLanguage language = SupportedLanguage.fromLocale(
                java.util.Locale.forLanguageTag(languageTag)).orElseThrow();

        String body = render(course(ANONYMIZED_NICKNAME, true), language);

        assertThat(body).contains(expected).doesNotContain(ANONYMIZED_NICKNAME);
    }

    private String render(CourseDetailDto course, SupportedLanguage language) throws Exception {
        when(courseService.getCourseDetail(7L, null, language)).thenReturn(course);

        return mockMvc.perform(get("/courses/{id}", 7L)
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME,
                                language.getLanguageTag())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private CourseDetailDto course(String nickname, boolean writerWithdrawn) {
        CourseDetailDto course = new CourseDetailDto();
        course.setId(7L);
        course.setUserId(5L);
        course.setTitle("여행 코스 테스트");
        course.setContent("<p>여행 코스 입니다</p>");
        course.setNickname(nickname);
        course.setWriterWithdrawn(writerWithdrawn);
        course.setCreatedAt(Timestamp.valueOf("2026-01-02 10:00:00"));
        course.setUpdatedAt(Timestamp.valueOf("2026-01-03 11:00:00"));
        course.setViews(12);
        course.setStops(List.of(stop()));
        return course;
    }

    private CourseStopDto stop() {
        CourseStopDto stop = new CourseStopDto();
        stop.setDestinationId(15L);
        stop.setVisitOrder(1);
        stop.setName("경복궁");
        stop.setRegionId(235L);
        stop.setRegionName("종로구");
        return stop;
    }
}
