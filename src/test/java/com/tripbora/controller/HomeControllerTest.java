package com.tripbora.controller;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.SupportedLanguage;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.HomeFestivalDto;
import com.tripbora.dto.RecommendDestinationDto;
import com.tripbora.dto.SeasonDestinationDto;
import com.tripbora.dto.TravelInfoListItemDto;
import com.tripbora.model.Event;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.User;
import com.tripbora.model.UserRole;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.security.CustomUserDetails;
import com.tripbora.service.event.EventLocalizationService;
import com.tripbora.service.event.EventService;
import com.tripbora.service.travelinfo.FestivalDetailService;
import com.tripbora.service.travelinfo.TravelInfoService;
import com.tripbora.service.destination.DestinationImageService;
import com.tripbora.service.destination.DestinationViewClock;
import com.tripbora.service.file.DestinationCardThumbnailService;
import com.tripbora.service.recommend.DestinationRecommendService;
import com.tripbora.service.recommend.PopularRecommendService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import jakarta.servlet.http.Cookie;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(value = HomeController.class,
        properties = {"app.contact-email=contact@tripbora.test",
                "seo.site-base-url=https://tripbora.com"})
@Import({SecurityConfig.class, I18nConfig.class})
class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FestivalDetailService festivalDetailService;
    @MockitoBean
    private DestinationRecommendService recommendService;
    @MockitoBean
    private PopularRecommendService popularRecommendService;
    @MockitoBean
    private DestinationViewClock viewClock;
    @MockitoBean
    private DestinationCardThumbnailService cardThumbnailService;
    @MockitoBean
    private DestinationImageService destinationImageService;
    @MockitoBean
    private TravelInfoService travelInfoService;
    @MockitoBean
    private EventService eventService;
    @MockitoBean
    private EventLocalizationService eventLocalizationService;
    @MockitoBean
    private UserMapper userMapper;
    @MockitoBean
    private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean
    private CustomLogoutSuccessHandler customLogoutSuccessHandler;

    @Test
    void guestHomeRendersFestivalsAndExistingMainSections() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 2);
        when(viewClock.today()).thenReturn(today);
        HomeFestivalDto festival = festival(12L, "서울 가을 문화 축제", "ongoing");
        when(festivalDetailService.getHomeFestivals(today, SupportedLanguage.KOREAN))
                .thenReturn(List.of(festival));
        RecommendDestinationDto featured = popular(15L, "경복궁", "/uploads/destinations/palace.jpg", "종로구");
        // 썸네일 서비스가 채워 두는 값 (여기서는 서비스가 mock 이라 미리 넣어 둔다)
        featured.setCardImageUrl("/destination-thumbnails/v1/480/palace.jpg");
        featured.setCardImageSrcset("/destination-thumbnails/v1/480/palace.jpg 480w, "
                + "/destination-thumbnails/v1/960/palace.jpg 960w");
        List<RecommendDestinationDto> popularDestinations = List.of(featured,
                popular(21L, "해운대해수욕장", "/uploads/destinations/haeundae.jpg", "해운대구"),
                popular(22L, "성산일출봉", "/uploads/destinations/seongsan.jpg", "서귀포시"),
                popular(23L, "전주한옥마을", "/uploads/destinations/jeonju.jpg", "전주시"),
                popular(24L, "경주 불국사", "/uploads/destinations/bulguksa.jpg", "경주시"));
        when(popularRecommendService.findDomesticPopular(5, SupportedLanguage.KOREAN))
                .thenReturn(popularDestinations);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attribute("isLoggedIn", false))
                .andExpect(model().attribute("homeFestivals", List.of(festival)))
                .andExpect(model().attributeDoesNotExist("popularCourses"))
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".footer-operations .footer-operator-name")).isEmpty();
                    assertThat(document.select(".footer-operations .footer-contact-email").text())
                            .isEqualTo("contact@tripbora.test");
                    // 메인 추천·메인 노출 이벤트가 없으면 Hero 와 프로모션 배너를 그리지 않는다. (임의 fallback 없음)
                    assertThat(document.select("#home-hero, #home-promotion, #event-slider")).isEmpty();
                    // 서비스 소개는 메인 본문 블록이 아니라 헤더 아이콘과 ☰ 메뉴 판의 글자 메뉴로 들어간다.
                    assertThat(document.select(".home-service-teaser")).isEmpty();
                    assertThat(document.selectFirst(".search-box a.header-about-link").attr("href"))
                            .isEqualTo("/about");
                    assertThat(document.selectFirst(".header-about-link").attr("aria-label"))
                            .isEqualTo("TripBora 소개");
                    assertThat(document.select("#site-menu a.site-menu-about[href='/about']").text())
                            .isEqualTo("TripBora 소개");
                    // 메인 순서: Hero → 지금 뜨는 여행지 → 이벤트 → 계절 추천 → 랜드마크 → 축제.
                    // 비어 있는 영역(Hero·이벤트·랜드마크)은 그리지 않고, 남은 영역은 같은 차례를 지킨다.
                    assertThat(document.select(".home-converge")).isEmpty();
                    assertThat(document.select(".home-page > section").eachAttr("class"))
                            .containsExactly("popular-recommend", "seasonal-recommend", "home-festival-section");
                    assertThat(document.select(".seasonal-recommend")).hasSize(1);
                    assertThat(document.select(".popular-recommend")).hasSize(1);
                    // 인기 여행지: 배지·필터 없이 제목·설명·전체보기, 그 아래 1 Large + 4 Small 편집 격자.
                    // 머리글과 격자가 한 본문 폭 상자 안에 있다.
                    assertThat(document.select(".popular-recommend .recommend-badge")).isEmpty();
                    assertThat(document.select("#popular-tag-list, .recommend-filter, "
                            + ".recommend-scope-tab, .recommend-theme-tab")).isEmpty();
                    assertThat(document.select("#popular-recommend-title").text()).isEqualTo("인기 여행지");
                    assertThat(document.selectFirst("#popular-view-all").attr("href"))
                            .isEqualTo("/destinations?type=domestic&sort=views");
                    assertThat(document.select(".popular-recommend-inner > .recommend-header, "
                            + ".popular-recommend-inner > ul.recommend-editorial")).hasSize(2);
                    assertThat(document.select(".recommend-editorial.is-count-5")).hasSize(1);
                    assertThat(document.select(".recommend-editorial > li.recommend-featured")).hasSize(1);
                    assertThat(document.select(".recommend-editorial > li.recommend-item")).hasSize(4);
                    assertThat(document.selectFirst(".recommend-editorial > li").hasClass("recommend-featured"))
                            .isTrue();
                    // 카드 전체가 상세 링크이고, 사진 alt 는 (다국어 처리된) 여행지명이다.
                    assertThat(document.select(".recommend-editorial a.recommend-card").eachAttr("href"))
                            .containsExactly("/destinations/15", "/destinations/21", "/destinations/22",
                                    "/destinations/23", "/destinations/24");
                    assertThat(document.select(".recommend-featured .recommend-card-name").text())
                            .isEqualTo("경복궁");
                    assertThat(document.select(".recommend-featured .recommend-card-region").text())
                            .isEqualTo("종로구");
                    var popularImages = document.select(".recommend-card-media img");
                    assertThat(popularImages.eachAttr("alt")).containsExactly(
                            "경복궁", "해운대해수욕장", "성산일출봉", "전주한옥마을", "경주 불국사");
                    assertThat(popularImages.get(0).attr("src"))
                            .isEqualTo("/destination-thumbnails/v1/480/palace.jpg");
                    assertThat(popularImages.get(0).attr("sizes")).endsWith("540px");
                    assertThat(popularImages.get(0).attr("data-original-src"))
                            .isEqualTo("/uploads/destinations/palace.jpg");
                    assertThat(popularImages.get(1).attr("src")).isEqualTo("/uploads/destinations/haeundae.jpg");
                    assertThat(popularImages.get(1).hasAttr("srcset")).isFalse();
                    assertThat(document.select("a.home-festival-card[href='/festivals/12']")).hasSize(1);
                    assertThat(document.select(".home-festival-card").text())
                            .contains("서울 가을 문화 축제", "진행중", "서울 종로구", "2026.10.01 - 10.12");
                    assertThat(document.select(".home-festival-all").attr("href"))
                            .isEqualTo("/travel-info?contentType=FESTIVAL");
                    // 위의 TripBora 이벤트 배너와 겹쳐 보이지 않게 지역 축제 영역임을 eyebrow 로 구분한다.
                    assertThat(document.select(".home-festival-eyebrow").text()).isEqualTo("LOCAL FESTIVALS");
                    assertThat(document.select(".popular-course-section")).isEmpty();
                    assertThat(document.select(".instant-trip, #roulette-canvas")).isEmpty();
                });

        verify(festivalDetailService).getHomeFestivals(today, SupportedLanguage.KOREAN);
        verify(cardThumbnailService).applyCardImages(eq(popularDestinations), any(), any());
    }

    @Test
    void popularDestinationsDrawOnlyWhatExistsAndHideWhenEmpty() throws Exception {
        // 한 곳도 없으면 섹션 자체를 그리지 않는다. (빈 카드·안내 문구 없음)
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".popular-recommend")).isEmpty();
                    assertThat(document.selectFirst(".seasonal-recommend").nextElementSibling()).isNull();
                });

        // 한 곳이면 대표 칸만 그리고 보조 칸은 만들지 않는다. 대표 사진이 없어도 화면이 깨지지 않는다.
        when(popularRecommendService.findDomesticPopular(5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(popular(15L, "경복궁", null, "종로구")));
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".recommend-editorial.is-count-1")).hasSize(1);
                    assertThat(document.select(".recommend-editorial > li")).hasSize(1);
                    assertThat(document.select(".recommend-featured a.recommend-card").attr("href"))
                            .isEqualTo("/destinations/15");
                    assertThat(document.select(".recommend-item")).isEmpty();
                });
    }

    @Test
    void fiveRecentlyViewedDestinationsTurnTheSectionIntoTrendingWithoutAskingForPopular() throws Exception {
        LocalDate today = LocalDate.of(2026, 9, 30);
        when(viewClock.today()).thenReturn(today);
        List<SeasonDestinationDto> trending = List.of(
                landmark(15L, "경복궁", "/uploads/destinations/palace.jpg", "서울", "종로구"),
                landmark(21L, "공산성", "/uploads/destinations/gongsan.jpg", "충남", "공주시"),
                landmark(31L, "센소지", "/uploads/destinations/sensoji.jpg", "일본", "도쿄"),
                landmark(32L, "빅벤", "/uploads/destinations/bigben.jpg", "영국", "런던"),
                landmark(40L, "성산일출봉", "/uploads/destinations/seongsan.jpg", null, "제주"));
        when(recommendService.findTrendingDestinations(today, 5, SupportedLanguage.KOREAN))
                .thenReturn(trending);

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("popularTrending", true))
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("#popular-recommend-title").text()).isEqualTo("지금 뜨는 여행지");
                    assertThat(document.select(".popular-recommend .recommend-sub").text())
                            .isEqualTo("최근 여행자들이 많이 찾아본 여행지를 만나보세요.");
                    // 같은 1 Large + 4 Small 격자에 최근 7일 순서 그대로 그린다. (순위·배지 없음)
                    assertThat(document.select(".recommend-editorial.is-count-5")).hasSize(1);
                    assertThat(document.select(".recommend-editorial a.recommend-card").eachAttr("href"))
                            .containsExactly("/destinations/15", "/destinations/21", "/destinations/31",
                                    "/destinations/32", "/destinations/40");
                    assertThat(document.select(".recommend-card-region").eachText())
                            .containsExactly("서울 종로구", "충남 공주시", "일본 도쿄", "영국 런던", "제주");
                });

        verify(popularRecommendService, never()).findDomesticPopular(anyInt(), any());
        verify(cardThumbnailService).applyCardImages(eq(trending), any(), any());
    }

    @Test
    void fewerThanFiveRecentDestinationsFallBackToPopularWithoutMixing() throws Exception {
        LocalDate today = LocalDate.of(2026, 9, 30);
        when(viewClock.today()).thenReturn(today);
        when(recommendService.findTrendingDestinations(today, 5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(
                        landmark(91L, "최근1", "/uploads/r1.jpg", "서울", "중구"),
                        landmark(92L, "최근2", "/uploads/r2.jpg", "서울", "중구"),
                        landmark(93L, "최근3", "/uploads/r3.jpg", "서울", "중구"),
                        landmark(94L, "최근4", "/uploads/r4.jpg", "서울", "중구")));
        when(popularRecommendService.findDomesticPopular(5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(
                        popular(15L, "경복궁", "/uploads/destinations/palace.jpg", "종로구"),
                        popular(21L, "해운대해수욕장", "/uploads/destinations/haeundae.jpg", "해운대구")));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("popularTrending", false))
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select("#popular-recommend-title").text()).isEqualTo("인기 여행지");
                    assertThat(document.select(".popular-recommend .recommend-sub").text())
                            .isEqualTo("지금 사람들이 많이 찾는 여행지를 둘러보세요.");
                    // 기존 인기 여행지만 그린다. 최근 조회 여행지로 빈 칸을 채우지 않는다.
                    assertThat(document.select(".recommend-editorial a.recommend-card").eachAttr("href"))
                            .containsExactly("/destinations/15", "/destinations/21");
                    assertThat(document.select(".recommend-editorial").text()).doesNotContain("최근1", "최근4");
                    assertThat(document.select(".recommend-card-region").eachText())
                            .containsExactly("종로구", "해운대구");
                });
    }

    private RecommendDestinationDto popular(Long id, String name, String imageUrl, String regionName) {
        RecommendDestinationDto destination = new RecommendDestinationDto();
        destination.setId(id);
        destination.setName(name);
        destination.setImageUrl(imageUrl);
        destination.setRegionName(regionName);
        return destination;
    }

    @Test
    void landmarkCardsLinkToDestinationsWithParentAndRegionNames() throws Exception {
        SeasonDestinationDto bigBen = landmark(31L, "빅벤",
                "https://upload.wikimedia.org/big-ben.jpg", "영국", "런던");
        SeasonDestinationDto palace = landmark(15L, "경복궁",
                "/uploads/destinations/palace.jpg", "서울", "종로구");
        // 썸네일 서비스가 채워 두는 값 (여기서는 서비스가 mock 이라 미리 넣어 둔다)
        palace.setCardImageUrl("/destination-thumbnails/v1/480/palace.jpg");
        palace.setCardImageSrcset("/destination-thumbnails/v1/480/palace.jpg 480w, "
                + "/destination-thumbnails/v1/960/palace.jpg 960w");
        palace.setCardImageCoverScale(1.78);
        palace.setCardPositionX(25);
        palace.setCardPositionY(15);
        SeasonDestinationDto jeju = landmark(40L, "성산일출봉",
                "/uploads/destinations/seongsan.jpg", null, "제주");
        List<SeasonDestinationDto> landmarks = List.of(bigBen, palace, jeju);
        when(recommendService.findHomeLandmarks(SupportedLanguage.KOREAN)).thenReturn(landmarks);

        mockMvc.perform(get("/").header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.selectFirst(".seasonal-recommend").nextElementSibling()
                            .hasClass("home-converge")).isTrue();
                    // 인기 여행지와 축제가 없으면 랜드마크가 마지막 섹션이다.
                    assertThat(document.selectFirst(".home-converge").nextElementSibling()).isNull();
                    assertThat(document.select("#home-converge-title").text()).isEqualTo("세계의 랜드마크");
                    assertThat(document.select(".home-converge-eyebrow").text()).isEqualTo("Landmarks");

                    // 있는 만큼만 그린다. 스크롤 효과가 옮기는 li 안에 링크가 있다.
                    assertThat(document.select("[data-home-converge] > li.home-converge-card")).hasSize(3);
                    // 순환 복제본이 앞뒤에 붙어도 원본 첫·마지막 카드를 알 수 있게 서버가 표시한다.
                    assertThat(document.select(".home-converge-card.is-first h3").text()).isEqualTo("빅벤");
                    assertThat(document.select(".home-converge-card.is-last h3").text()).isEqualTo("성산일출봉");
                    assertThat(document.select(".home-converge-card.is-clone")).isEmpty();
                    assertThat(document.select(".home-converge-card > a.home-converge-link").eachAttr("href"))
                            .containsExactly("/destinations/31", "/destinations/15", "/destinations/40");
                    assertThat(document.select(".home-converge-card h3").eachText())
                            .containsExactly("빅벤", "경복궁", "성산일출봉");
                    assertThat(document.select(".home-converge-card p").eachText())
                            .containsExactly("영국 런던", "서울 종로구", "제주");

                    var images = document.select(".home-converge-photo img");
                    assertThat(images.get(0).attr("src")).isEqualTo("https://upload.wikimedia.org/big-ben.jpg");
                    assertThat(images.get(0).hasAttr("srcset")).isFalse();
                    assertThat(images.get(1).attr("src")).isEqualTo("/destination-thumbnails/v1/480/palace.jpg");
                    assertThat(images.get(1).attr("srcset")).contains("960w");
                    assertThat(images.get(1).attr("sizes")).contains("calc(170px * 1.78)");
                    assertThat(images.get(1).attr("style")).contains("--card-position-x: 25%", "--card-position-y: 15%");
                    assertThat(images.get(1).attr("data-original-src")).isEqualTo("/uploads/destinations/palace.jpg");
                    assertThat(images.get(2).attr("src")).isEqualTo("/uploads/destinations/seongsan.jpg");
                    var resources = document.select("link[href*='home-converge-prototype'], script[src*='card-thumbnail']");
                    assertThat(resources).hasSize(2);
                    for (var resource : resources) {
                        String url = resource.hasAttr("href") ? resource.attr("href") : resource.attr("src");
                        assertThat(url).matches("/(css|js)/.+-[0-9a-f]{32}\\.(css|js)");
                        var response = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
                        assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
                    }

                    // 수동 레일의 이전/다음 버튼: button + aria-label, 넘길 카드가 있을 때만 JS 가 보여 준다.
                    var rail = document.selectFirst(".home-converge-rail");
                    assertThat(rail.selectFirst("#home-converge-list[data-home-converge]")).isNotNull();
                    var navButtons = rail.select("button.home-converge-nav");
                    assertThat(navButtons.eachAttr("aria-label"))
                            .containsExactly("이전 랜드마크", "다음 랜드마크");
                    assertThat(navButtons.eachAttr("aria-controls"))
                            .containsExactly("home-converge-list", "home-converge-list");
                    assertThat(navButtons).allMatch(button -> button.hasAttr("hidden")
                            && "button".equals(button.attr("type")));
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/destination-ui"));
                    java.nio.file.Files.writeString(java.nio.file.Path.of("build/reports/destination-ui/home.html"),
                            result.getResponse().getContentAsString());
                });

        verify(cardThumbnailService).applyCardImages(eq(landmarks), any(), any());
    }

    private SeasonDestinationDto landmark(Long id, String name, String imageUrl,
                                          String parentRegionName, String regionName) {
        SeasonDestinationDto destination = new SeasonDestinationDto();
        destination.setId(id);
        destination.setName(name);
        destination.setImageUrl(imageUrl);
        destination.setParentRegionName(parentRegionName);
        destination.setRegionName(regionName);
        return destination;
    }

    @Test
    void withdrawalQueryRendersAnAccessibleToastWithoutAddingAHomeLayoutBanner()
            throws Exception {

        mockMvc.perform(get("/").queryParam("withdrawn", "true"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(
                            ".home-withdrawal-toast[role=status][aria-live=polite]"))
                            .hasSize(1);
                    assertThat(document.select(".home-withdrawal-toast").text())
                            .isEqualTo("회원탈퇴 신청이 완료되었습니다. 계정은 30일 동안 보존됩니다.");
                    assertThat(document.select(".home-account-status")).isEmpty();
                    assertThat(document.select("script[src*=home-withdrawal-toast]").eachAttr("src"))
                            .singleElement().asString()
                            .matches("/js/home-withdrawal-toast(?:-[0-9a-f]{32})?\\.js");
                });
    }

    /** 탈퇴 완료 안내는 5개 언어 모두 messages 번들에서 나온다. */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko    | 회원탈퇴 신청이 완료되었습니다. 계정은 30일 동안 보존됩니다.",
            "en    | Your account deletion request has been received. Your account is kept for 30 days.",
            "ja    | 退会申請が完了しました。アカウントは30日間保存されます。",
            "zh-CN | 注销申请已提交，账号将保留 30 天。",
            "zh-TW | 註銷申請已送出，帳號將保留 30 天。"
    })
    void withdrawalToastFollowsTheCurrentLanguage(String languageTag, String expected)
            throws Exception {

        mockMvc.perform(get("/")
                        .queryParam("withdrawn", "true")
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag)))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    assertThat(Jsoup.parse(html).select(".home-withdrawal-toast").text())
                            .isEqualTo(expected);
                    assertThat(html).doesNotContain("??");
                });
    }

    /** withdrawn=true 가 아니면 어떤 언어에서도 안내가 뜨지 않는다. */
    @ParameterizedTest
    @CsvSource({"false", "TRUE", "1", "yes"})
    void onlyTheExactWithdrawnTrueValueRendersTheToast(String value) throws Exception {

        mockMvc.perform(get("/").queryParam("withdrawn", value))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(Jsoup.parse(
                        result.getResponse().getContentAsString())
                        .select(".home-withdrawal-toast")).isEmpty());
    }

    @Test
    void regularHomeDoesNotRenderTheWithdrawalToast() throws Exception {

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(Jsoup.parse(
                        result.getResponse().getContentAsString())
                        .select(".home-withdrawal-toast")).isEmpty());
    }

    @Test
    void homeRendersCanonicalSeoHeadAndOnePageHeading() throws Exception {

        mockMvc.perform(get("/").queryParam("withdrawn", "true"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.title()).isEqualTo("TripBora | 여행을 보라, 추억을 남겨라");
                    assertThat(document.selectFirst("meta[name=description]").attr("content"))
                            .startsWith("트립보라에서 ")
                            .contains("여행을 보라, 추억을 남겨라")
                            .doesNotContain("TripBora(트립보라)");
                    assertThat(document.selectFirst("link[rel=canonical]").attr("href"))
                            .isEqualTo("https://tripbora.com/");
                    assertThat(document.selectFirst("meta[name=robots]").attr("content"))
                            .isEqualTo("index, follow");
                    assertThat(document.selectFirst("meta[property=og:title]").attr("content"))
                            .isEqualTo(document.title());
                    assertThat(document.selectFirst("meta[property=og:url]").attr("content"))
                            .isEqualTo("https://tripbora.com/");
                    assertThat(document.select("meta[property=og:site_name]")).hasSize(1);
                    assertThat(document.selectFirst("meta[property=og:site_name]").attr("content"))
                            .isEqualTo("트립보라");
                    assertThat(document.selectFirst("meta[property=og:description]").attr("content"))
                            .contains("여행을 보라, 추억을 남겨라");
                    assertThat(document.selectFirst("meta[name=twitter:card]").attr("content"))
                            .isEqualTo("summary_large_image");
                    assertThat(document.selectFirst("meta[name=twitter:title]").attr("content"))
                            .isEqualTo(document.title());
                    assertThat(document.selectFirst("meta[name=twitter:image]").attr("content"))
                            .isEqualTo("https://tripbora.com/images/branding/tripbora-og.png");
                    assertThat(document.select("link[rel=alternate][hreflang]")).isEmpty();
                    assertThat(document.select(".home-page > h1")).hasSize(1);
                    assertThat(document.select(".home-page > h1").text())
                            .isEqualTo("트립보라 | 여행을 보라, 추억을 남겨라");
                    assertThat(document.select(".footer-description").text())
                            .isEqualTo("여행을 보라, 추억을 남겨라");
                    var website = new ObjectMapper().readTree(document
                            .selectFirst("script[type=application/ld+json]").data());
                    assertThat(website.path("@type").asText()).isEqualTo("WebSite");
                    assertThat(website.path("name").asText()).isEqualTo("트립보라");
                    assertThat(website.path("alternateName").asText()).isEqualTo("TripBora");
                    assertThat(document.select("script[type=application/ld+json]").stream()
                            .filter(script -> script.data().contains("\"WebSite\""))
                            .count()).isEqualTo(1);
                    assertThat(website.path("url").asText()).isEqualTo("https://tripbora.com/");
                });
    }

    @Test
    void guestAboutIsPublicAndRendersIndexedSeoMetadata() throws Exception {
        mockMvc.perform(get("/about"))
                .andExpect(status().isOk())
                .andExpect(view().name("about"))
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.title()).isEqualTo("트립보라 소개 | TripBora");
                    assertThat(document.selectFirst("meta[name=description]").attr("content"))
                            .contains("여행지", "여행 계획", "여행 기록");
                    assertThat(document.selectFirst("link[rel=canonical]").attr("href"))
                            .isEqualTo("https://tripbora.com/about");
                    assertThat(document.selectFirst("meta[name=robots]").attr("content"))
                            .isEqualTo("index, follow");
                    assertThat(document.selectFirst("meta[property=og:url]").attr("content"))
                            .isEqualTo("https://tripbora.com/about");
                    assertThat(document.select("main .about-page h1")).hasSize(1);
                    assertThat(document.select(".about-feature")).hasSize(7);
                    assertThat(document.select(".about-feature .about-feature-link").eachAttr("href"))
                            .containsExactly(
                                    "/destinations", "/travel-info", "/travel-info?contentType=FESTIVAL",
                                    "/courses", "/diaries/demo", "/travel-plans", "/board/list");
                });
    }

    /**
     * 홈은 로그인 여부만 알면 된다. 회원 한 줄을 통째로 읽어 화면으로 넘기지 않는다.
     * (헤더가 쓰는 프로필 사진은 GlobalModelAttributes 가 따로 최소 조회로 넣어 준다)
     */
    @Test
    void authenticatedHomeDoesNotLoadTheWholeMemberRow() throws Exception {
        User user = user(7L);

        mockMvc.perform(get("/").with(authentication(authenticationFor(user))))
                .andExpect(status().isOk())
                .andExpect(view().name("home"))
                .andExpect(model().attribute("isLoggedIn", true))
                .andExpect(model().attributeDoesNotExist("user"));

        verify(userMapper, never()).findById(7L);
        verify(userMapper, never()).hasLocalPasswordById(7L);
    }

    @Test
    void englishHomeTranslatesFixedUiWhileKeepingDatabaseContentUnchanged() throws Exception {
        HomeFestivalDto festival = festival(12L, "Seoul Autumn Festival", "upcoming");
        when(festivalDetailService.getHomeFestivals(any(), eq(SupportedLanguage.ENGLISH)))
                .thenReturn(List.of(festival));
        when(popularRecommendService.findDomesticPopular(5, SupportedLanguage.ENGLISH))
                .thenReturn(List.of(popular(15L, "Gyeongbokgung Palace", "/uploads/destinations/palace.jpg",
                        "Jongno-gu")));
        when(travelInfoService.getHomeHeroItems(5, SupportedLanguage.ENGLISH))
                .thenReturn(List.of(heroItem(10L, "Seoul Palace Walk", "Seasonal travel")));

        mockMvc.perform(get("/")
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, "en")))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    assertThat(document.select(".recommend-header").text())
                            .contains("Popular Destinations", "View all");
                    assertThat(document.select(".home-festival-header").text())
                            .contains("Festivals & Events to Visit Now", "View all");
                    assertThat(document.select(".home-festival-card").text())
                            .contains("Seoul Autumn Festival", "Upcoming");
                    assertThat(document.selectFirst("#home-i18n").attr("data-spring-title"))
                            .isEqualTo("Spring: Great Places to Go Now");
                    // Hero 의 고정 문구도 요청 언어를 따르고, 제목·카테고리는 서비스가 바꿔 둔 값 그대로다.
                    var hero = document.selectFirst("#home-hero");
                    assertThat(hero.attr("aria-label")).isEqualTo("Featured travel guides");
                    assertThat(hero.attr("data-label-play")).isEqualTo("Start autoplay");
                    assertThat(hero.select(".slide-text").text())
                            .isEqualTo("Seasonal travel Seoul Palace Walk View details →");
                });
    }

    @Test
    void heroRendersFeaturedTravelInfoOnTheServerInTheGivenOrder() throws Exception {
        when(travelInfoService.getHomeHeroItems(5, SupportedLanguage.KOREAN)).thenReturn(List.of(
                heroItem(10L, "서울 고궁 체험", "여행추천"),
                heroItem(11L, "일본 온천 여행 가이드", "준비물")));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    var document = Jsoup.parse(result.getResponse().getContentAsString());
                    var hero = document.selectFirst("#home-hero");
                    assertThat(hero).isNotNull();
                    // Hero 는 본문의 첫 영역이다. 이 경우 지금 뜨는 여행지·이벤트가 비어 있어 다음이 계절 추천이다.
                    assertThat(document.selectFirst(".home-page > section")).isSameAs(hero);
                    assertThat(hero.nextElementSibling().hasClass("seasonal-recommend")).isTrue();
                    assertThat(hero.attr("aria-label")).isEqualTo("추천 여행정보");

                    var slides = hero.select(".swiper-wrapper > .swiper-slide");
                    assertThat(slides).hasSize(2);
                    assertThat(slides.select(".badge").eachText()).containsExactly("여행추천", "준비물");
                    assertThat(slides.select("h2.title").eachText())
                            .containsExactly("서울 고궁 체험", "일본 온천 여행 가이드");
                    assertThat(slides.select("a.more").eachAttr("href"))
                            .containsExactly("/travel-info/10", "/travel-info/11");
                    assertThat(slides.select("a.more").eachText()).containsOnly("자세히 보기 →");
                    // 이미지 링크는 글 링크와 같은 곳으로 가지만 읽기·탭 순서에서는 빠진다.
                    assertThat(slides.select("a.slide-img").eachAttr("href"))
                            .containsExactly("/travel-info/10", "/travel-info/11");
                    assertThat(slides.select("a.slide-img").eachAttr("tabindex")).containsOnly("-1");
                    assertThat(slides.select("a.slide-img").eachAttr("aria-hidden")).containsOnly("true");
                    // 첫 이미지는 바로 받고 나머지는 미룬다.
                    var images = slides.select(".slide-img img");
                    assertThat(images.eachAttr("src"))
                            .containsExactly("/uploads/travel-info/thumbnails/10.jpg",
                                    "/uploads/travel-info/thumbnails/11.jpg");
                    assertThat(images.get(0).attr("fetchpriority")).isEqualTo("high");
                    assertThat(images.get(0).attr("loading")).isEqualTo("eager");
                    assertThat(images.get(1).hasAttr("fetchpriority")).isFalse();
                    assertThat(images.get(1).attr("loading")).isEqualTo("lazy");
                    // 이벤트 시절의 고정 문구·링크가 남지 않는다.
                    assertThat(hero.text()).doesNotContain("EVENT", "이벤트");
                    assertThat(hero.select("a[href^=/events/]")).isEmpty();

                    // 두 장 이상이라 컨트롤을 그린다. 버튼마다 이름이 있고 카운터 전체 수는 서버가 채운다.
                    assertThat(hero.select(".slider-ui .slider-counter").text()).isEqualTo("01 / 02");
                    assertThat(hero.select(".slider-ui .prev").attr("aria-label")).isEqualTo("이전 추천 여행정보");
                    assertThat(hero.select(".slider-ui .next").attr("aria-label")).isEqualTo("다음 추천 여행정보");
                    assertThat(hero.select(".slider-ui .pause").attr("aria-label")).isEqualTo("자동 재생 일시정지");
                    assertThat(hero.select(".slider-ui .pause").attr("aria-pressed")).isEqualTo("false");
                    assertThat(hero.select(".slider-ui .progress .progress-bar")).hasSize(1);
                    // 진행선·카운터는 영역 안 class 로 찾는다. 문서 전체 id 로 두 슬라이더가 섞이지 않는다.
                    assertThat(document.select("#slide-area, #slide-index, #slide-total, #progress-bar")).isEmpty();
                });

        verify(travelInfoService).getHomeHeroItems(5, SupportedLanguage.KOREAN);
    }

    @Test
    void singleHeroItemRendersWithoutCarouselControls() throws Exception {
        when(travelInfoService.getHomeHeroItems(5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(heroItem(10L, "단양 8경", null)));

        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(result -> {
            var hero = Jsoup.parse(result.getResponse().getContentAsString()).selectFirst("#home-hero");
            assertThat(hero.select(".swiper-slide")).hasSize(1);
            assertThat(hero.select(".slider-ui, .nav, .progress")).isEmpty();
            // 카테고리 이름이 비면 eyebrow 를 그리지 않는다.
            assertThat(hero.select(".badge")).isEmpty();
            assertThat(hero.select("h2.title").text()).isEqualTo("단양 8경");
        });
    }

    @Test
    void promotionBannerShowsLocalizedOngoingAndUpcomingEventsBetweenTrendingAndSeasonal()
            throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 3);
        when(viewClock.today()).thenReturn(today);
        when(popularRecommendService.findDomesticPopular(5, SupportedLanguage.KOREAN))
                .thenReturn(List.of(popular(15L, "경복궁", "/uploads/destinations/palace.jpg", "종로구")));
        when(festivalDetailService.getHomeFestivals(today, SupportedLanguage.KOREAN))
                .thenReturn(List.of(festival(12L, "서울 가을 문화 축제", "ongoing")));
        List<Event> stored = List.of(
                event(3L, "가을 여행 후기 이벤트", "<p>원문</p>", "/uploads/events/a.jpg"),
                event(4L, "겨울 사진 공모전", null, "/uploads/events/b.jpg"));
        when(eventService.getHomePromotionEvents(today, 5)).thenReturn(stored);
        // 언어 대체는 이벤트 공개 화면과 같은 서비스가 맡는다. 설명은 HTML 로 저장될 수 있다.
        when(eventLocalizationService.localizeAll(stored, SupportedLanguage.KOREAN)).thenReturn(List.of(
                event(3L, "가을 여행 후기 이벤트", "<p>후기를 남기면 <strong>여행 굿즈</strong>를 드려요.</p>",
                        "/uploads/events/a.jpg"),
                event(4L, "겨울 사진 공모전", null, "/uploads/events/b.jpg")));

        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(result -> {
            var document = Jsoup.parse(result.getResponse().getContentAsString());
            var promotion = document.selectFirst("#home-promotion");
            assertThat(promotion).isNotNull();
            // 지금 뜨는(인기) 여행지 다음, 계절 추천 앞이다.
            assertThat(promotion.previousElementSibling().hasClass("popular-recommend")).isTrue();
            assertThat(promotion.nextElementSibling().hasClass("seasonal-recommend")).isTrue();
            assertThat(document.select(".home-page > section").eachAttr("class"))
                    .containsExactly("popular-recommend", "home-promotion home-slider is-carousel",
                            "seasonal-recommend", "home-festival-section");
            assertThat(promotion.attr("aria-label")).isEqualTo("TripBora 이벤트");

            var banners = promotion.select(".swiper-slide .home-promotion-banner");
            assertThat(banners).hasSize(2);
            // eyebrow 는 배너 위에 영역당 하나다. 슬라이드마다 반복하지 않는다.
            assertThat(promotion.select(".home-promotion-eyebrow").eachText()).containsExactly("TRIPBORA EVENT");
            assertThat(banners.select(".home-promotion-eyebrow")).isEmpty();
            assertThat(banners.select(".home-promotion-title").eachText())
                    .containsExactly("가을 여행 후기 이벤트", "겨울 사진 공모전");
            // 메인 배너는 이미지 중심이라 설명을 그리지 않는다.
            assertThat(promotion.select(".home-promotion-description")).isEmpty();
            assertThat(promotion.text()).doesNotContain("여행 굿즈");
            assertThat(banners.select("a.home-promotion-more").eachAttr("href"))
                    .containsExactly("/events/3", "/events/4");
            assertThat(banners.select(".home-promotion-media img").eachAttr("src"))
                    .containsExactly("/uploads/events/a.jpg", "/uploads/events/b.jpg");
            assertThat(banners.select(".home-promotion-media").eachAttr("tabindex")).containsOnly("-1");

            // 여러 장이라 같은 자리에서 넘기는 작은 컨트롤이 있다. Hero 컨트롤과는 다른 영역이다.
            assertThat(promotion.select(".slider-ui .slider-counter").text()).isEqualTo("01 / 02");
            assertThat(promotion.select(".slider-ui .prev").attr("aria-label")).isEqualTo("이전 이벤트");
            assertThat(promotion.select(".slider-ui .next").attr("aria-label")).isEqualTo("다음 이벤트");
            assertThat(promotion.select(".slider-ui .pause")).hasSize(1);
        });

        // 오늘은 DB 시계가 아니라 애플리케이션 날짜(KST)다.
        verify(eventService).getHomePromotionEvents(today, 5);
    }

    @Test
    void singlePromotionEventIsOneBannerWithoutControlsAndNoneHidesTheSection() throws Exception {
        LocalDate today = LocalDate.of(2026, 10, 3);
        when(viewClock.today()).thenReturn(today);
        List<Event> one = List.of(event(3L, "가을 여행 후기 이벤트", null, "/uploads/events/a.jpg"));
        when(eventService.getHomePromotionEvents(today, 5)).thenReturn(one);
        when(eventLocalizationService.localizeAll(one, SupportedLanguage.KOREAN)).thenReturn(one);

        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(result -> {
            var promotion = Jsoup.parse(result.getResponse().getContentAsString())
                    .selectFirst("#home-promotion");
            assertThat(promotion.select(".home-promotion-banner")).hasSize(1);
            assertThat(promotion.select(".slider-ui, .nav")).isEmpty();
        });

        when(eventService.getHomePromotionEvents(today, 5)).thenReturn(List.of());
        when(eventLocalizationService.localizeAll(List.of(), SupportedLanguage.KOREAN)).thenReturn(List.of());
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(result ->
                assertThat(Jsoup.parse(result.getResponse().getContentAsString())
                        .select("#home-promotion")).isEmpty());
    }

    private TravelInfoListItemDto heroItem(Long id, String title, String categoryName) {
        TravelInfoListItemDto item = new TravelInfoListItemDto();
        item.setId(id);
        item.setTitle(title);
        item.setContentType(TravelInfoContentType.GENERAL);
        item.setCategoryId(3L);
        item.setCategoryName(categoryName);
        item.setThumbnailUrl("/uploads/travel-info/thumbnails/" + id + ".jpg");
        return item;
    }

    private Event event(Long id, String title, String description, String eventImg) {
        Event event = new Event();
        event.setId(id);
        event.setTitle(title);
        event.setDescription(description);
        event.setEventImg(eventImg);
        return event;
    }

    private HomeFestivalDto festival(Long id, String title, String eventStatus) {
        HomeFestivalDto festival = new HomeFestivalDto();
        festival.setId(id);
        festival.setTitle(title);
        festival.setEventStatus(eventStatus);
        festival.setThumbnailUrl(id == 14L ? null : id == 12L ? "/images/travel8.jpg" : "/images/travel9.jpg");
        festival.setLocation("서울 종로구");
        festival.setStartDate(LocalDate.of(2026, 10, 1));
        festival.setEndDate(LocalDate.of(2026, 10, 12));
        return festival;
    }

    @Test
    void noFestivalsHidesTheEntireSection() throws Exception {
        mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(result -> {
            var document = Jsoup.parse(result.getResponse().getContentAsString());
            assertThat(document.select(".home-festival-section, .popular-course-section")).isEmpty();
            exportHomeFixture("empty", result.getResponse().getContentAsString());
        });
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ko | 지금 가볼 만한 축제·행사 | 진행중 | 예정",
            "en | Festivals & Events to Visit Now | Ongoing | Upcoming",
            "ja | 今行きたい祭り・イベント | 開催中 | 開催予定",
            "zh-CN | 近期值得去的节庆与活动 | 进行中 | 即将开始",
            "zh-TW | 近期值得去的節慶與活動 | 進行中 | 即將開始"
    })
    void festivalSectionSupportsEveryLanguage(String languageTag, String title,
                                             String ongoing, String upcoming) throws Exception {
        var language = SupportedLanguage.fromLanguageTag(languageTag).orElseThrow();
        var festivals = List.of(festival(12L, "서울 가을 문화 축제", "ongoing"),
                festival(13L, "A Long Festival & Events Title Across Multiple Cities", "upcoming"),
                festival(14L, "지역 문화 행사", "upcoming"),
                festival(15L, "가을 축제", "upcoming"),
                festival(16L, "문화 축제", "upcoming"),
                festival(17L, "음식 축제", "upcoming"),
                festival(18L, "야간 행사", "upcoming"),
                festival(19L, "지역 축제", "upcoming"));
        when(festivalDetailService.getHomeFestivals(any(), eq(language))).thenReturn(festivals);
        when(popularRecommendService.findDomesticPopular(5, language)).thenReturn(List.of(
                popular(15L, "경복궁", "/images/default.png", "종로구"),
                popular(21L, "해운대", "/images/default.png", "해운대구"),
                popular(22L, "성산일출봉", "/images/default.png", "서귀포시"),
                popular(23L, "전주한옥마을", "/images/default.png", "전주시"),
                popular(24L, "불국사", "/images/default.png", "경주시")));
        when(recommendService.findHomeLandmarks(language)).thenReturn(List.of(
                landmark(1L, "경복궁", "/images/travel8.jpg", "서울", "종로구"),
                landmark(2L, "랜드마크", "/images/travel9.jpg", "도시", "지역"),
                landmark(3L, "세계의 명소", "/images/default.png", "도시", "지역")));
        mockMvc.perform(get("/").cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag)))
                .andExpect(status().isOk()).andExpect(result -> {
                    String html = result.getResponse().getContentAsString();
                    var document = Jsoup.parse(html);
                    assertThat(document.select("#home-festival-title").text()).isEqualTo(title);
                    assertThat(document.select(".home-festival-status").eachText())
                            .containsExactly(ongoing, upcoming, upcoming, upcoming, upcoming, upcoming, upcoming, upcoming);
                    assertThat(document.select(".home-festival-card").eachAttr("href"))
                            .containsExactly("/festivals/12", "/festivals/13", "/festivals/14", "/festivals/15",
                                    "/festivals/16", "/festivals/17", "/festivals/18", "/festivals/19");
                    assertThat(html).doesNotContain("??home.festival");
                    exportHomeFixture(languageTag, html);
                });
    }

    private void exportHomeFixture(String name, String html) throws java.io.IOException {
        String directory = System.getenv("HOME_BROWSER_FIXTURES");
        if (directory != null && !directory.isBlank()) {
            var path = java.nio.file.Path.of(directory);
            java.nio.file.Files.createDirectories(path);
            java.nio.file.Files.writeString(path.resolve(name + ".html"), html);
        }
    }

    private UsernamePasswordAuthenticationToken authenticationFor(User user) {
        CustomUserDetails userDetails = new CustomUserDetails(user);
        return new UsernamePasswordAuthenticationToken(
                userDetails, userDetails.getPassword(), userDetails.getAuthorities());
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        user.setUserPassword("encoded-password");
        user.setUserRole(UserRole.USER);
        return user;
    }
}
