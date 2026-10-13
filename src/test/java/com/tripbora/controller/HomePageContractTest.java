package com.tripbora.controller;

import com.tripbora.controller.recommend.RandomRecommendController;
import com.tripbora.service.recommend.RouletteRegionController;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class HomePageContractTest {

    @Test
    void existingSliderSeasonAndPopularContractsRemain() throws IOException {
        String template = resource("/templates/home.html");
        String homeScript = resource("/static/js/home.js");
        String sliderScript = resource("/static/js/slider.js");
        String sliderCss = resource("/static/css/slider.css");
        var document = Jsoup.parse(template);

        // Hero 와 이벤트 프로모션 배너는 서버가 그린다. 각자 영역 안에 Swiper 와 컨트롤을 가진다.
        assertThat(document.select("#home-hero .swiper .swiper-wrapper .swiper-slide")).hasSize(1);
        assertThat(document.select("#home-hero .slider-ui .prev, #home-hero .slider-ui .pause, "
                + "#home-hero .slider-ui .next")).hasSize(3);
        assertThat(document.select("#home-hero .progress-bar")).hasSize(1);
        assertThat(document.select("#home-promotion .swiper .swiper-slide .home-promotion-banner")).hasSize(1);
        assertThat(document.select("#event-slider, #slide-area, #progress-bar")).isEmpty();
        assertThat(template)
                .contains("th:if=\"${!#lists.isEmpty(homeHeroItems)}\"")
                .contains("th:if=\"${#lists.size(homeHeroItems) > 1}\"")
                .contains("th:if=\"${!#lists.isEmpty(homePromotionEvents)}\"")
                .contains("th:if=\"${#lists.size(homePromotionEvents) > 1}\"")
                .doesNotContain("home.event.");
        assertThat(sliderScript)
                .doesNotContain("fetch(", "/api/events/slide", "innerHTML", "/events/", "EVENT")
                .doesNotContain("navBar.style.backgroundColor", "pastelColors")
                .doesNotContain("#e0ffe0", "#fff5cc", "#ffe0f0", "#e0f7fa")
                .doesNotContain("getElementById")
                // 영역마다 따로 시작하고, 요소는 그 영역 안에서만 찾는다.
                .contains("document.querySelectorAll('#home-hero, #home-promotion')")
                .contains("root.querySelector('.swiper')", "root.querySelector('.progress-bar')")
                // 자동재생은 슬라이드가 두 장 이상이고 움직임 줄이기가 아닐 때만 켠다.
                .contains("const autoplayEnabled = hasMultipleSlides && !reducedMotion")
                .contains("autoplay: autoplayEnabled ? { delay: AUTOPLAY_DELAY")
                .contains("effect: 'fade'", "if (slideCount === 0")
                .contains("swiper.slidePrev()", "swiper.slideNext()")
                .contains("root.dataset.labelPlay", "root.dataset.labelPause");
        // Hero 는 본문 폭의 큰 이미지 한 장 위에 글을 얹는다. 글/이미지 두 칸 구조는 쓰지 않는다.
        assertThat(sliderCss)
                .contains("aspect-ratio: 2.2 / 1", "aspect-ratio: 2 / 1", "aspect-ratio: 6 / 5")
                .contains(".slide-img::after", "rgba(14, 17, 22,")
                .contains("#home-hero .slide-text {\n    position: absolute;")
                .contains("-webkit-line-clamp: 2")
                .contains(".slider-ui [hidden]", "prefers-reduced-motion: reduce")
                .contains("#home-hero .progress-bar")
                .contains("#home-hero .slide-text a.more")
                .doesNotContain("grid-template-columns", "35fr", "65fr")
                .doesNotContain("event-slider", ".description", "is-empty");
        // 폭별 Hero 모양은 slider.css 한 곳에서만 정한다.
        assertThat(resource("/static/css/home-mobile.css")).doesNotContain("#home-hero");
        assertThat(homeScript)
                .contains("SPRING", "SUMMER", "FALL", "WINTER")
                .contains("/api/season-destinations?season=")
                .contains("renderSeasonDestinations(currentSeason, tag.categoryName)")
                // 계절 탭은 숫자 카테고리 ID 대신 카테고리 이름으로 고른다.
                .contains("&categoryName=${encodeURIComponent(categoryName)}")
                // 가을: 첫 탭(기본)은 FALL 여행지 최근 7일 인기, 나머지는 같은 카테고리 이름 조회를 쓴다.
                // 탭 라벨은 '가을 꽃 여행'이지만 조회 카테고리 이름은 '가을 꽃 명소'다.
                .contains("[SEASON_POPULAR_TAB, '단풍 명소', '가을 꽃 명소', '가을 풍경 여행']")
                .contains("/api/season-destinations/popular?season=${season}&limit=${SEASON_CARD_LIMIT}")
                .contains("&limit=${SEASON_CARD_LIMIT}`")
                // 모든 탭은 네 장만 받는다. (슬라이드·더보기용으로 더 받지 않는다)
                .contains("const SEASON_CARD_LIMIT = 4;")
                .doesNotContain("categoryId=")
                // 인기 여행지는 필터 없는 편집형 영역이라 서버가 그린다. 메인 스크립트는 필터 API 를 부르지 않는다.
                .doesNotContain("/api/popular-destinations/")
                .doesNotContain("renderPopularRecommend", "popularTags");
        assertThat(template)
                .contains("th:each=\"destination, stat : ${popularDestinations}\"")
                .contains("th:href=\"@{/destinations/{id}(id=${destination.id})}\"")
                .contains("th:alt=\"${destination.name}\"");
    }

    @Test
    void fallSeasonTabsHaveFourLabelsInEveryLanguageAndShareOneFourColumnCard() throws IOException {
        for (String file : new String[]{"/messages.properties", "/messages_ko.properties",
                "/messages_en.properties", "/messages_ja.properties",
                "/messages_zh_CN.properties", "/messages_zh_TW.properties"}) {
            java.util.Properties messages = new java.util.Properties();
            messages.load(new java.io.StringReader(resource(file)));
            // 라벨 순서가 home.js 의 [인기, 단풍 명소, 가을 꽃 명소, 가을 풍경 여행] 과 맞아야 한다.
            assertThat(messages.getProperty("home.season.fall.tags").split("\\|")).as(file).hasSize(4);
        }
        assertThat(resource("/messages_ko.properties"))
                .contains("home.season.fall.tags=가을 인기 여행지|단풍 여행|가을 꽃 여행|가을 풍경 여행");
        // 데스크톱 계절 카드는 카드 수와 관계없이 한 줄 네 칸 폭으로 고정한다. (탭마다 크기가 달라지지 않는다)
        assertThat(resource("/static/css/home.css"))
                .contains(".trip-card-list .trip-card {\n    flex: 0 0 calc((100% - 48px) / 4);");
    }

    @Test
    void rouletteLeavesHomeWhileBackendRoutesRemain() throws IOException {
        String template = resource("/templates/home.html");

        assertThat(template)
                .doesNotContain("instant-trip")
                .doesNotContain("roulette-canvas")
                .doesNotContain("/js/random.js");
        assertThat(RandomRecommendController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/random-recommend");
        assertThat(RouletteRegionController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/roulette-region");
    }

    @Test
    void festivalsReplaceHomeCoursesWithManualRailAndExistingFestivalLinks() throws IOException {
        String template = resource("/templates/home.html");
        String homeCss = resource("/static/css/home-festival.css");

        assertThat(template)
                .contains("지금 가볼 만한 축제·행사")
                .contains("th:if=\"${!#lists.isEmpty(homeFestivals)}\"")
                .contains("th:each=\"festival : ${homeFestivals}\"")
                .contains("@{/festivals/{id}(id=${festival.id})}")
                .contains("@{/travel-info(contentType='FESTIVAL')}")
                .contains("home-festival-placeholder")
                .doesNotContain("popularCourses", "popular-course-section");
        assertThat(homeCss)
                .contains("calc((100% - 54px) / 4)", "calc((100% - 32px) / 3)")
                .contains("overflow-x: auto", "flex-basis: 55%", "aspect-ratio: 3 / 4")
                .doesNotContain("animation:");
    }

    @Test
    void mainScriptsReadLocalizedUiFromTheRenderedPageWithoutChangingApiContentBindings()
            throws IOException {
        String template = resource("/templates/home.html");
        String homeScript = resource("/static/js/home.js");
        String sliderScript = resource("/static/js/slider.js");

        assertThat(template)
                .contains("id=\"home-i18n\"")
                .contains("#{home.season.spring.title}")
                // Hero·프로모션 배너의 고정 문구는 메시지로, 콘텐츠 값은 서버가 바꿔 둔 그대로 그린다.
                .contains("#{home.hero.details}", "#{home.promotion.details}", "#{home.promotion.eyebrow}")
                .contains("th:text=\"${item.categoryName}\"", "th:text=\"${item.title}\"")
                .contains("th:text=\"${event.title}\"")
                .doesNotContain("th:text=\"${event.description}\"")
                .contains("th:text=\"${festival.title}\"")
                .contains("th:text=\"${festival.location}\"");
        assertThat(homeScript)
                .contains("home-i18n", ".dataset", "homeI18n.springTitle")
                .contains("${dest.name}", "${regionText(dest)}")
                // 지역은 API 가 준 상위 지역 + 지역 이름을 이어 붙인다. (화면에 지역명을 박아 두지 않는다)
                .contains("[dest.parentRegionName, dest.regionName]")
                .doesNotContain("데이터가 없습니다.", "불러오기에 실패했습니다.");
        assertThat(sliderScript)
                // 슬라이더는 글자를 만들지 않는다. 일시정지/재생 이름만 자기 영역의 data 속성에서 읽는다.
                .contains("root.dataset.labelPause", "root.dataset.labelPlay")
                .doesNotContain("home-i18n", "escapeHtml", ">자세히 보기<", "자세히 보기");
    }

    /**
     * 메인 이미지 로딩: 첫 화면 핵심 이미지(Hero 첫 장·인기 여행지 대표 카드)는 미루지 않고,
     * 그 아래 카드·계절 배경은 다가갈 때 받는다. 여행지 카드는 서버 썸네일(srcset)을 쓰고 칸 비율은 CSS 가 잡는다.
     */
    @Test
    void firstScreenImagesLoadEagerlyAndBelowTheFoldImagesWait() throws IOException {
        String template = resource("/templates/home.html");
        String homeScript = resource("/static/js/home.js");
        String homeCss = resource("/static/css/home.css");
        var document = Jsoup.parse(template);

        // Hero 첫 장은 높은 우선순위로 바로 받고, 나머지 슬라이드는 미룬다.
        assertThat(document.select("#home-hero .slide-img img").attr("th:attr"))
                .contains("fetchpriority=${stat.first} ? 'high' : null")
                .contains("loading=${stat.first} ? 'eager' : 'lazy'");
        // 인기 여행지: 대표 카드만 바로, 보조 카드는 미룬다. 둘 다 서버 썸네일 srcset 을 쓴다.
        var popular = document.select(".recommend-editorial img.card-thumb");
        assertThat(popular).hasSize(1);
        assertThat(popular.attr("loading")).isEmpty();
        assertThat(popular.attr("th:attr"))
                .contains("loading=${stat.first} ? 'eager' : 'lazy'")
                .contains("srcset=${destination.cardImageSrcset}");
        // 첫 화면 아래 영역(이벤트 배너·랜드마크·축제)은 미룬다.
        assertThat(document.select("#home-promotion img, .home-converge-photo img, .home-festival-image"))
                .hasSize(3)
                .allSatisfy(image -> assertThat(image.attr("loading")).isEqualTo("lazy"));
        // 계절 추천 카드(JS 생성)도 미루고, 썸네일 srcset 을 그대로 쓴다.
        assertThat(homeScript)
                .contains("alt=\"${dest.name}\" loading=\"lazy\" decoding=\"async\" />")
                .contains("`srcset=\"${dest.cardImageSrcset}\" sizes=\"${imageSizes}\"`");
        // 계절 배경 사진(CSS 배경)은 영역이 화면에 가까워져 is-bg-ready 가 붙을 때만 받는다.
        assertThat(homeScript)
                .contains("deferSeasonBackground(wrapper);")
                .contains("element.classList.add('is-bg-ready');")
                .contains("{rootMargin: '600px 0px'}");
        for (String season : new String[]{"spring", "summer", "fall", "winter"}) {
            assertThat(homeCss).contains(".seasonal-bg-wrapper." + season + ".is-bg-ready {\n    background-image: url(");
        }
        assertThat(homeCss).doesNotContainPattern("\\.seasonal-bg-wrapper\\.(spring|summer|fall|winter) \\{\\s*background-image");
        // 첫 화면을 막는 Swiper CSS 는 정확한 버전으로 불러 CDN 302 왕복을 피한다.
        assertThat(template)
                .contains("https://unpkg.com/swiper@9.4.1/swiper-bundle.min.css")
                .contains("https://unpkg.com/swiper@9.4.1/swiper-bundle.min.js")
                .doesNotContain("swiper@9/");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
