package com.tripbora.repository;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CourseTimelineUiContractTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void mapLinkUsesCentralizedOutboundUrlWithoutInlineMapAssets() throws IOException {
        var fragment = Jsoup.parse(resource("templates/course/fragments/item-map.html"));
        var trigger = fragment.selectFirst("a.course-map-link");
        assertThat(trigger).isNotNull();
        assertThat(trigger.text()).isEqualTo("지도에서 보기 ↗");
        assertThat(trigger.attr("target")).isEqualTo("_blank");
        assertThat(trigger.attr("rel")).isEqualTo("noopener noreferrer");
        assertThat(trigger.attr("th:href")).isEqualTo("${stop.googleMapsUrl}");
        assertThat(trigger.hasAttr("aria-expanded")).isFalse();
        assertThat(fragment.select("button, .course-inline-map, .course-location-canvas, iframe")).isEmpty();
        assertThat(fragment.selectFirst("[th:fragment]").attr("th:if"))
                .isEqualTo("${stop.googleMapsUrl != null}");
        String css = resource("static/css/course-detail.css");
        int start = css.indexOf(".course-detail-container .course-map-link {");
        assertThat(start).isNotNegative();
        String triggerRules = css.substring(start, css.indexOf('}', start));
        assertThat(triggerRules).contains("border: 0", "background: transparent", "text-decoration: underline")
                .doesNotContain("border: 1px", "min-height: 36px");
        assertThat(css).contains(".course-detail-container .course-map-link:focus-visible");
        assertThat(resource("templates/course/detail.html"))
                .doesNotContain("/js/map-init.js", "/js/course-location.js", "courseGoogleMapsApiKey", "data-course-map-config");
        assertThat(resource("static/js/course-detail.js")).doesNotContain("CourseLocation", "kakao", "relayoutVisible");
    }

    @Test
    void dayFilterEnhancesReadableDaysAndHeroUsesTheExistingRepresentativeImage() throws IOException {
        var document = Jsoup.parse(resource("templates/course/detail.html"));
        var nav = document.selectFirst("[data-course-day-nav]");
        assertThat(nav).isNotNull();
        assertThat(nav.hasAttr("hidden")).isTrue();
        assertThat(nav.select("button[data-course-day-filter=all][aria-pressed=true]")).hasSize(1);
        assertThat(nav.select("button").stream().filter(button -> button.hasAttr("th:each"))).hasSize(1);
        var day = document.selectFirst("section.course-day-route");
        assertThat(day.hasAttr("hidden")).isFalse();
        assertThat(day.attr("th:data-course-day")).isEqualTo("${day.dayNumber}");
        var hero = document.selectFirst("[data-course-hero]");
        assertThat(hero.attr("th:if")).contains("course.representativeImageUrl", "'/images/default.png'");
        assertThat(hero.selectFirst("img").attr("th:src")).isEqualTo("${course.representativeImageUrl}");
        assertThat(document.select(".course-breadcrumb a")).hasSize(1);
        assertThat(resource("static/css/course-detail.css"))
                .contains(".course-detail-container .course-hero-image", "object-fit: cover", "object-position: center")
                .contains(".course-detail-container .course-day-route[hidden]");
    }

    @Test
    void multiDayRouteKeepsDayOrderAndEscapesUserTitlesAndMemos() throws IOException {
        String template = resource("templates/course/detail.html");

        assertThat(template)
                .contains("th:each=\"day : ${course.days}\"")
                .contains("th:each=\"stop : ${day.items}\"")
                .contains("th:text=\"${day.title}\"")
                .contains("th:text=\"${stop.memo}\"")
                .contains("th:text=\"${stop.startTime}\"")
                .doesNotContain("stop.endTime")
                .contains("stop.itemType == 'STAY'", "stop.itemType == 'FOOD'")
                .doesNotContain("th:utext=\"${day.title}\"", "th:utext=\"${stop.memo}\"");
    }

    @Test
    void memoCanExpandWithoutJavascriptAndKeepsDestinationLinksSeparate() throws IOException {
        var document = Jsoup.parse(resource("templates/course/detail.html"));
        var row = document.selectFirst(".course-day-route .course-stop-card");
        assertThat(row.tagName()).isEqualTo("div");
        assertThat(row.select("a.course-stop-image-wrap")).hasSize(1);
        assertThat(row.select("a.course-stop-title-link")).hasSize(1);
        var memo = row.selectFirst("details.course-item-memo");
        assertThat(memo).isNotNull();
        assertThat(memo.selectFirst("summary span").attr("th:text")).isEqualTo("${stop.memo}");
        assertThat(memo.parents().stream().anyMatch(parent -> parent.tagName().equals("a"))).isFalse();
        assertThat(resource("static/css/course-detail.css"))
                .contains(".course-item-memo[open] > summary > span", "-webkit-line-clamp: 2")
                .contains("-webkit-line-clamp: unset");
    }

    @Test
    void templateKeepsStopDomOrderAndAllExistingCardInformation() throws IOException {
        String template = resource("templates/course/detail.html");

        assertThat(template)
                .contains("th:each=\"stop : ${course.stops}\"")
                .contains("class=\"course-stop\"")
                .contains("th:text=\"${stop.visitOrder}\"")
                .doesNotContain("class=\"course-stop-order\"")
                .contains("@{/destinations/{id}(id=${stop.destinationId})}")
                .contains("stop.imageUrl")
                .contains("'/images/default.png'")
                .contains("th:text=\"${stop.name}\"")
                .contains("stop.shortDescription")
                .contains("th:text=\"${stop.locationLabel}\"")
                .doesNotContain("stopStat")
                .doesNotContain("course-stop--left")
                .doesNotContain("course-stop--right")
                .doesNotContain("reverse(");
    }

    @Test
    void ownerActionsKeepTheirPermissionAndDeleteContractInTheHeader() throws IOException {
        var document = Jsoup.parse(resource("templates/course/detail.html"));
        var menu = document.selectFirst(".course-summary-header .course-owner-menu");

        assertThat(menu).isNotNull();
        assertThat(menu.attr("th:if")).isEqualTo("${course.myCourse}");
        assertThat(menu.selectFirst("summary")).isNotNull();
        assertThat(menu.selectFirst("a.course-edit-button").attr("th:href"))
                .isEqualTo("@{/course/{id}/edit(id=${course.id})}");
        var deleteForm = menu.selectFirst("form.course-delete-form");
        assertThat(deleteForm.attr("method")).isEqualTo("post");
        assertThat(deleteForm.attr("th:action"))
                .isEqualTo("@{/course/{id}/delete(id=${course.id})}");
        assertThat(deleteForm.attr("th:data-confirm")).isEqualTo("#{course.detail.deleteConfirm}");
        assertThat(document.select(".course-detail-footer .course-edit-button, "
                + ".course-detail-footer .course-delete-form")).isEmpty();
        assertThat(document.select(".course-summary-label .course-stop-count")).hasSize(1);
    }

    @Test
    void everyViewportUsesCompactRowsInNaturalStopOrder() throws IOException {
        String css = resource("static/css/course-detail.css");

        assertThat(css)
                .contains(".course-detail-container .course-route .course-timeline")
                .contains("flex-direction: column")
                .contains("grid-template-columns: 120px minmax(0, 1fr)")
                .contains("--route-marker-size: 24px")
                .doesNotContain("nth-of-type", "grid-auto-flow: row dense", "repeat(3,")
                .doesNotContain("flex-direction: row-reverse")
                .doesNotContain("\n    order:");
    }

    @Test
    void straightConnectorsStopAtLastRowAndSurviveSharedMobileRules() throws IOException {
        String css = resource("static/css/course-detail.css");

        assertThat(css)
                .contains(".course-detail-container .course-route .course-timeline > .course-stop:not(:last-child)::before")
                .contains("display: block", "top: var(--route-marker-size)", "bottom: 0", "width: 1px")
                .contains("background: var(--route-line-color)")
                .contains(".course-detail-container .course-route .course-timeline > .course-stop:last-child::before")
                .doesNotContain(".course-stop::after", "--route-turn-offset");
    }

    @Test
    void mobileKeepsCompactThumbnailsAndShrinkableTextColumns() throws IOException {
        String css = resource("static/css/course-detail.css");
        int mobileStart = css.indexOf("@media (max-width: 600px)");
        String mobile = css.substring(mobileStart);

        assertThat(mobileStart).isNotNegative();
        assertThat(mobile)
                .contains(".course-detail-container .course-route .course-stop-card")
                .contains("grid-template-columns: 80px minmax(0, 1fr)")
                .contains("width: 80px; height: 68px")
                .doesNotContain("linear-gradient")
                .contains("minmax(0, 1fr)");
        assertThat(css).contains("min-width: 0", "overflow-wrap: anywhere");
    }

    private String resource(String relativePath) throws IOException {
        return Files.readString(RESOURCES.resolve(relativePath), StandardCharsets.UTF_8);
    }
}
