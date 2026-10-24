package com.tripbora.controller.travelinfo;

import com.tripbora.config.CustomLoginSuccessHandler;
import com.tripbora.config.CustomLogoutSuccessHandler;
import com.tripbora.config.SecurityConfig;
import com.tripbora.config.i18n.I18nConfig;
import com.tripbora.config.i18n.TripBoraLocaleResolver;
import com.tripbora.dto.TravelInfoDetailDto;
import com.tripbora.model.TravelInfoContentFormat;
import com.tripbora.model.TravelInfoContentType;
import com.tripbora.model.TravelInfoScope;
import com.tripbora.repository.user.UserMapper;
import com.tripbora.service.category.InfoCategoryService;
import com.tripbora.service.category.ReferenceNameLocalizationService;
import com.tripbora.service.travelinfo.FestivalDetailService;
import com.tripbora.service.travelinfo.TravelInfoService;
import com.tripbora.service.travelinfo.structured.StructuredBlock;
import com.tripbora.service.travelinfo.structured.StructuredContent;
import com.tripbora.service.travelinfo.structured.StructuredContentSamples;
import com.tripbora.service.travelinfo.structured.StructuredImage;
import com.tripbora.service.travelinfo.structured.StructuredImageCredit;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공개 여행정보 상세의 QUILL / STRUCTURED 분기와 구조화 블록 SSR.
 *
 * <p>Service 가 넘겨준 typed model 을 그대로 그리는지만 본다. (언어 바꾸기·검사는 Service 쪽 테스트가 본다)
 */
@WebMvcTest(TravelInfoController.class)
@Import({SecurityConfig.class, I18nConfig.class})
class TravelInfoStructuredDetailRenderingTest {

