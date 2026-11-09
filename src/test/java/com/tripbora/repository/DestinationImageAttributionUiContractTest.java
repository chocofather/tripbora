package com.tripbora.repository;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationImageAttributionUiContractTest {

    @Test
    void detailOnlyCreatesTheQuietCaptionWhenAnyImageHasAttribution() throws IOException {
        String source = resource("/templates/destination/detail.html");
        Document page = Jsoup.parse(source);

        assertThat(page.select("[data-destination-image-attribution]")).hasSize(1);
        assertThat(source)
                .contains("th:if=\"${hasImageAttribution}\"")
                .contains("data-source-name=${img.travelerPhoto ?")
                .contains(": img.sourceName}")
                .contains("data-license-label=${img.licenseDisplay}")
                .contains("data-photographer=${img.photographer}")
                .contains("data-license-url=${img.safeLicenseUrl}")
                .contains("data-credit=${img.customAttribution}")
                // Commons 사진은 사진별 파일 페이지, 그 외 사진은 기존 safeSourceUrl 과 같은 값이다.
                .contains("data-source-url=${img.attributionUrl}");
        assertThat(page.select("[data-destination-image-source-link][target=_blank]"
                + "[rel='noopener noreferrer']")).hasSize(1);
        assertThat(page.select("[data-destination-image-license-link][target=_blank]"
                + "[rel='noopener noreferrer license']")).hasSize(1);
        assertThat(page.select("[data-destination-image-credit]")).hasSize(1);
    }

    @Test
    void carouselMovementUpdatesSourceLicenseAndSafeLinkTogether() throws IOException {
        String script = resource("/static/js/detail-carousel.js");
        String modalScript = resource("/static/js/destination-image-modal.js");

        assertThat(script)
                .contains("function updateAttribution()")
                .contains("slide?.dataset.sourceName")
                .contains("slide?.dataset.licenseLabel")
                .contains("slide?.dataset.photographer")
                .contains("slide?.dataset.sourceUrl")
                .contains("$source.text(sourceName)")
                .contains("$license.text(licenseLabel)")
                .contains("$photographer.text(photographer)")
                .contains("$sourceLink.attr('href', sourceUrl)")
                .contains("$licenseLink.attr('href', licenseUrl)")
                .contains("$credit.text(credit)")
                .contains("updateAttribution();")
                .contains("destination-gallery-change")
                .contains("move(0);");
        assertThat(modalScript)
                .contains("new CustomEvent('destination-gallery-change'")
                .contains("detail: {index: currentIndex}");
    }

    @Test
    void missingCoverIsNotRenderedAndTheModalCaptionFollowsTheCurrentSlide() throws IOException {
        String source = resource("/templates/destination/detail.html");
        Document page = Jsoup.parse(source);
        String modalScript = resource("/static/js/destination-image-modal.js");

        // 대표 이미지 주소가 없으면 깨진 <img> 를 만들지 않는다.
        assertThat(page.selectFirst(".carousel.no-image").attr("th:if"))
                .contains("!#strings.isEmpty(destination.thumbnailPath)");
        // 모달 캡션은 숨긴 채 시작하고, 지금 보이는 슬라이드의 저장된 출처 data-* 로 채운다.
        assertThat(page.select("#destination-image-modal figcaption#destination-image-modal-caption[hidden]"))
                .hasSize(1);
        assertThat(modalScript)
                .contains("renderCaption(source);")
                .contains("source.closest('.slide')?.dataset")
                .contains("caption.hidden = true;")
                // 주소는 있지만 받지 못한 사진은 깨진 아이콘 대신 숨긴다.
                .contains("document.addEventListener('error'")
                .contains("hideBrokenImage(target)");
    }

    @Test
    void attributionStyleStaysSmallUnboxedAndCloseToTheImage() throws IOException {
        String css = resource("/static/css/detail.css");
        String rule = css.substring(css.indexOf(".destination-image-attribution {"),
                css.indexOf("}", css.indexOf(".destination-image-attribution {")) + 1);

        assertThat(rule)
                .contains("font-size: 11px")
                .contains("color: #9299a6")
                .contains("margin: 6px 2px 0")
                .doesNotContain("background", "border", "padding");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
