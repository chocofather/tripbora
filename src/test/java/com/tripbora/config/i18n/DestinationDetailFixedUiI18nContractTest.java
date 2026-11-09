package com.tripbora.config.i18n;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class DestinationDetailFixedUiI18nContractTest {

    private static final String[] BUNDLES = {
            "/messages.properties",
            "/messages_ko.properties",
            "/messages_en.properties",
            "/messages_ja.properties",
            "/messages_zh_CN.properties",
            "/messages_zh_TW.properties"
    };

    @Test
    void sectionHeadingsAndTypeInformationLabelsUseDestinationDetailMessages() throws IOException {
        Document detail = Jsoup.parse(resource("/templates/destination/detail.html"));

        assertThat(detail.select("div.detail-container h2, div.detail-container dt.info-item-label, "
                + "div.detail-container .info-note-label, div.detail-container .amenities-title"))
                .isNotEmpty()
                .allSatisfy(element -> assertThat(element.attr("th:text"))
                        .as(element.cssSelector())
                        .startsWith("#{destination.detail."));
        assertThat(detail.select("main.detail-container")).isEmpty();

        assertThat(detail.selectFirst(".info h2").attr("th:text"))
                .isEqualTo("#{destination.detail.section.introduction}");
        assertThat(detail.selectFirst(".attraction-info h2").attr("th:text"))
                .isEqualTo("#{destination.detail.section.attraction}");
        assertThat(detail.selectFirst("section.map h2").attr("th:text"))
                .isEqualTo("#{destination.detail.section.map}");
    }

    @Test
    void metadataGalleryCommentsAndRecommendationsUseMessagesWithoutReplacingDbBindings()
            throws IOException {
        String source = resource("/templates/destination/detail.html");
        Document detail = Jsoup.parse(source);

        assertThat(detail.getAllElements().stream()
                .filter(element -> !localizedAttribute(element).isEmpty()))
                .anySatisfy(element -> assertThat(localizedAttribute(element))
                        .contains("destination.detail."));
        assertThat(source)
                .contains("#{destination.detail.meta.saveToDiary}")
                .contains("#{destination.detail.gallery.previous}")
                .contains("#{destination.detail.comment.sort.latest}")
                .contains("#{destination.detail.similar.withRegion(${regionName})}")
                .contains("${destination.name}", "${paragraph}", "${regionPath}",
                        "${categoryName}", "${regionName}")
                .contains("${attractionInfo.closedDays ?: '-'}")
                .contains("${attractionInfo.openingHours ?: '-'}")
                .contains("th:attr=\"data-tooltip=${a.name}\"")
                .contains("th:alt=\"${a.name}\"");
    }

    @Test
    void galleryDialogsAndCloseControlsExposeLocalizedAccessibleNames() throws IOException {
        Document detail = Jsoup.parse(resource("/templates/destination/detail.html"));

        for (String selector : new String[]{"#image-modal"}) {
            Element dialog = detail.selectFirst(selector);
            assertThat(dialog).as(selector).isNotNull();
            assertThat(dialog.attr("role")).isEqualTo("dialog");
            assertThat(dialog.attr("aria-modal")).isEqualTo("true");
            assertThat(dialog.attr("th:aria-label"))
                    .startsWith("#{destination.detail.gallery.");
        }

        for (String selector : new String[]{"#image-modal .close-btn"}) {
            Element closeButton = detail.selectFirst(selector);
            assertThat(closeButton).as(selector).isNotNull();
            assertThat(closeButton.tagName()).isEqualTo("button");
            assertThat(closeButton.attr("type")).isEqualTo("button");
            assertThat(closeButton.attr("th:aria-label"))
                    .isEqualTo("#{destination.detail.gallery.close}");
        }
    }

    @Test
    void destinationDetailJavascriptReadsRenderedMessagesInsteadOfHardcodingKoreanUi()
            throws IOException {
        String detail = resource("/templates/destination/detail.html");
        String imageModal = resource("/static/js/destination-image-modal.js");
        String commentInit = resource("/static/js/comment/init.js");
        String commentRender = resource("/static/js/comment/render.js");
        String commentEvents = resource("/static/js/comment/events.js");

        assertThat(detail).contains("id=\"destination-detail-i18n\"");
        assertThat(imageModal).contains("destination-detail-i18n", ".dataset");
        assertThat(commentInit).contains("detailMessage(");
        assertThat(commentRender).contains("detailMessage(");
        assertThat(commentEvents).contains("detailMessage(");
        assertThat(commentInit).doesNotContain("'불러오는 중…'", "'댓글 더보기'");
        assertThat(commentRender).doesNotContain("'관리자에 의해 조치된 댓글입니다.'",
                "'알 수 없음'", ">좋아요<", ">답글<", ">수정<", ">삭제<");
        assertThat(commentEvents).doesNotContain("'댓글을 삭제하시겠습니까?'",
                "'삭제 권한 없음'", ">저장<", ">취소<", "답글을 입력하세요");
    }

    @Test
    void everyBundleContainsAllDestinationDetailKeysAndRepresentativeTranslationsResolve()
            throws IOException {
        Properties fallback = properties(BUNDLES[0]);
        var detailKeys = fallback.stringPropertyNames().stream()
                .filter(key -> key.startsWith("destination.detail."))
                .toList();

        assertThat(detailKeys).isNotEmpty();
        for (String bundle : BUNDLES) {
            assertThat(properties(bundle).stringPropertyNames())
                    .as("destination detail keys in %s", bundle)
                    .containsAll(detailKeys);
        }

        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);

        assertThat(message(messages, "destination.detail.section.introduction", "en"))
                .isEqualTo("Introduction");
        assertThat(message(messages, "destination.detail.section.attraction", "ja"))
                .isEqualTo("観光スポット情報");
        assertThat(message(messages, "destination.detail.attraction.hours", "zh-CN"))
                .isEqualTo("开放时间");
        assertThat(message(messages, "destination.detail.attraction.hours", "zh-TW"))
                .isEqualTo("開放時間");
        assertThat(message(messages, "destination.detail.info.officialWebsite", "en"))
                .isEqualTo("Official Website");
        assertThat(message(messages, "destination.detail.value.available", "ko"))
                .isEqualTo("가능");
        assertThat(message(messages, "destination.detail.value.unavailable", "en"))
                .isEqualTo("Unavailable");
        assertThat(message(messages, "destination.detail.value.available", "ja"))
                .isEqualTo("利用可");
        assertThat(message(messages, "destination.detail.value.unavailable", "zh-CN"))
                .isEqualTo("不可用");
        assertThat(message(messages, "destination.detail.value.available", "zh-TW"))
                .isEqualTo("可使用");
    }

    private String localizedAttribute(Element element) {
        for (String attribute : new String[]{"th:alt", "th:aria-label", "th:placeholder", "th:title"}) {
            if (element.hasAttr(attribute)) return element.attr(attribute);
        }
        return "";
    }

    private String message(ResourceBundleMessageSource messages, String key, String languageTag) {
        return messages.getMessage(key, null, Locale.forLanguageTag(languageTag));
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Properties properties(String path) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as("resource %s", path).isNotNull();
            properties.load(new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
