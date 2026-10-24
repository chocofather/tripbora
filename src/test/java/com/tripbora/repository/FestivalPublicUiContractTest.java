package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class FestivalPublicUiContractTest {

    @Test
    void festivalDetailUsesPublicLayoutLocalHeroImageStructuredInfoAndSafeAttribution() throws IOException {
        String template = resource("/templates/festivals/detail.html");

        assertThat(template)
                .contains("layout/main :: layout")
                .contains("/css/quill-content.css", "/css/festival-detail.css")
                .contains("festival-detail-hero", "festival-detail-title", "festival-detail-summary")
                .contains("th:src=\"@{${image.imageUrl}}\"")
                .contains("대표이미지가 없습니다")
                .contains("행사 소개", "festival-detail-content rich-text-content")
                .contains("th:utext=\"${festival.travelInfo.content}\"")
                .contains("행사 정보", "festival-detail-info-list")
                .contains("festival.primaryPeriod", "festival.eventPlace", "festival.address")
                .contains("festival.playTime", "festival.useTime")
                .contains("festival.sponsor1", "festival.sponsor2", "festival.contactTel")
                .contains("공식 홈페이지", "target=\"_blank\"", "rel=\"noopener noreferrer\"")
                // 출처 문구는 messages 로, 라이선스는 코드 → messages 로 바뀐다.
                .contains("#{travelInfo.festival.image.source}")
                .contains("${firstImage.sourceName}")
                .contains("travelInfo.festival.image.license.")
                .contains("festival.galleryImages[0].licenseCode")
                .doesNotContain("festival.mainImage.sourceImageUrl", "source_image_url");
    }

    @Test
    void festivalCssKeepsHeroTextInfoAndAttributionResponsiveWithoutHeavyEffects() throws IOException {
        String css = resource("/static/css/festival-detail.css");

        assertThat(css)
                .contains(".festival-detail-gallery-open img")
                .contains("object-fit: contain")
                .contains("overflow-wrap: anywhere")
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                // 데스크톱 / 태블릿 / 모바일 세 구간을 모두 잡는다.
                .contains("@media (max-width: 1100px)", "@media (max-width: 900px)")
                .contains("@media (max-width: 767px)")
                .contains("@media (max-width: 520px)")
                .contains("grid-template-columns: minmax(0, 1fr)")
                .contains(".festival-detail-content pre")
                .contains("overflow-x: auto")
                .contains(".festival-detail-content [class*=\"ql-indent-\"]")
                .contains("padding-left: min(3em, 12vw)")
                .contains("max-width: 100%")
                .doesNotContain("linear-gradient", "animation:", "transition:", "box-shadow:");
    }

    @Test
    void festivalHeroUsesWiderCentredColumnAndBodyKeepsItsExistingWidth()
            throws IOException {
        String template = resource("/templates/festivals/detail.html");
        String css = resource("/static/css/festival-detail.css");

        // 상단은 이미지 비중을 높이고 소개·행사정보는 기존 본문 폭을 유지한다.
        assertThat(template).doesNotContain("festival-detail-body");
        assertThat(css)
                .contains("width: min(1240px, calc(100% - 40px))")
                .contains("max-width: 1080px")
                .doesNotContain(".festival-detail-body")
                // 소개 본문은 섹션 폭을 그대로 쓴다.
                .contains(".festival-detail-content {")
                .contains("line-height: 1.8")
                .contains(".festival-detail-content p + p")
                .doesNotContain("max-width: 74ch", "max-width: 760px");
        // 섹션 구분은 여백과 얇은 선이 맡고, 카드로 감싸지 않는다.
        assertThat(css)
                .contains(".festival-detail-section {")
                .contains(".festival-detail-section h2")
                .contains("border-bottom: 2px solid var(--festival-border)")
                .doesNotContain(".festival-detail-section h2::after")
                .doesNotContain("--festival-card-shadow", "--festival-radius");
    }

    @Test
    void festivalInformationStaysOneGridInsteadOfPerItemCards() throws IOException {
        String template = resource("/templates/festivals/detail.html");
        String css = resource("/static/css/festival-detail.css");

        // 강조 카드를 따로 만들지 않는다. 홈페이지도 같은 흐름 안의 링크다.
        assertThat(template).doesNotContain("is-highlight");
        assertThat(css)
                .contains(".festival-detail-info-row {")
                // 항목 구분은 얇은 divider 하나뿐이다.
                .contains("border-top: 1px solid var(--festival-border-soft)")
                .doesNotContain(".festival-detail-info-row.is-highlight")
                .doesNotContain(".festival-detail-info-row dt::before")
                // 홈페이지는 버튼이 아니라 텍스트 링크다.
                .contains(".festival-detail-homepage")
                .doesNotContain("border-radius: 999px;\n    background: #fff;");
    }

    @Test
    void festivalInformationUsesTwoColumnsOnDesktopAndOneColumnOnMobile() throws IOException {
        String template = resource("/templates/festivals/detail.html");
        String css = resource("/static/css/festival-detail.css");

        assertThat(template)
                .contains("festival-detail-info-row is-wide")
                .doesNotContain("festival-detail-kicker");
        assertThat(css)
                .contains("grid-template-columns: repeat(2, minmax(0, 1fr))")
                .contains(".festival-detail-info-row.is-wide")
                .contains("grid-column: 1 / -1")
                .contains("@media (max-width: 767px)")
                .contains("grid-template-columns: minmax(0, 1fr)")
                .contains("grid-column: auto")
                .contains("overflow-wrap: anywhere");
    }

    @Test
    void mobileFestivalHeaderStacksLongTitleAndKeepsControlsTouchFriendly() throws IOException {
        String css = resource("/static/css/festival-detail.css");

        assertThat(css)
                .containsPattern("(?s)@media \\(max-width: 520px\\).*"
                        + "\\.festival-detail-title-row \\{\\s*"
                        + "grid-template-columns: minmax\\(0, 1fr\\);")
                .containsPattern("(?s)@media \\(max-width: 520px\\).*"
                        + "\\.festival-detail-bookmark \\{[^}]*justify-self: start;")
                .containsPattern("(?s)@media \\(max-width: 520px\\).*"
                        + "\\.festival-detail-bookmark \\{[^}]*min-height: 44px;")
                .containsPattern("(?s)@media \\(max-width: 520px\\).*"
                        + "\\.festival-detail-gallery-nav \\{[^}]*"
                        + "width: 44px;[^}]*height: 44px;")
                .containsPattern("(?s)@media \\(max-width: 520px\\).*"
                        + "\\.festival-detail-back \\{[^}]*min-height: 44px;");
    }

    @Test
    void festivalGalleryReusesDialogNavigationPatternAndSupportsKeyboardControls() throws IOException {
        String template = resource("/templates/festivals/detail.html");
        String script = resource("/static/js/festival-gallery.js");

        assertThat(template)
                .contains("data-festival-gallery", "data-festival-gallery-slide")
                .contains("data-festival-gallery-prev", "data-festival-gallery-next")
                .contains("festival-image-modal", "<dialog")
                .contains("data-license-label", "festival-detail-attribution")
                .doesNotContain("sourceImageUrl", "source_image_url");
        assertThat(script)
                .contains("ArrowLeft", "ArrowRight", "Escape")
                .contains("showModal", "currentIndex")
                .contains("(currentIndex + step + slides.length) % slides.length")
                .contains("updateAttribution", "updateCounter");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
