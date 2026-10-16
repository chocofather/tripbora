package com.tripbora.controller.recommend;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.repository.user.UserMapper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import jakarta.servlet.http.Cookie;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(value = RandomTravelController.class,
        properties = "app.operator-name=운영자테스트")
@Import({SecurityConfig.class, I18nConfig.class})
class RandomTravelControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Test
    void guestCanOpenRandomTravelPageWithTheSharedLayoutAndControls() throws Exception {
        mockMvc.perform(get("/random-travel"))
                .andExpect(status().isOk())
                .andExpect(view().name("random-travel"))
                .andExpect(model().attribute("pageTitle", "랜덤 여행"))
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("header .main-nav")).hasSize(1);
                    assertThat(document.select("footer .site-footer")).hasSize(1);
                    assertThat(document.select(".footer-operations .footer-operator-name").text())
                            .isEqualTo("운영자테스트");
                    assertThat(document.select(".footer-operations .footer-contact-email")).isEmpty();
                    assertThat(document.select(".footer-operations > span")).hasSize(1);
                    assertThat(document.select(".random-travel-page h1").text())
                            .isEqualTo("어디로 떠나볼까요?");
                    assertThat(document.select(".random-travel-subtitle").text())
                            .isEqualTo("고민은 잠깐 내려놓고, Tripbora가 여행지를 골라드릴게요.");
                    assertThat(document.select("#random-scope-group [data-random-scope]")).hasSize(2);
                    assertThat(document.select("#random-draw-button").text()).contains("여행지 뽑기");
                    assertThat(document.select("#random-status[aria-live=polite]")).hasSize(1);
                    assertThat(document.select("#random-stage, #random-result")).hasSize(2);
                    assertThat(document.select("link[href='/css/random-travel.css']")).hasSize(1);
                    assertThat(document.select("script[src='/js/random-travel.js']")).hasSize(1);
                });
    }

    @Test
    void sharedNavigationPlacesRandomTravelUnderTheNewTravelRecordMenu() throws Exception {
        mockMvc.perform(get("/random-travel"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".main-menu > .menu-item > a").eachText())
                            .containsExactlyElementsOf(List.of(
                                    "국내", "해외", "여행코스", "여행 커뮤니티", "여행정보",
                                    "여행기록", "고객센터", "이벤트"));
                    assertThat(document.select(".submenu-grid > .submenu-col")).hasSize(8);
                    assertThat(document.select(
                            ".submenu-grid > .submenu-col:nth-child(6) a[href='/random-travel']"))
                            .hasSize(1);
                    assertThat(document.select(
                            ".submenu-grid > .submenu-col:nth-child(1) a[href='/random-travel'], "
                                    + ".submenu-grid > .submenu-col:nth-child(2) a[href='/random-travel']"))
                            .isEmpty();
                });
    }

    @Test
    void englishRandomTravelRendersFixedUiAndJavascriptMessagesWithoutKoreanFallback()
            throws Exception {
        mockMvc.perform(get("/random-travel")
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, "en")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".random-travel-page h1").text())
                            .isEqualTo("Where should we go?");
                    assertThat(document.select("[data-random-scope]").eachText())
                            .containsExactly("Domestic", "International");
                    assertThat(document.select("#random-draw-button").text())
                            .contains("Pick a destination");
                    assertThat(document.selectFirst("#random-travel-i18n")
                            .attr("data-card-details")).isEqualTo("View details");
                    assertThat(document.selectFirst("#random-travel-i18n")
                            .attr("data-error-title"))
                            .isEqualTo("We couldn't load destinations.");
                    assertThat(result.getResponse().getContentAsString())
                            .doesNotContain("??random.travel.");
                });
    }
}
