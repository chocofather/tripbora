package com.tripbora.controller.destination;

import com.tripbora.dto.DestinationDto;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.thymeleaf.context.WebContext;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;
import org.thymeleaf.web.servlet.JakartaServletWebApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.tripbora.model.DestinationImage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공개 여행지 목록 카드 이미지. 실제 fragment.html 의 {@code <img>} 를 그대로 렌더한다.
 * 여행지 업로드 사진은 썸네일(srcset/sizes)과 원본 되돌림을, 외부 주소는 원래 주소만 쓴다. lazy loading 은 둘 다 유지한다.
 */
class DestinationListCardImageRenderingTest {

    /** DB 없이 실제 관리자 image/button/dialog 템플릿을 렌더해 Chrome 회귀 테스트에 제공한다. */
    @Test
    void exportsTheRenderedPositionEditorAndListCardsForBrowserChecks() throws IOException {
        String source = Files.readString(Path.of("src/main/resources/templates/admin/destinations/image-upload.html"));
        var admin = Jsoup.parse(source);
        String editor = "<article class='admin-destination-image-card'><div class='admin-destination-image-preview'>"
                + admin.selectFirst(".admin-destination-image-preview > img").outerHtml() + "</div>"
                + admin.selectFirst("[data-image-position-open]").outerHtml() + "</article>"
                + admin.selectFirst("#image-position-dialog").outerHtml();
        MockServletContext servlet = new MockServletContext();
        var context = new WebContext(JakartaServletWebApplication.buildApplication(servlet)
                .buildExchange(new MockHttpServletRequest(servlet), new MockHttpServletResponse()), Locale.KOREAN);
        DestinationImage image = new DestinationImage();
        image.setId(8L); image.setDestinationId(3L); image.setIsMain(true);
        image.setCardPositionX(50); image.setCardPositionY(70); image.setImageUrl("/fixture/portrait.svg");
        context.setVariable("img", image);
        context.setVariable("destinationId", 3L);
        context.setVariable("imageThumbnails", Map.of());
        String rendered = engine().process(editor, context);
        assertThat(Jsoup.parse(rendered).selectFirst("[data-image-position-open]").attr("data-position-url"))
                .isEqualTo("/admin/api/destinations/3/images/8/card-position");
        StringBuilder cards = new StringBuilder();
        for (String kind : new String[]{"portrait", "landscape", "square", "small", "none"}) {
            var dto = card(kind.equals("none") ? null : "/fixture/" + kind + ".svg");
            dto.setCardPositionY(70);
            var imageElement = render(dto);
            cards.append("<div class='destination-card-wrapper'><a href='/destinations/34' class='destination-card'>")
                    .append("<div class='destination-card-media'>")
                    .append(imageElement == null ? "" : imageElement.outerHtml())
                    .append("</div><div class='card-text-wrapper'><h3>검증 카드</h3></div></a>")
                    .append("<button class='bookmark-icon' type='button'>북마크</button></div>");
        }
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='_csrf' content='test-csrf'><meta name='_csrf_header' content='X-CSRF-TOKEN'>"
                + "<link rel='stylesheet' href='/css/style.css'><link rel='stylesheet' href='/css/destination.css'>"
                + "<link rel='stylesheet' href='/css/admin-layout.css'><link rel='stylesheet' href='/css/admin-destination-images.css'>"
                + "<link rel='stylesheet' href='/css/admin-mobile.css'>"
                + "<script src='/js/admin-destination-image-position.js' defer></script></head><body><main>"
                + "<div class='admin-shell'><aside class='admin-sidebar'></aside><div class='admin-main'>"
                + "<section class='admin-page-section'>" + rendered + "</section></div></div>"
                + "<div id='region-fragment-container'><div id='destination-list' class='destination-list-wrapper'>"
                + "<div class='destination-card-list'>" + cards + "</div></div></div></main></body></html>";
        Path output = Path.of("build/reports/destination-image-position");
        Files.createDirectories(output);
        Files.writeString(output.resolve("fixture.html"), html);
    }

