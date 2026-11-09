package com.tripbora.config.i18n;

import com.tripbora.config.GlobalRequestControllerAdvice;
import com.tripbora.dto.DestinationDto;
import com.tripbora.model.CountryCategory;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LocaleStreamingRuntimeRegressionTest.TestApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                "logging.level.root=ERROR"
        })
class LocaleStreamingRuntimeRegressionTest {

    @LocalServerPort
    private int port;

    @Test
    void firstGuestRequestCompletesAfterRenderingLocalePostForms() throws Exception {
        HttpResponse<String> response = get(null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("class=\"locale-option-form\"")
                .contains("id=\"locale-runtime-render-complete\"")
                .contains("</html>");
    }

    @Test
    void embeddedRuntimeResolvesHeaderAndDestinationDetailMessagesForEverySupportedLocale()
            throws Exception {
        List<LocaleExpectation> expectations = List.of(
                new LocaleExpectation("ko", "국내", "로그인", "편의시설", "지도", "여행자 리뷰"),
                new LocaleExpectation("en", "Domestic", "Log in", "Amenities", "Map", "Traveler reviews"),
                new LocaleExpectation("ja", "国内", "ログイン", "設備・サービス", "地図", "旅行者のレビュー"),
                new LocaleExpectation("zh-CN", "国内", "登录", "便利设施", "地图", "旅行者点评"),
                new LocaleExpectation("zh-TW", "國內", "登入", "便利設施", "地圖", "旅客評論")
        );

        for (LocaleExpectation expectation : expectations) {
            HttpResponse<String> response = get(expectation.languageTag());

            assertThat(response.statusCode()).as(expectation.languageTag()).isEqualTo(200);
            assertThat(response.body())
                    .as(expectation.languageTag())
                    .contains("<html lang=\"" + expectation.languageTag() + "\"")
                    .contains(">" + expectation.domestic() + "</a>")
                    .contains(">" + expectation.login() + "</span>")
                    .contains(">" + expectation.amenities() + "</p>")
                    .contains(">" + expectation.map() + "</p>")
                    .contains(">" + expectation.photoReviews() + "</p>")
                    .doesNotContain("??nav.domestic_", "??auth.login_",
                            "??destination.detail.section.");
        }
    }

    @Test
    void embeddedRuntimeRendersLocalizedDestinationListFragmentsForEverySupportedLocale()
            throws Exception {
        List<DestinationListExpectation> expectations = List.of(
                new DestinationListExpectation("ko", "서울", "국내 여행지", "기본순", "지역:"),
                new DestinationListExpectation("en", "Seoul", "Domestic Destinations", "Default", "Area:"),
                new DestinationListExpectation("ja", "ソウル", "国内の旅行スポット", "標準", "地域："),
                new DestinationListExpectation("zh-CN", "首尔", "国内旅行目的地", "默认", "地区:"),
                new DestinationListExpectation("zh-TW", "首爾", "國內旅遊目的地", "預設", "地區：")
        );

        for (DestinationListExpectation expectation : expectations) {
            HttpResponse<String> response = get("/destination-list-probe", expectation.languageTag());

            assertThat(response.statusCode()).as(expectation.languageTag()).isEqualTo(200);
            assertThat(response.body())
                    .as(expectation.languageTag())
                    .contains(">" + expectation.region() + "</span>")
                    .contains(">" + expectation.title() + "</span>")
                    .contains(">" + expectation.defaultSort() + "</button>")
                    .contains("<p class=\"region\">" + expectation.region() + "</p>")
                    .doesNotContain(">" + expectation.regionLabel() + "</span>")
                    .contains(">Localized destination</h3>")
                    .contains(">Localized summary</p>")
                    .doesNotContain("??destination.list.");
        }
    }

    private HttpResponse<String> get(String languageTag) throws Exception {
        return get("/", languageTag);
    }

    private HttpResponse<String> get(String path, String languageTag) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .GET();
        if (languageTag != null) {
            request.header("Cookie", TripBoraLocaleResolver.COOKIE_NAME + "=" + languageTag);
        }

        try {
            return HttpClient.newHttpClient()
                    .send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            fail("guest HTML response ended before the chunked body completed", exception);
            throw exception;
        }
    }

    private record LocaleExpectation(
            String languageTag,
            String domestic,
            String login,
            String amenities,
            String map,
            String photoReviews) {
    }

    private record DestinationListExpectation(
            String languageTag,
            String region,
            String title,
            String defaultSort,
            String regionLabel) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            MybatisAutoConfiguration.class,
            MailSenderAutoConfiguration.class
    })
    @Import({
            I18nConfig.class,
            GlobalRequestControllerAdvice.class,
            LocaleRuntimeProbeController.class
    })
    static class TestApplication {

        @Bean
        SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .build();
        }
    }

    @Controller
    static class LocaleRuntimeProbeController {

        @GetMapping("/")
        String probe(Model model) {
            model.addAttribute("isLoggedIn", false);
            model.addAttribute("pageTitle", "Locale runtime probe");
            return "locale-runtime-probe";
        }

        @GetMapping("/destination-list-probe")
        String destinationListProbe(Model model, Locale locale) {
            SupportedLanguage language = SupportedLanguage.fromLocale(locale)
                    .orElse(SupportedLanguage.KOREAN);
            String regionName = switch (language) {
                case KOREAN -> "서울";
                case ENGLISH -> "Seoul";
                case JAPANESE -> "ソウル";
                case CHINESE_SIMPLIFIED -> "首尔";
                case CHINESE_TRADITIONAL -> "首爾";
            };
            CountryCategory city = new CountryCategory();
            city.setId(38L);
            city.setRegionName("서울");
            city.setDepth(3);
            city.setIconPath("/images/seoul.png");
            DestinationDto destination = new DestinationDto();
            destination.setId(15L);
            destination.setName("Localized destination");
            destination.setShortDescription("Localized summary");
            destination.setRegionName(regionName);

            model.addAttribute("cities", List.of(city));
            model.addAttribute("destinations", List.of(destination));
            model.addAttribute("subregions", List.of());
            model.addAttribute("selectedSubregionId", null);
            model.addAttribute("selectedCityId", null);
            model.addAttribute("selectedCityName", null);
            model.addAttribute("totalPages", 1);
            model.addAttribute("currentPage", 1);
            model.addAttribute("pageSize", 12);
            model.addAttribute("sort", "default");
            model.addAttribute("type", "domestic");
            model.addAttribute("regionDisplayNames", Map.of(38L, regionName));
            model.addAttribute("isLoggedIn", false);
            model.addAttribute("pageTitle", "Destination list runtime probe");
            return "destination/list";
        }
    }
}
