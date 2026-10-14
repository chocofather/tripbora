package com.tripbora.service.travelinfo.structured;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 블록 에디터(admin-structured-editor.js)가 미리 알려 주는 한도가 서버 검증과 같은지 본다.
 * 서버가 최종 기준이지만, 둘이 어긋나면 화면은 통과시키고 서버가 거절하는 일이 생긴다.
 */
class StructuredEditorLimitsContractTest {

    @Test
    void editorLimitsMatchTheServerValidator() throws IOException {
        String script = resource("/static/js/admin-structured-editor.js");
        Map<String, Integer> expected = Map.ofEntries(
                Map.entry("blocks", StructuredContentValidator.MAX_BLOCKS),
                Map.entry("sliderItems", StructuredContentValidator.MAX_SLIDER_ITEMS),
                Map.entry("title", StructuredContentValidator.TextField.TITLE.maxLength()),
                Map.entry("richText", StructuredContentValidator.TextField.RICH_TEXT.maxLength()),
                Map.entry("richTextHtml", StructuredContentValidator.MAX_RICH_TEXT_HTML_LENGTH),
                Map.entry("imageText", StructuredContentValidator.TextField.IMAGE_TEXT.maxLength()),
                Map.entry("caption", StructuredContentValidator.TextField.CAPTION.maxLength()),
                Map.entry("alt", StructuredContentValidator.TextField.ALT.maxLength()),
                Map.entry("itemTitle", StructuredContentValidator.TextField.ITEM_TITLE.maxLength()),
                Map.entry("callout", StructuredContentValidator.TextField.CALLOUT.maxLength()),
                // 이미지 출처표시(credit)
                Map.entry("creditAuthor", StructuredContentValidator.TextField.CREDIT_AUTHOR.maxLength()),
                Map.entry("creditSource", StructuredContentValidator.TextField.CREDIT_SOURCE.maxLength()),
                Map.entry("creditSourceUrl", StructuredContentValidator.TextField.CREDIT_SOURCE_URL.maxLength()),
                Map.entry("creditLicense", StructuredContentValidator.TextField.CREDIT_LICENSE.maxLength()),
                Map.entry("creditLicenseUrl", StructuredContentValidator.TextField.CREDIT_LICENSE_URL.maxLength()));

        expected.forEach((name, value) -> {
            Matcher matcher = Pattern.compile("\\b" + name + ": (\\d+)").matcher(script);
            assertThat(matcher.find()).as("LIMITS.%s", name).isTrue();
            assertThat(Integer.parseInt(matcher.group(1))).as("LIMITS.%s", name).isEqualTo(value);
        });
        // 서버 id 규칙과 같은 모양(접두어 + 영숫자)으로 만들고, 이미지는 전용 업로드 경로만 받는다.
        assertThat(script)
                .contains("`${prefix}-${randomPart()}`")
                .contains("const IMAGE_URL_PREFIX = '" + StructuredImage.URL_PREFIX + "';");
        // 섹션 제목은 제목만 쓰고, 일반 글의 본문 폭은 서버 enum 과 같은 두 프리셋뿐이다.
        assertThat(script)
                .doesNotContain("block.lead", "label: '짧은 소개'")
                .contains("choices: [['DEFAULT', '기본'], ['FOCUSED', '집중형']]");
        assertThat(StructuredBlock.RichTextLayout.values()).extracting(Enum::name)
                .containsExactly("DEFAULT", "FOCUSED");
        // 이미지 배치: 추가 메뉴는 2장 / 3장 두 가지이고, 칸 수는 서버가 받는 값과 같다.
        assertThat(StructuredContentValidator.GRID_COLUMNS).containsExactlyInAnyOrder(2, 3);
        assertThat(script)
                .contains("const GRID_COLUMNS = Object.freeze([2, 3]);")
                .contains("{label: '이미지 2장 배치', type: 'IMAGE_GRID', columns: 2}")
                .contains("{label: '이미지 3장 배치', type: 'IMAGE_GRID', columns: 3}");
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