    @Test
    void listPositionUsesTheSelectedImagesValuesAndDefaultsToCenter() throws IOException {
        DestinationDto centered = card("/uploads/destinations/center.jpg");
        assertThat(render(centered).attr("style"))
                .contains("--card-position-x: 50%", "--card-position-y: 50%");
        centered.setCardPositionX(0);
        centered.setCardPositionY(100);
        assertThat(render(centered).attr("style"))
                .contains("--card-position-x: 0%", "--card-position-y: 100%");
    }

    @Test
    void uploadedPhotosUseCardThumbnailsWithTheOriginalAsFallbackAndOtherAddressesStayAsTheyAre() throws IOException {
        DestinationDto uploaded = card("/uploads/destinations/c51f.jpg");
        uploaded.setCardImageUrl("/thumbnails/destinations/v2/480/c51f.jpg");
        uploaded.setCardImageSrcset("/thumbnails/destinations/v2/480/c51f.jpg 907w, "
                + "/thumbnails/destinations/v2/960/c51f.jpg 1814w");
        // 가로로 긴 사진: 4:3 칸을 채우려면 칸 폭의 2.18배로 그려진다.
        uploaded.setCardImageCoverScale(2.18);

        Element thumbnail = render(uploaded);
        assertThat(thumbnail.attr("src")).isEqualTo("/thumbnails/destinations/v2/480/c51f.jpg");
        assertThat(thumbnail.attr("srcset")).isEqualTo(uploaded.getCardImageSrcset());
        assertThat(thumbnail.attr("sizes")).isEqualTo("(max-width: 600px) calc((50vw - 22px) * 2.18), "
                + "(max-width: 820px) calc((33.333333vw - 18.666667px) * 2.18), "
                + "(max-width: 1024px) calc((50vw - 20px) * 2.18), (max-width: 1300px) calc((25vw - 28px) * 2.18), "
                + "calc(301px * 2.18)");
        assertThat(thumbnail.attr("data-original-src")).isEqualTo("/uploads/destinations/c51f.jpg");
        assertThat(thumbnail.attr("onerror")).contains("this.dataset.originalSrc");
        assertThat(thumbnail.attr("loading")).isEqualTo("lazy");

        Element external = render(card("https://tong.visitkorea.or.kr/cms/resource/a.jpg"));
        assertThat(external.attr("src")).isEqualTo("https://tong.visitkorea.or.kr/cms/resource/a.jpg");
        assertThat(external.hasAttr("srcset")).isFalse();
        assertThat(external.hasAttr("sizes")).isFalse();
        assertThat(external.hasAttr("data-original-src")).isFalse();
        assertThat(external.attr("loading")).isEqualTo("lazy");
    }

    private static DestinationDto card(String thumbnailPath) {
        DestinationDto card = new DestinationDto();
        card.setId(34L);
        card.setName("롯데호텔");
        card.setThumbnailPath(thumbnailPath);
        return card;
    }

    private Element render(DestinationDto card) throws IOException {
        MockServletContext servletContext = new MockServletContext();
        WebContext context = new WebContext(JakartaServletWebApplication.buildApplication(servletContext)
                .buildExchange(new MockHttpServletRequest(servletContext), new MockHttpServletResponse()),
                Locale.KOREAN);
        context.setVariable("d", card);
        return Jsoup.parse(engine().process(cardImageTemplate(), context)).selectFirst("img");
    }

    /** fragment.html 의 목록 카드 {@code <img ... />} 한 개. */
    private String cardImageTemplate() throws IOException {
        String fragment;
        try (InputStream input = getClass().getResourceAsStream("/templates/destination/fragment.html")) {
            assertThat(input).isNotNull();
            fragment = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        Matcher image = Pattern.compile("<img th:if=\"\\$\\{d\\.thumbnailPath != null}\".*?/>", Pattern.DOTALL)
                .matcher(fragment);
        assertThat(image.find()).isTrue();
        return image.group();
    }

    private static SpringTemplateEngine engine() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setTemplateEngineMessageSource(messageSource);
        return engine;
    }
}
