package com.tripbora.repository;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationDescriptionUiContractTest {

    @Test
    void mobileDetailScopeIncludesDynamicSectionsAndKeepsExistingInteractionHooks() throws IOException {
        Document detail = Jsoup.parse(resource("/templates/destination/detail.html"));
        Element scope = detail.selectFirst(".destination-detail-page");
        assertThat(scope).isNotNull();
        assertThat(scope.select(".destination-hero, .detail-container, .comment-section, .similar-destinations"))
                .hasSize(4);
        assertThat(scope.select("#comment-form, #comment-list, #guest-comment-box, #bookmark-container, #map"))
                .hasSize(5);
        assertThat(scope.select(".carousel .nav")).hasSize(2);
        assertThat(scope.select("[data-comment-sort]")).hasSize(4);
        assertThat(scope.select("#destination-image-modal, #image-modal")).hasSize(2);
        assertThat(scope.select(".photo-review-section, #photo-modal, .view-all")).isEmpty();
        assertThat(detail.select("link[rel=stylesheet]").last().attr("th:href"))
                .isEqualTo("@{/css/destination-detail-mobile.css}");
        // 공통 레이아웃에는 로드하지 않아 다른 페이지 댓글과 헤더에 영향을 주지 않는다.
        assertThat(resource("/templates/layout/main.html"))
                .doesNotContain("destination-detail-mobile.css", "destination-detail-page");
    }

    @Test
    void publicDescriptionUsesEscapedParagraphsWithoutChangingItsContainerWidth() throws IOException {
        Document detail = Jsoup.parse(resource("/templates/destination/detail.html"));
        Element description = detail.selectFirst(".destination-description");
        String css = resource("/static/css/detail.css");

        assertThat(description).isNotNull();
        assertThat(description.select("p")).hasSize(1);
        assertThat(description.selectFirst("p").attr("th:each")).isEqualTo("paragraph : ${descriptionParagraphs}");
        assertThat(description.selectFirst("span").attr("th:text")).isEqualTo("${paragraph}");
        assertThat(description.select("[th\\:utext]")).isEmpty();
        assertThat(detail.select(".destination-short-description")).isEmpty();
        assertThat(resource("/templates/destination/detail.html"))
                .doesNotContain("currentLanguage", "currentLanguageTag", "locale ==");

        int descriptionCssStart = css.indexOf(".destination-description");
        int descriptionCssEnd = css.indexOf("/* 메타 정보 */", descriptionCssStart);
        String descriptionCss = css.substring(descriptionCssStart, descriptionCssEnd);
        assertThat(descriptionCss)
                .contains("line-height: 1.72;")
                .contains("margin-top: 0.9em;")
                .doesNotContain("max-width")
                .doesNotContain("white-space: pre-line")
                .doesNotContain("word-break: keep-all");
    }

    @Test
    void createAndEditFormsKeepTextareasAndOnlyShowTheParagraphHelp() throws IOException {
        for (String formPath : new String[]{
                "/templates/admin/destinations/create.html",
                "/templates/admin/destinations/edit.html"}) {
            String form = resource(formPath);
            Document page = Jsoup.parse(form);

            // 한국어 원본 1개. 번역 쪽 상세 설명은 공통 번역 탭 조각이 언어마다 그린다.
            assertThat(page.select("textarea.is-description")).hasSize(1);
            assertThat(page.text()).contains("문단을 나누려면 한 줄을 비워주세요.");
            assertThat(page.select("[data-description-preview], [data-description-preview-toggle], [data-description-preview-content]")).isEmpty();
            assertThat(form).doesNotContain("admin-destination-description-preview.js");
        }
        assertThat(Jsoup.parse(resource(
                "/templates/admin/destinations/fragments/translation-tabs.html"))
                .select("textarea.is-description")).hasSize(1);
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
