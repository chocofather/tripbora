package com.tripbora.config.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코스 상세의 고정 문구가 메시지에서 나오는지, 사용자가 쓴 글은 그대로인지 본다.
 */
class CourseDetailI18nContractTest {

    private static final String[] BUNDLES = {
            "/messages.properties",
            "/messages_ko.properties",
            "/messages_en.properties",
            "/messages_ja.properties",
            "/messages_zh_CN.properties",
            "/messages_zh_TW.properties"
    };

    @Test
    void fixedScreenTextComesFromMessages() throws IOException {
        String detail = resource("/templates/course/detail.html");

        assertThat(detail)
                .contains("#{course.detail.label}")
                .contains("#{course.detail.meta.writer}")
                .contains("#{course.detail.meta.createdAt}")
                .contains("#{course.detail.meta.updatedAt}")
                .contains("#{course.detail.meta.views}")
                .contains("#{course.detail.section.introduction}")
                .contains("#{course.detail.stopCount(${course.stops.size()})}")
                .contains("#{course.detail.route.title}")
                .contains("#{course.detail.route.allDays}")
                .contains("#{course.detail.route.empty}")
                .contains("#{course.detail.stop.region}")
                .contains("#{course.detail.stop.imageAlt(${stop.name})}")
                .contains("#{course.detail.backToList}")
                .contains("#{course.detail.edit}")
                .contains("#{course.detail.delete}")
                .contains("th:data-confirm=\"#{course.detail.deleteConfirm}\"")
                .contains("#{course.detail.comment.heading}")
                .contains("#{course.detail.comment.sortLabel}")
                .contains("#{course.detail.comment.sort.latest}")
                .contains("#{course.detail.comment.sort.oldest}")
                .contains("#{course.detail.comment.sort.likes}")
                .contains("#{course.detail.comment.placeholder}")
                .contains("#{course.detail.comment.submit}")
                .contains("#{course.detail.comment.loadMore}")
                .contains("#{destination.detail.comment.loginPrompt}")
                .contains("#{course.detail.comment.image.close}")
                .contains("#{course.detail.comment.image.previous}")
                .contains("#{course.detail.comment.image.next}")
                .contains("#{course.detail.comment.image.enlargedAlt}")
                .contains("#{destination.detail.comment.translate}")
                .contains("#{destination.detail.comment.original}")
                .contains("#{destination.detail.comment.translating}");
        // 삭제 확인은 화면이 내려준 문구를 쓰고, 화면 안에 한국어를 박아 두지 않는다.
        assertThat(detail).doesNotContain("confirm('이 여행 코스를 삭제하시겠습니까?')");
        // 비로그인 안내는 textarea 안에서만 보여주고 별도 링크 문구는 만들지 않는다.
        assertThat(detail).doesNotContain("#{course.detail.comment.loginPrompt(${loginUrl})}");
    }

    @Test
    void userWrittenContentStaysBoundToTheDatabaseValues() throws IOException {
        String detail = resource("/templates/course/detail.html");

        assertThat(detail)
                .contains("th:text=\"${course.title}\"")
                .contains("th:utext=\"${course.content}\"")
                .contains("th:text=\"${course.nickname}\"")
                .contains("th:text=\"${stop.name}\"")
                .contains("th:text=\"${stop.locationLabel}\"");
    }

    @Test
    void courseCommentScriptReadsRenderedMessagesInsteadOfHardcodingKoreanUi() throws IOException {
        String detail = resource("/templates/course/detail.html");
        String script = resource("/static/js/course-comments.js");

        assertThat(detail).contains("id=\"course-detail-i18n\"");
        assertThat(script)
                .contains("document.getElementById('course-detail-i18n')")
                .contains("detailMessage(")
                .doesNotContain("'첫 댓글을 작성해 보세요.'", "'댓글 더보기'", "'불러오는 중…'",
                        "'좋아요'", "'답글'", "'수정'", "'삭제'", "'저장'", "'취소'", "'등록'",
                        "'댓글을 삭제하시겠습니까?'", "'로그인이 필요합니다.'");
    }

    @Test
    void everyBundleContainsCourseAndStandaloneNavigationKeys() throws IOException {
        Properties fallback = properties(BUNDLES[0]);
        var courseKeys = fallback.stringPropertyNames().stream()
                .filter(key -> key.startsWith("course.") || key.equals("nav.courses"))
                .toList();

        assertThat(courseKeys).isNotEmpty();
        for (String bundle : BUNDLES) {
            assertThat(properties(bundle).stringPropertyNames())
                    .as("course section keys in %s", bundle)
                    .containsAll(courseKeys);
        }
    }

    @Test
    void representativeCourseDetailMessagesResolveForEverySupportedLocale() {
        ResourceBundleMessageSource messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messages.setFallbackToSystemLocale(false);

        assertThat(message(messages, "course.detail.route.title", "ko")).isEqualTo("여행 일정");
        assertThat(message(messages, "course.detail.route.title", "en")).isEqualTo("Itinerary");
        assertThat(message(messages, "course.detail.route.title", "ja")).isEqualTo("旅行日程");
        assertThat(message(messages, "course.detail.route.title", "zh-CN")).isEqualTo("旅行行程");
        assertThat(message(messages, "course.detail.route.title", "zh-TW")).isEqualTo("旅遊行程");
        assertThat(message(messages, "course.detail.route.allDays", "ko")).isEqualTo("전체 일정");
        assertThat(message(messages, "course.detail.route.allDays", "en")).isEqualTo("Full itinerary");
        assertThat(message(messages, "course.detail.route.allDays", "ja")).isEqualTo("全日程");
        assertThat(message(messages, "course.detail.route.allDays", "zh-CN")).isEqualTo("全部行程");
        assertThat(message(messages, "course.detail.route.allDays", "zh-TW")).isEqualTo("全部行程");

        // STOP 카드의 "지역" 라벨
        assertThat(message(messages, "course.detail.stop.region", "ko")).isEqualTo("지역");
        assertThat(message(messages, "course.detail.stop.region", "en")).isEqualTo("Area");
        assertThat(message(messages, "course.detail.stop.region", "ja")).isEqualTo("地域");
        assertThat(message(messages, "course.detail.stop.region", "zh-CN")).isEqualTo("地区");
        assertThat(message(messages, "course.detail.stop.region", "zh-TW")).isEqualTo("地區");

        assertThat(messages.getMessage("course.detail.stopCount", new Object[]{3},
                Locale.forLanguageTag("en"))).isEqualTo("3 destinations in total");
        assertThat(messages.getMessage("course.detail.comment.imageLimit", new Object[]{3},
                Locale.forLanguageTag("ko"))).isEqualTo("사진은 최대 3장까지 첨부할 수 있습니다.");
        assertThat(messages.getMessage("course.detail.comment.profileAlt", new Object[]{"minjun"},
                Locale.forLanguageTag("en"))).isEqualTo("Profile image of minjun");
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
