package com.tripbora.repository;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CourseTranslationUiContractTest {

    @Test
    void detailUsesTheSharedOnDemandTitleAndRichTextToggle() throws IOException {
        String detail = resource("/templates/course/detail.html");
        String script = resource("/static/js/user-post-translation.js");

        assertThat(detail)
                .contains("/js/user-post-translation.js")
                .contains("th:if=\"${course.translationAvailable and course.courseKind != 'OFFICIAL' and adminPreview != true}\"")
                .contains("data-title-content-translation")
                .contains("data-translation-title")
                .contains("data-translation-content")
                .contains("@{/course/{id}/translation(id=${course.id})}");
        assertThat(script)
                .contains("[data-title-content-translation][data-translation-url]")
                .contains("fetch(article.dataset.translationUrl")
                .contains("new DOMParser()")
                .contains("content.replaceChildren")
                .doesNotContain("content.innerHTML =");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