    @MockitoBean private TravelInfoService travelInfoService;
    @MockitoBean private FestivalDetailService festivalDetailService;
    @MockitoBean private InfoCategoryService infoCategoryService;
    @MockitoBean private ReferenceNameLocalizationService referenceNameLocalizationService;
    @MockitoBean private CustomLoginSuccessHandler customLoginSuccessHandler;
    @MockitoBean private CustomLogoutSuccessHandler customLogoutSuccessHandler;
    @MockitoBean private UserMapper userMapper;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void festivalGalleryProtectsTypeThreeAndKeepsOriginalRatioZoom() throws Exception {
        TravelInfoDetailDto detail = base();
        detail.setContentType(TravelInfoContentType.FESTIVAL);
        detail.setTitle("서울 가을 문화예술 축제와 함께하는 특별한 밤");
        detail.setPeriods(java.util.List.of(new com.tripbora.dto.TravelInfoPeriodDto(
                java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31))));
        var festivalInfo = new com.tripbora.model.FestivalInfo();
        festivalInfo.setEventPlace("서울광장 및 문화예술 행사장");
        festivalInfo.setAddress("서울특별시 중구 세종대로 110 서울광장 일대, 야외 무대 및 시민 체험 공간");
        java.util.List<com.tripbora.model.InfoImage> images = new java.util.ArrayList<>();
        for (int index = 0; index < 3; index++) {
            var image = new com.tripbora.model.InfoImage();
            image.setImageUrl("/uploads/festival-gallery-" + index + ".jpg");
            image.setLicenseType(index == 1 ? "KOGL_TYPE_3" : "KOGL_TYPE_1");
            image.setSourceName("한국관광공사");
            images.add(image);
        }
        when(festivalDetailService.getPublicDetail(10L)).thenReturn(new com.tripbora.dto.FestivalDetailDto(
                detail, festivalInfo, images));
        String html = mockMvc.perform(get("/festivals/10"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Document document = Jsoup.parse(html);
        Elements slides = document.select("[data-festival-gallery-slide]");
        assertThat(slides).hasSize(3);
        assertThat(slides.get(0).hasClass("is-active")).isTrue();
        assertThat(slides.get(0).hasClass("is-no-derivatives")).isFalse();
        assertThat(slides.get(1).hasClass("is-no-derivatives")).isTrue();
        assertThat(document.select("[data-festival-gallery-open]")).hasSize(3);
        assertThat(document.select("#festival-image-modal")).hasSize(1);
        for (String asset : java.util.List.of("/css/festival-detail.css", "/js/festival-gallery.js")) {
            String url = document.select("link[href], script[src]").stream()
                    .map(element -> element.hasAttr("href") ? element.attr("href") : element.attr("src"))
                    .filter(value -> value.startsWith(asset.substring(0, asset.lastIndexOf('.'))))
                    .findFirst().orElseThrow();
            assertThat(url).matches("/(css|js)/.+-[a-f0-9]{32}\\.(css|js)");
            var response = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse();
            assertThat(response.getContentType()).isIn("text/css", "text/javascript", "application/javascript");
            assertThat(response.getContentAsString()).doesNotContain("<!DOCTYPE html>");
        }
        java.nio.file.Path report = java.nio.file.Path.of("build/reports/festival-gallery");
        java.nio.file.Files.createDirectories(report);
        java.nio.file.Files.writeString(report.resolve("detail.html"), html);
    }

    @Test
    void structuredArticleRendersBlocksInsteadOfTheDerivedSearchHtml() throws Exception {
        Document document = render(structured(StructuredContentSamples.palaceTour()), "ko");

        Element content = document.selectFirst(".travel-info-structured .structured-content");
        assertThat(content).isNotNull();
        // 파생 HTML 은 검색/SEO 용이다. 본문으로 그리지 않는다.
        assertThat(document.text()).doesNotContain("DERIVED-SEARCH-HTML");
        assertThat(document.select(".rich-text-content")).isEmpty();
        assertThat(content.children().stream().map(Element::className).toList()).containsExactly(
                "structured-section-title", "structured-text", "structured-figure",
                "structured-image-text", "structured-slider", "structured-callout",
                "structured-section-title", "structured-image-text is-image-right",
                "structured-slider", "structured-slider",
                "structured-grid is-two", "structured-grid is-three");
        assertThat(document.select("link[href^=/css/travel-info-structured.css]")).hasSize(1);
        assertThat(document.select("script[src^=/js/structured-slider.js]")).hasSize(1);
        assertThat(document.select("link[href^=/css/quill-content.css]")).hasSize(1);
    }

    @Test
    void sectionTitleTextAndCalloutUseHeadingsParagraphsAndNotes() throws Exception {
        Document document = render(structured(StructuredContentSamples.palaceTour()), "ko");

        Elements sections = document.select(".structured-section-title");
        assertThat(sections.select("h2").eachText()).containsExactly("경복궁", "창덕궁");
        assertThat(sections.first().id()).isEqualTo("structured-gyeongbok-intro");
        // 섹션 제목은 글 안의 소제목이다. 제목만 그리고 짧은 소개(lead)는 그리지 않는다.
        assertThat(sections).allMatch(section -> section.children().size() == 1
                && section.child(0).tagName().equals("h2"));
        assertThat(document.select(".structured-lead")).isEmpty();
        assertThat(document.select(".structured-text p")).hasSize(2);
        assertThat(document.select(".structured-text p").first().wholeText())
                .isEqualTo("조선 왕조의 법궁인 경복궁은 1395년에 지어졌습니다.\n광화문에서 시작해 북쪽으로 걸어 들어갑니다.");
        assertThat(document.selectFirst(".structured-callout").attr("role")).isEqualTo("note");
        assertThat(document.selectFirst(".structured-callout").text()).isEqualTo("경복궁은 매주 화요일 휴궁합니다.");
        // 블록 제목은 페이지 제목(h1) 아래 h2 / h3 이다.
        assertThat(document.select(".structured-content h1")).isEmpty();
        assertThat(document.select(".structured-image-text h3, .structured-slider h3").eachText())
                .containsExactly("근정전", "경복궁 주요 전각", "인정전", "후원 산책");
    }

    @Test
    void userTextIsEscapedNotInterpretedAsHtml() throws Exception {
        String html = renderHtml(structured(StructuredContentSamples.palaceTour()), "ko");
        Document document = Jsoup.parse(html);

        assertThat(document.select(".structured-content script")).isEmpty();
        assertThat(document.select(".structured-text p").get(1).text())
                .contains("<script>alert('x')</script> 는 글자 그대로");
        assertThat(html).contains("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;");
    }

    @Test
    void imagesCarryStoredSizeAltAndLoadingHints() throws Exception {
        Document document = render(structured(StructuredContentSamples.palaceTour()), "ko");

        Element full = document.selectFirst(".structured-figure img");
        assertThat(full.attr("src")).isEqualTo(StructuredContentSamples.image(1, 2000, 1333).url());
        assertThat(full.attr("alt")).isEqualTo("북악산 아래 펼쳐진 경복궁 전경");
        assertThat(full.attr("width")).isEqualTo("2000");
        assertThat(full.attr("height")).isEqualTo("1333");
        assertThat(document.selectFirst(".structured-figure figcaption").text()).isEqualTo("북악산 아래 펼쳐진 경복궁");
        assertThat(document.selectFirst(".structured-figure").hasClass("is-portrait")).isFalse();

        Element split = document.selectFirst(".structured-image-text img");
        assertThat(split.attr("alt")).isEqualTo("근정전 정면");
        assertThat(split.attr("width")).isEqualTo("1600");
        // 세 번째 블록부터는 늦게 읽는다. 첫 화면 근처 두 블록만 바로 읽는다.
        assertThat(document.select(".structured-content img").eachAttr("loading"))
                .allMatch(value -> value.equals("lazy"));
        assertThat(document.select(".structured-content img")).allMatch(img -> img.hasAttr("width")
                && img.hasAttr("height") && !img.attr("alt").isBlank());
    }

    @Test
    void sliderHasCarouselSemanticsCaptionsBelowImagesAndHiddenControlsUntilScriptRuns() throws Exception {
        Document document = render(structured(StructuredContentSamples.palaceTour()), "ko");
        Elements sliders = document.select("[data-structured-slider]");
        assertThat(sliders).hasSize(3);

        Element halls = sliders.get(0);
        assertThat(halls.attr("aria-roledescription")).isEqualTo("carousel");
        assertThat(halls.attr("aria-labelledby")).isEqualTo("structured-gyeongbok-halls-title");
        assertThat(halls.selectFirst("#structured-gyeongbok-halls-title").text()).isEqualTo("경복궁 주요 전각");
        assertThat(halls.selectFirst("[data-slider-track]").attr("tabindex")).isEqualTo("0");
        Elements slides = halls.select("[data-slider-slide]");
        assertThat(slides).hasSize(6);
        assertThat(slides.eachAttr("aria-label")).containsExactly("1 / 6", "2 / 6", "3 / 6", "4 / 6", "5 / 6", "6 / 6");
        assertThat(slides.eachAttr("aria-roledescription")).containsOnly("slide");
        Element first = slides.first();
        assertThat(first.select("figcaption strong").text()).isEqualTo("광화문");
        assertThat(first.select("figcaption span").text()).isEqualTo("경복궁의 정문");
        // 캡션은 사진 위가 아니라 사진 다음에 온다.
        assertThat(first.selectFirst("figure").child(0).hasClass("structured-slide-media")).isTrue();
        assertThat(first.selectFirst("figure").child(1).tagName()).isEqualTo("figcaption");
        // 제목만 있는 사진, 둘 다 없는 사진
        assertThat(slides.get(2).select("figcaption span")).isEmpty();

        Element controls = halls.selectFirst("[data-slider-controls]");
        assertThat(controls.hasAttr("hidden")).isTrue();
        assertThat(controls.selectFirst("[data-slider-current]").text()).isEqualTo("01");
        assertThat(controls.selectFirst(".structured-slider-counter").text()).isEqualTo("01 / 06");
        assertThat(controls.selectFirst(".structured-slider-counter").attr("aria-live")).isEqualTo("polite");
        assertThat(controls.select("button[type=button]").eachAttr("aria-label"))
                .containsExactly("이전 사진", "다음 사진");

        Element garden = sliders.get(1);
        assertThat(garden.select("[data-slider-slide]")).hasSize(3);
        assertThat(garden.select("[data-slider-slide]").get(2).select("figcaption")).isEmpty();
        assertThat(garden.selectFirst(".structured-slider-counter").text()).isEqualTo("01 / 03");

        // 한 장짜리 슬라이더는 번호·버튼이 없고, 제목이 없으면 기본 이름으로 불린다.
        Element single = sliders.get(2);
        assertThat(single.select("[data-slider-controls]")).isEmpty();
        assertThat(single.attr("aria-label")).isEqualTo("사진 슬라이더");
        assertThat(single.hasAttr("aria-labelledby")).isFalse();
        // 슬라이더마다 id 가 달라 서로 섞이지 않는다.
        assertThat(sliders.eachAttr("id")).containsExactly(
                "structured-gyeongbok-halls", "structured-changdeok-garden", "structured-deoksu-single");
    }

    @Test
    void emptyAltFallsBackToBlockTextAndAlwaysKeepsTheAltAttribute() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.FullImage("captioned", StructuredContentSamples.image(1, 1600, 1000),
                        null, "북악산 아래 경복궁"),
                new StructuredBlock.FullImage("decorative", StructuredContentSamples.image(2, 1600, 1000), null, null),
                new StructuredBlock.ImageText("split", StructuredBlock.ImagePosition.LEFT,
                        StructuredContentSamples.image(3, 1200, 900), null, "근정전", "본문"),
                new StructuredBlock.ImageSlider("halls", "경복궁 주요 전각", java.util.List.of(
                        new StructuredBlock.SliderItem("i1", StructuredContentSamples.image(4, 1800, 1200),
                                "광화문 정면", "광화문", null),
                        new StructuredBlock.SliderItem("i2", StructuredContentSamples.image(5, 1800, 1200),
                                null, "흥례문", null),
                        new StructuredBlock.SliderItem("i3", StructuredContentSamples.image(6, 1800, 1200),
                                null, null, "왕의 정전"),
                        new StructuredBlock.SliderItem("i4", StructuredContentSamples.image(7, 1800, 1200),
                                null, null, null)))));

        Document document = render(structured(content), "ko");

        Elements images = document.select(".structured-content img");
        assertThat(images).allMatch(img -> img.hasAttr("alt"));
        assertThat(images.eachAttr("alt")).containsExactly(
                "북악산 아래 경복궁", "", "근정전", "광화문 정면", "흥례문", "왕의 정전", "경복궁 주요 전각");
    }

    @Test
    void imageGridRendersTwoOrThreeFixedRatioCellsWithCaptionsBelow() throws Exception {
        Document document = render(structured(StructuredContentSamples.palaceTour()), "ko");

        Elements grids = document.select(".structured-content > ul.structured-grid");
        assertThat(grids).hasSize(2);
        Element pair = grids.get(0);
        Element trio = grids.get(1);
        assertThat(pair.id()).isEqualTo("structured-changdeok-pair");
        assertThat(pair.select("> li.structured-grid-item")).hasSize(2);
        assertThat(trio.select("> li.structured-grid-item")).hasSize(3);

        // 사진은 비율 틀(.structured-grid-media) 안에, 글은 사진 아래 figcaption 에 있을 때만 둔다.
        Element first = pair.selectFirst("li figure");
        assertThat(first.child(0).className()).isEqualTo("structured-grid-media");
        assertThat(first.selectFirst("img").attr("src")).isEqualTo(StructuredContentSamples.image(14, 1800, 1200).url());
        assertThat(first.selectFirst("img").attr("width")).isEqualTo("1800");
        assertThat(first.select("figcaption strong").text()).isEqualTo("낙선재");
        assertThat(first.select("figcaption span").text()).isEqualTo("단청을 칠하지 않은 소박한 건물");
        assertThat(pair.select("li").get(1).select("figcaption span")).isEmpty();
        assertThat(trio.select("li").get(1).select("figcaption strong")).isEmpty();

        // alt 는 슬라이더와 같은 규칙: alt → 제목 → 설명. (블록 제목은 없다)
        assertThat(trio.select("img").eachAttr("alt")).containsExactly("대한문 사진", "중화전 처마", "후원 사진");
        assertThat(grids.select("img").eachAttr("loading")).allMatch("lazy"::equals);
    }

    @Test
    void imageGridAltFallsBackToItemTextsAndAlwaysKeepsTheAttribute() throws Exception {
        StructuredImage image = StructuredContentSamples.image(1, 1600, 1200);
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.ImageGrid("grid", 3, java.util.List.of(
                        new StructuredBlock.SliderItem("a", image, null, "광화문", "정문"),
                        new StructuredBlock.SliderItem("b", image, null, null, "흥례문"),
                        new StructuredBlock.SliderItem("c", image, null, null, null)))));

        Document document = render(structured(content), "ko");

        Elements images = document.select(".structured-grid img");
        assertThat(images).allMatch(img -> img.hasAttr("alt"));
        assertThat(images.eachAttr("alt")).containsExactly("광화문", "흥례문", "");
        // 앞쪽 두 블록 안의 사진은 바로 읽는다.
        assertThat(images.eachAttr("loading")).allMatch("eager"::equals);
        assertThat(document.select(".structured-grid figcaption")).hasSize(2);
    }

    @Test
    void richTextLayoutPresetBecomesAClassAndMissingLayoutIsDefault() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.RichText("legacy", null, "layout 이 없던 예전 글"),
                new StructuredBlock.RichText("wide", StructuredBlock.RichTextLayout.DEFAULT, "기본 폭"),
                new StructuredBlock.RichText("essay", StructuredBlock.RichTextLayout.FOCUSED, "집중형")));

        Document document = render(structured(content), "ko");

        assertThat(document.select(".structured-text").eachAttr("class")).containsExactly(
                "structured-text", "structured-text", "structured-text is-focused");
        assertThat(document.select(".structured-text").eachAttr("style")).allMatch(String::isEmpty);
    }

    /** 서식을 쓴 일반 글은 정리된 HTML 을 그대로, 예전 일반 글은 지금처럼 escape 한 문단으로 그린다. */
    @Test
    void formattedRichTextRendersItsPresetsAndPlainTextStaysEscaped() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.RichText("rich", null, null,
                        "<h3>근정전</h3><p><strong>굵게</strong> <mark class=\"rt-point\">포인트</mark>"
                                + " <a href=\"https://www.royalpalace.go.kr/\" onclick=\"x()\">홈페이지</a></p>"
                                + "<p class=\"rt-size-small\">작은</p><p class=\"rt-size-large\">큰</p><h4>소제목</h4>"
                                + "<p><span class=\"rt-font-sans\">고딕</span> <span class=\"rt-font-serif\">명조</span>"
                                + " <span style=\"font-family: Comic\">임의</span></p>"
                                + "<ul><li>하나</li></ul><blockquote>인용</blockquote><script>alert(1)</script>"),
                new StructuredBlock.RichText("plain", null, "<b>글자 그대로</b>")));

        Document document = render(structured(content), "ko");

        Element rich = document.selectFirst("#structured-rich");
        assertThat(rich).isNotNull();
        assertThat(rich.className()).isEqualTo("structured-text is-rich");
        assertThat(rich.select("h3").text()).isEqualTo("근정전");
        // 크기·글꼴 프리셋은 class 그대로 남고, 프리셋이 아닌 span(style)은 글만 남는다.
        assertThat(rich.select("p.rt-size-small").text()).isEqualTo("작은");
        assertThat(rich.select("p.rt-size-large").text()).isEqualTo("큰");
        assertThat(rich.select("h4").text()).isEqualTo("소제목");
        assertThat(rich.select("span.rt-font-sans").text()).isEqualTo("고딕");
        assertThat(rich.select("span.rt-font-serif").text()).isEqualTo("명조");
        assertThat(rich.select("[style]")).isEmpty();
        assertThat(rich.text()).contains("임의");
        assertThat(rich.select("strong").text()).isEqualTo("굵게");
        assertThat(rich.select("mark.rt-point").text()).isEqualTo("포인트");
        assertThat(rich.select("ul li").text()).isEqualTo("하나");
        assertThat(rich.select("blockquote").text()).isEqualTo("인용");
        Element link = rich.selectFirst("a");
        assertThat(link.attr("href")).isEqualTo("https://www.royalpalace.go.kr/");
        assertThat(link.attr("rel")).isEqualTo("noopener noreferrer nofollow");
        assertThat(link.hasAttr("onclick")).isFalse();
        assertThat(rich.select("script")).isEmpty();
        Element plain = document.selectFirst("#structured-plain");
        assertThat(plain.className()).isEqualTo("structured-text");
        assertThat(plain.select("b")).isEmpty();
        assertThat(plain.select("p").text()).isEqualTo("<b>글자 그대로</b>");
    }

    @Test
    void sectionTitleUsesALeftAccentLineAndTextBlocksShareTheContentStartLine() throws IOException {
        String css;
        try (InputStream input = getClass().getResourceAsStream("/static/css/travel-info-structured.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 위쪽 짧은 가로선 대신 왼쪽 세로 accent 선(3px). 카드·배경은 없다.
        assertThat(rule(css, ".structured-section-title"))
                .contains("border-left: 3px solid var(--structured-accent);")
                .doesNotContain("background");
        assertThat(css).doesNotContain(".structured-section-title::before", ".structured-lead");
        // 섹션 제목과 기본 본문은 가운데로 몰지 않고 콘텐츠 왼쪽 기준선에서 시작한다.
        assertThat(rule(css, ".structured-text"))
                .contains("max-width: var(--structured-body-width);", "text-align: left;")
                .doesNotContain("margin-inline");
        assertThat(rule(css, ".structured-text.is-focused"))
                .contains("max-width: var(--structured-focused-width);", "margin-inline: auto;");
        assertThat(css).contains("--structured-body-width: 880px;", "--structured-focused-width: 700px;");
    }

    @Test
    void blockRulesLeaveTheGapBetweenBlocksToTheSiblingSpacingRules() throws IOException {
        String css;
        try (InputStream input = getClass().getResourceAsStream("/static/css/travel-info-structured.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 블록 규칙이 자기 위아래 margin 을 정하면(예: margin: 0) "앞 블록 + 다음 블록" 여백을 덮어써 블록이 붙는다.
        for (String block : java.util.List.of(".structured-section-title", ".structured-text", ".structured-figure",
                ".structured-image-text", ".structured-grid", ".structured-callout")) {
            assertThat(rule(css, block)).as(block)
                    .doesNotContain("margin:", "margin-top", "margin-bottom", "margin-block");
        }
        assertThat(css).contains(
                ".structured-content > * + * {\n    margin-top: var(--structured-space-media);",
                "margin-top: var(--structured-space-media-pair);");
    }

    /** 미디어 쿼리 밖의 첫 규칙 본문. */
    private String rule(String css, String selector) {
        int start = css.indexOf("\n" + selector + " {");
        assertThat(start).as(selector).isNotNegative();
        return css.substring(start, css.indexOf('}', start));
    }

    @Test
    void altTextIsEscapedInsideTheAttribute() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.FullImage("a", StructuredContentSamples.image(1, 1600, 1000),
                        "\"광화문\" <script>x</script>", null)));

        String html = renderHtml(structured(content), "ko");

        assertThat(html).contains("alt=\"&quot;광화문&quot; &lt;script&gt;x&lt;/script&gt;\"");
        assertThat(Jsoup.parse(html).selectFirst(".structured-figure img").attr("alt"))
                .isEqualTo("\"광화문\" <script>x</script>");
    }

    @Test
    void localizedModelFromTheServiceIsRenderedAsIs() throws Exception {
        TravelInfoDetailDto detail = structured(StructuredContentSamples.palaceTourInEnglish());
        detail.setTitle("Seoul palace tour");

        Document document = render(detail, "en");

        assertThat(document.select(".structured-section-title h2").eachText())
                .containsExactly("Gyeongbokgung", "창덕궁");
        // 예전 번역에 남은 섹션 제목 lead 는 읽기만 하고 그리지 않는다.
        assertThat(document.select(".structured-lead")).isEmpty();
        assertThat(document.text()).doesNotContain("The main royal palace of Joseon");
        assertThat(document.selectFirst(".structured-figure img").attr("alt")).isEqualTo("Gyeongbokgung below Bugaksan");
        assertThat(document.selectFirst(".structured-figure img").attr("src"))
                .isEqualTo(StructuredContentSamples.image(1, 2000, 1333).url());
        assertThat(document.selectFirst("#structured-gyeongbok-halls-title").text())
                .isEqualTo("Main halls of Gyeongbokgung");
        Element first = document.selectFirst("[data-slider-slide]");
        assertThat(first.select("figcaption strong").text()).isEqualTo("Gwanghwamun");
        assertThat(first.selectFirst("img").attr("alt")).isEqualTo("Gwanghwamun gate");
        // 번역이 없는 칸은 Service 가 원문을 넣어 준 그대로다.
        assertThat(document.select("[data-slider-slide]").get(1).select("figcaption strong").text())
                .isEqualTo("흥례문");
        assertThat(document.selectFirst(".structured-callout").text()).isEqualTo("Gyeongbokgung is closed on Tuesdays.");
        assertThat(document.select(".structured-slider-buttons button").eachAttr("aria-label"))
                .startsWith("Previous photo", "Next photo");
        assertThat(document.select("[data-structured-slider]").get(2).attr("aria-label")).isEqualTo("Photo slider");
    }

    @Test
    void quillArticleKeepsTheExistingRichTextBodyWithoutStructuredAssets() throws Exception {
        TravelInfoDetailDto detail = base();
        detail.setContent("<p class=\"ql-align-center\"><img src=\"/uploads/editor/a.png\" "
                + "class=\"ql-image-size-50\" alt=\"사진\"></p>");

        String html = renderHtml(detail, "ko");
        Document document = Jsoup.parse(html);

        assertThat(html).contains("<section class=\"travel-info-detail-content rich-text-content rich-text-image-layout\"");
        assertThat(document.selectFirst(".rich-text-content img.ql-image-size-50")).isNotNull();
        assertThat(document.select(".structured-content, .travel-info-structured")).isEmpty();
        assertThat(document.select("link[href^=/css/travel-info-structured.css]")).isEmpty();
        assertThat(document.select("script[src^=/js/structured-slider.js]")).isEmpty();
        // QUILL 본문 사진에는 확대 모달을 붙이지 않는다.
        assertThat(document.select("[data-structured-gallery], [data-structured-gallery-image]")).isEmpty();
        assertThat(document.select("script[src^=/js/structured-gallery.js]")).isEmpty();
    }

    @Test
    void everyStructuredImageIsAGalleryItemAndThePageHasOneModal() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.FullImage("full",
                        credited(credit(null, "궁능유적본부", "https://example.org/a", "공공누리 제1유형", null)),
                        "강녕전", "경복궁 강녕전 내부"),
                new StructuredBlock.ImageText("split", StructuredBlock.ImagePosition.LEFT,
                        StructuredContentSamples.image(2, 1600, 1200), "근정전 정면", "근정전", "조선의 법궁"),
                new StructuredBlock.ImageSlider("slider", null, java.util.List.of(
                        new StructuredBlock.SliderItem("i1", StructuredContentSamples.image(3, 1600, 1200),
                                null, "광화문", "경복궁의 정문"),
                        new StructuredBlock.SliderItem("i2", StructuredContentSamples.image(4, 1600, 1200),
                                null, null, null))),
                new StructuredBlock.ImageGrid("grid", 2, java.util.List.of(
                        new StructuredBlock.SliderItem("c1", StructuredContentSamples.image(5, 1600, 1200),
                                null, "낙선재", null),
                        new StructuredBlock.SliderItem("c2", StructuredContentSamples.image(6, 1600, 1200),
                                null, null, "부용정")))));

        Document document = render(structured(content), "ko");

        // 본문의 모든 사진이 그려진 순서 그대로 갤러리 사진이다. 제목·설명은 있을 때만 data 로 싣는다.
        Elements images = document.select(".travel-info-structured [data-structured-gallery-image]");
        assertThat(images).hasSize(6).hasSameSizeAs(document.select(".structured-content img"));
        assertThat(images.eachAttr("src")).containsExactly(
                StructuredContentSamples.image(1, 1600, 1000).url(), StructuredContentSamples.image(2, 1600, 1200).url(),
                StructuredContentSamples.image(3, 1600, 1200).url(), StructuredContentSamples.image(4, 1600, 1200).url(),
                StructuredContentSamples.image(5, 1600, 1200).url(), StructuredContentSamples.image(6, 1600, 1200).url());
        assertThat(images.get(0).attr("data-gallery-description")).isEqualTo("경복궁 강녕전 내부");
        assertThat(images.get(0).hasAttr("data-gallery-title")).isFalse();
        assertThat(images.get(1).attr("data-gallery-title")).isEqualTo("근정전");
        assertThat(images.get(2).attr("data-gallery-title")).isEqualTo("광화문");
        assertThat(images.get(2).attr("data-gallery-description")).isEqualTo("경복궁의 정문");
        assertThat(images.get(3).hasAttr("data-gallery-title") || images.get(3).hasAttr("data-gallery-description")).isFalse();
        assertThat(images.get(5).attr("data-gallery-description")).isEqualTo("부용정");
        // 모달 DOM 은 페이지에 하나뿐이고, 이름·버튼 이름은 현재 언어 문구다.
        Elements modals = document.select("dialog[data-structured-gallery]");
        assertThat(modals).hasSize(1);
        Element modal = modals.first();
        assertThat(modal.attr("role")).isEqualTo("dialog");
        assertThat(modal.attr("aria-modal")).isEqualTo("true");
        assertThat(modal.attr("aria-label")).isEqualTo("사진 확대 보기");
        assertThat(modal.attr("data-open-label")).isEqualTo("사진 크게 보기");
        assertThat(modal.select("[data-gallery-close]").attr("aria-label")).isEqualTo("사진 닫기");
        assertThat(modal.select("[data-gallery-prev]").attr("aria-label")).isEqualTo("이전 사진");
        assertThat(modal.select("[data-gallery-next]").attr("aria-label")).isEqualTo("다음 사진");
        assertThat(modal.select("img[data-gallery-current]")).hasSize(1);
        assertThat(modal.select("[data-gallery-slot]").eachAttr("data-gallery-slot"))
                .containsExactly("title", "description", "credit");
        assertThat(document.select("script[src^=/js/structured-gallery.js]")).hasSize(1);
        // 모달이 복사해 쓸 출처 줄은 본문에 그대로 있다. (링크 규칙 그대로)
        assertThat(document.select(".structured-figure .structured-media-credit a").attr("rel"))
                .isEqualTo("noopener noreferrer nofollow");
    }

    @Test
    void unreadableStructuredBlocksFallBackToTheDerivedHtml() throws Exception {
        TravelInfoDetailDto detail = structured(null);
        detail.setContent("<h2>서울 궁 투어</h2><p>대신 보이는 글</p>");

        Document document = render(detail, "ko");

        assertThat(document.selectFirst(".rich-text-content").text()).isEqualTo("서울 궁 투어 대신 보이는 글");
        assertThat(document.select(".structured-content")).isEmpty();
        assertThat(document.select("script[src^=/js/structured-slider.js]")).isEmpty();
    }

    // ---- 이미지 출처표시(credit) ------------------------------------------------------------

    @Test
    void imagesWithoutCreditKeepTheExistingCaptionMarkup() throws Exception {
        String html = renderHtml(structured(StructuredContentSamples.palaceTour()), "ko");
        Document document = Jsoup.parse(html);

        assertThat(document.select(".structured-media-credit")).isEmpty();
        assertThat(document.text()).doesNotContain("사진:");
        // 출처 기능 전과 같다: 사진 다음 곧바로 캡션이고, 사진 칸 틀 안에는 사진 하나뿐이다. (빈 출처 줄 없음)
        assertThat(html).contains("<figcaption class=\"structured-media-caption\">북악산 아래 펼쳐진 경복궁</figcaption>");
        assertThat(classNames(document.selectFirst(".structured-figure").children()))
                .containsExactly("", "structured-media-caption");
        Element first = document.selectFirst("[data-slider-slide] figcaption");
        assertThat(classNames(first.children())).containsExactly("structured-media-title", "");
        assertThat(first.previousElementSibling().hasClass("structured-slide-media")).isTrue();
        for (String media : java.util.List.of(".structured-image-text-media",
                ".structured-slide-media", ".structured-grid-media")) {
            assertThat(document.select(media)).as(media).isNotEmpty()
                    .allMatch(element -> element.children().size() == 1 && element.child(0).is("img"));
        }
        assertThat(document.select("[data-slider-slide]").get(8).select("figcaption")).isEmpty();
        assertThat(document.select(".structured-media-caption").stream().map(Element::text))
                .noneMatch(String::isBlank);
    }

    @Test
    void creditLineListsAuthorSourceAndLicenseInOrderAndLeavesOutEmptyParts() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                creditOnly("author", credit("John Doe", null, null, null, null)),
                creditOnly("source", credit(null, "한국관광공사", null, null, null)),
                creditOnly("all", credit("John Doe", "Wikimedia Commons", null, "CC BY-SA 4.0", null)),
                creditOnly("same", credit("한국관광공사", "한국관광공사", null, "공공누리 제1유형", null)),
                creditOnly("no-author", credit(null, "한국관광공사", null, "공공누리 제1유형", null))));

        Document document = render(structured(content), "ko");

        // 제목·설명이 없으면 figcaption 을 만들지 않고, 사진 바로 아래 출처 한 줄만 있다.
        assertThat(document.select(".structured-figure figcaption")).isEmpty();
        assertThat(document.select(".structured-figure > img + .structured-media-credit").eachText()).containsExactly(
                "사진: John Doe",
                "사진: 한국관광공사",
                "사진: John Doe · Wikimedia Commons · CC BY-SA 4.0",
                "사진: 한국관광공사 · 공공누리 제1유형",
                "사진: 한국관광공사 · 공공누리 제1유형");
        assertThat(document.select(".structured-media-credit-separator").eachAttr("aria-hidden")).containsOnly("true");
        assertThat(document.select(".structured-media-credit a")).isEmpty();
    }

    @Test
    void sourceAndLicenseBecomeNewTabLinksOnlyWhenTheirUrlExists() throws Exception {
        String sourceUrl = "https://commons.wikimedia.org/wiki/File:A.jpg";
        String licenseUrl = "https://creativecommons.org/licenses/by-sa/4.0/";
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                creditOnly("both", credit("John Doe", "Wikimedia Commons", sourceUrl, "CC BY-SA 4.0", licenseUrl)),
                creditOnly("license", credit("홍길동", "개인 블로그", null, "CC BY 4.0",
                        "https://creativecommons.org/licenses/by/4.0/")),
                creditOnly("same", credit("한국관광공사", "한국관광공사", "https://www.visitkorea.or.kr", null, null))));

        Document document = render(structured(content), "ko");

        Elements credits = document.select(".structured-media-credit");
        Elements both = credits.get(0).select("a");
        assertThat(both.eachText()).containsExactly("Wikimedia Commons", "CC BY-SA 4.0");
        assertThat(both.eachAttr("href")).containsExactly(sourceUrl, licenseUrl);
        assertThat(credits.select("a")).allMatch(link -> link.attr("target").equals("_blank")
                && link.attr("rel").equals("noopener noreferrer nofollow"));
        // 저작자는 링크가 아니다. URL 은 링크 주소로만 쓰고 화면 글로 보이지 않는다.
        assertThat(credits.get(0).select("span").eachText()).contains("John Doe");
        assertThat(credits.text()).doesNotContain("https://");
        // 출처 URL 이 없으면 출처명은 글, 라이선스 URL 이 있으면 라이선스만 링크다.
        assertThat(credits.get(1).select("a").eachText()).containsExactly("CC BY 4.0");
        // 저작자와 출처명이 같으면 링크를 가질 수 있는 출처명 하나만 남는다.
        assertThat(credits.get(2).text()).isEqualTo("사진: 한국관광공사");
        assertThat(credits.get(2).select("a").eachAttr("href")).containsExactly("https://www.visitkorea.or.kr");
    }

    @Test
    void everyImageBlockShowsTheCreditOfItsOwnImageRightBelowIt() throws Exception {
        StructuredImage kto = credited(credit(null, "한국관광공사", null, "공공누리 제1유형", null));
        StructuredImage commons = credited(credit("John Doe", "Wikimedia Commons", null, "CC BY-SA 4.0", null));
        StructuredImage plain = StructuredContentSamples.image(2, 1600, 1200);
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                new StructuredBlock.FullImage("full", kto, "강녕전", "경복궁 강녕전 내부"),
                new StructuredBlock.ImageText("split", StructuredBlock.ImagePosition.RIGHT, commons,
                        "근정전", "근정전", "조선의 법궁"),
                new StructuredBlock.ImageSlider("slider", "주요 전각", java.util.List.of(
                        new StructuredBlock.SliderItem("i1", kto, null, "강녕전 내부", "왕의 침전"),
                        new StructuredBlock.SliderItem("i2", commons, null, null, null),
                        new StructuredBlock.SliderItem("i3", plain, null, null, null))),
                new StructuredBlock.ImageGrid("grid", 2, java.util.List.of(
                        new StructuredBlock.SliderItem("c1", plain, null, "광화문", null),
                        new StructuredBlock.SliderItem("c2", commons, null, null, "가을의 경복궁")))));

        Document document = render(structured(content), "ko");

        // 출처는 사진(또는 사진 칸 틀) 바로 다음 한 줄이다. 사진 위에 겹치지 않고, 캡션에도 들어가지 않는다.
        assertThat(document.select("figcaption .structured-media-credit")).isEmpty();
        Elements credits = document.select(".structured-media-credit");
        assertThat(credits).hasSize(5);
        assertThat(credits).allMatch(credit -> credit.previousElementSibling().is(
                "img, .structured-slide-media, .structured-grid-media"));
        assertThat(document.select(".structured-slide-media .structured-media-credit, "
                + ".structured-grid-media .structured-media-credit")).isEmpty();
        // FULL_IMAGE: 사진 → 출처 → 캡션(마지막 자식)
        Element full = document.selectFirst(".structured-figure");
        assertThat(classNames(full.children())).containsExactly("", "structured-media-credit", "structured-media-caption");
        assertThat(full.child(1).text()).isEqualTo("사진: 한국관광공사 · 공공누리 제1유형");
        assertThat(full.selectFirst("figcaption").text()).isEqualTo("경복궁 강녕전 내부");
        // IMAGE_TEXT: 사진 칸 안, 사진 바로 아래. 글 칸은 그대로다.
        assertThat(document.selectFirst(".structured-image-text-media > img + .structured-media-credit").text())
                .isEqualTo("사진: John Doe · Wikimedia Commons · CC BY-SA 4.0");
        assertThat(document.selectFirst(".structured-image-text-body h3").text()).isEqualTo("근정전");
        // IMAGE_SLIDER: 사진 → 출처 → 제목·설명. 출처만 있는 사진에는 캡션이 없다.
        Elements slides = document.select("[data-slider-slide]");
        assertThat(classNames(slides.get(0).selectFirst("figure").children()))
                .containsExactly("structured-slide-media", "structured-media-credit", "structured-media-caption");
        assertThat(slides.get(0).select(".structured-media-credit").text()).isEqualTo("사진: 한국관광공사 · 공공누리 제1유형");
        assertThat(classNames(slides.get(1).selectFirst("figure").children()))
                .containsExactly("structured-slide-media", "structured-media-credit");
        assertThat(slides.get(2).select(".structured-media-credit, figcaption")).isEmpty();
        // IMAGE_GRID: 칸마다 자기 사진 바로 아래
        Elements cells = document.select(".structured-grid-item");
        assertThat(cells.get(0).select(".structured-media-credit")).isEmpty();
        assertThat(cells.get(0).select("figcaption").text()).isEqualTo("광화문");
        assertThat(classNames(cells.get(1).selectFirst("figure").children()))
                .containsExactly("structured-grid-media", "structured-media-credit", "structured-media-caption");
        assertThat(cells.get(1).select(".structured-media-credit").text())
                .isEqualTo("사진: John Doe · Wikimedia Commons · CC BY-SA 4.0");
        assertThat(cells.get(1).select(".structured-media-credit").attr("title"))
                .isEqualTo("사진: John Doe · Wikimedia Commons · CC BY-SA 4.0");
        assertThat(cells.get(1).select("figcaption").text()).isEqualTo("가을의 경복궁");
    }

    @Test
    void creditTextIsEscapedAndNotTranslatedWhileTheLabelFollowsTheLanguage() throws Exception {
        StructuredContent content = new StructuredContent(1, java.util.List.of(
                creditOnly("a", credit("<b>홍길동</b>", null, null, "공공누리 제1유형", null))));
        java.util.Map<String, String> labels = java.util.Map.of(
                "ko", "사진:", "en", "Photo:", "ja", "写真:", "zh-CN", "图片：", "zh-TW", "圖片：");

        for (java.util.Map.Entry<String, String> label : labels.entrySet()) {
            String html = renderHtml(structured(content), label.getKey());
            Element credit = Jsoup.parse(html).selectFirst(".structured-media-credit");
            assertThat(credit.child(0).text()).as(label.getKey()).isEqualTo(label.getValue());
            assertThat(credit.text()).as(label.getKey())
                    .isEqualTo(label.getValue() + " <b>홍길동</b> · 공공누리 제1유형");
            assertThat(credit.select("b")).isEmpty();
            assertThat(html).contains("&lt;b&gt;홍길동&lt;/b&gt;");
        }
    }

    @Test
    void creditStyleIsSmallMutedAndWrapsWithoutEllipsis() throws IOException {
        String css;
        try (InputStream input = getClass().getResourceAsStream("/static/css/travel-info-structured.css")) {
            assertThat(input).isNotNull();
            css = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        // 출처는 사진 아래 작은 한 줄: 오른쪽 정렬, 줄바꿈 없음, 배경·겹침 없음.
        assertThat(rule(css, ".structured-media-credit"))
                .contains("display: block;", "margin: 4px 0;", "font-size: 10px;", "line-height: 1.3;",
                        "text-align: right;", "white-space: nowrap;")
                .doesNotContain("position", "background", "border", "overflow-wrap");
        assertThat(rule(css, ".structured-media-credit + .structured-media-caption")).contains("padding-top: 4px;");
        assertThat(rule(css, ".structured-figure.is-portrait .structured-media-credit"))
                .contains("width: min(100%, 560px);", "margin-inline: auto;");
        assertThat(rule(css, ".structured-media-credit a")).contains("color: inherit;", "text-decoration: none;");
        // 캡션은 출처 기능 전 그대로다. 겹침 틀·첫 줄 grid 는 남아 있지 않다.
        assertThat(rule(css, ".structured-media-caption"))
                .contains("flex-direction: column;", "max-width: var(--structured-text-width);", "text-align: center;");
        assertThat(css).doesNotContain("structured-caption-primary", "has-credit", "structured-media-description",
                "structured-figure-media", "position: absolute");
        // 공개 상세와 관리자 상세 미리보기 모두 바뀐 CSS 를 새로 받는다.
        for (String template : java.util.List.of("/templates/travel-info/detail.html",
                "/templates/admin/travel-info/detail.html")) {
            try (InputStream input = getClass().getResourceAsStream(template)) {
                assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8)).as(template)
                        .contains("/css/travel-info-structured.css?v=20261014-rich-text-2");
            }
        }
    }

    /** 자식들의 class. (eachAttr 는 class 가 없는 요소를 건너뛰므로 자리를 맞추려고 className 을 쓴다) */
    private static java.util.List<String> classNames(Elements elements) {
        return elements.stream().map(Element::className).toList();
    }

    private static StructuredBlock.FullImage creditOnly(String id, StructuredImageCredit credit) {
        return new StructuredBlock.FullImage(id, credited(credit), null, null);
    }

    private static StructuredImage credited(StructuredImageCredit credit) {
        StructuredImage image = StructuredContentSamples.image(1, 1600, 1000);
        return new StructuredImage(image.url(), image.width(), image.height(), credit);
    }

    private static StructuredImageCredit credit(String author, String source, String sourceUrl,
                                                String license, String licenseUrl) {
        return new StructuredImageCredit(author, source, sourceUrl, license, licenseUrl);
    }

    private TravelInfoDetailDto structured(StructuredContent content) {
        TravelInfoDetailDto detail = base();
        detail.setContentFormat(TravelInfoContentFormat.STRUCTURED);
        detail.setContent("<p>DERIVED-SEARCH-HTML</p>");
        detail.setStructuredContent(content);
        return detail;
    }

    private TravelInfoDetailDto base() {
        TravelInfoDetailDto detail = new TravelInfoDetailDto();
        detail.setId(10L);
        detail.setTitle("서울 궁 투어");
        detail.setScope(TravelInfoScope.DOMESTIC);
        detail.setContentType(TravelInfoContentType.GENERAL);
        detail.setCategoryId(3L);
        detail.setCategoryName("계절여행");
        detail.setViews(7);
        detail.setCreatedAt(Timestamp.valueOf("2026-08-10 09:00:00"));
        return detail;
    }

    private Document render(TravelInfoDetailDto detail, String languageTag) throws Exception {
        return Jsoup.parse(renderHtml(detail, languageTag));
    }

    private String renderHtml(TravelInfoDetailDto detail, String languageTag) throws Exception {
        when(festivalDetailService.isPublicFestival(10L)).thenReturn(false);
        when(travelInfoService.getPublicDetail(10L)).thenReturn(detail);
        return mockMvc.perform(get("/travel-info/10")
                        .cookie(new Cookie(TripBoraLocaleResolver.COOKIE_NAME, languageTag)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
