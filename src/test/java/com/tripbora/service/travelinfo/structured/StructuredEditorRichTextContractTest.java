package com.tripbora.service.travelinfo.structured;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 관리자 일반 글 서식 툴바(admin-structured-editor.js)가 서버 허용 목록·공개 화면 CSS 와 같은 프리셋을 쓰는지 본다.
 * 화면에서만 꾸며지고 저장·상세에서 사라지는 일을 막는다.
 */
class StructuredEditorRichTextContractTest {

    @Test
    void sizeAndFontPresetsAreTheOnesTheServerKeeps() throws IOException {
        String script = resource("/static/js/admin-structured-editor.js");

        // 문단 스타일 3개, 글꼴 3개, 글자 크기 3단계만 있다. (숫자·글꼴 이름을 직접 넣는 칸은 없다)
        // 글자 크기는 문단 스타일이 아니라 고른 글자에만 붙는 따로 된 고르기 칸이다.
        assertThat(script)
                .contains("[['body', '본문'], ['h4', '소제목'], ['h3', '중제목']]")
                .contains("[['', '기본 글꼴'], ['rt-font-sans', '고딕'], ['rt-font-serif', '명조']]")
                .contains("[['rt-size-small', '작게'], ['', '기본 크기'], ['rt-size-large', '크게']]")
                .contains("picker('글자 크기', RICH_SIZES, value => applyTextPreset('size', value))")
                .doesNotContain("['small', '작은 글씨']", "type: 'color'", "font-size:", "style.fontFamily");
        // 에디터가 만드는 class 는 서버 정리를 그대로 지난다. (글꼴·글자 크기는 span, 예전 데이터의 p 크기도 남는다)
        for (String size : arrayValues(script, "RICH_SIZE_CLASSES")) {
            assertThat(StructuredRichTextHtml.sanitize("<p>앞 <span class=\"" + size + "\">글</span> 뒤</p>"))
                    .isEqualTo("<p>앞 <span class=\"" + size + "\">글</span> 뒤</p>");
            assertThat(StructuredRichTextHtml.sanitize("<p class=\"" + size + "\">글</p>"))
                    .isEqualTo("<p class=\"" + size + "\">글</p>");
        }
        for (String font : arrayValues(script, "RICH_FONT_CLASSES")) {
            assertThat(StructuredRichTextHtml.sanitize("<p><span class=\"" + font + "\">글</span></p>"))
                    .isEqualTo("<p><span class=\"" + font + "\">글</span></p>");
        }
        assertThat(arrayValues(script, "RICH_SIZE_CLASSES")).containsExactly("rt-size-small", "rt-size-large");
        assertThat(arrayValues(script, "RICH_FONT_CLASSES")).containsExactly("rt-font-sans", "rt-font-serif");
    }

    @Test
    void editorAndDetailPageDrawThePresetsTheSameWay() throws IOException {
        String editorCss = resource("/static/css/admin-structured-editor.css");
        String detailCss = resource("/static/css/travel-info-structured.css");

        for (String css : List.of(editorCss, detailCss)) {
            assertThat(css)
                    .contains("h3 {\n    font-size: 1.53em;")
                    .contains("h4 {\n    font-size: 1.3em;")
                    .contains(".rt-size-small {")
                    .contains("font-size: 0.82em;")
                    .contains(".rt-size-large {")
                    .contains("font-size: 1.18em;")
                    // 상세와 관리자 폼이 이미 불러오는 Noto Sans KR / Noto Serif KR 뒤에 언어별 시스템 글꼴을 둔다.
                    .contains(".rt-font-sans {\n    font-family: \"Noto Sans KR\"")
                    .contains("\"Hiragino Sans\"", "\"PingFang SC\"", "\"Microsoft JhengHei\", sans-serif;")
                    .contains(".rt-font-serif {\n    font-family: \"Noto Serif KR\"")
                    .contains("\"Hiragino Mincho ProN\"", "\"Songti SC\"", "PMingLiU, Georgia, serif;");
        }
    }

    @Test
    void pickersApplyTheChosenValueAndKeepTheSelection() throws IOException {
        String script = resource("/static/js/admin-structured-editor.js");

        // 고른 값은 선택을 되돌리기 전에 읽는다. (되돌릴 때 툴바가 지금 커서의 값으로 바뀌어 언제나 본문이 적용되던 문제)
        assertThat(script).contains("const value = event.target.value;\n"
                + "                    format(() => apply(value), true);");
        // 서식을 바꾼 뒤에는 같은 글자 자리를 다시 고른다. (문단을 통째로 고른 채 두지 않는다)
        assertThat(script)
                .contains("const positions = selectionPositions();\n                run();\n"
                        + "                selectPositions(positions);")
                .doesNotContain("selectBlocks(");
    }

    @Test
    void undoAndRedoCoverTheEditingAreaOnly() throws IOException {
        String script = resource("/static/js/admin-structured-editor.js");

        assertThat(script)
                .contains("historyButton('undo', '↶', '실행취소 (Ctrl+Z)', undo)")
                .contains("historyButton('redo', '↷', '다시실행 (Ctrl+Y)', redo)")
                .contains("event.inputType === 'historyUndo' || event.inputType === 'historyRedo'")
                .contains("if (pressed === 'z' && !event.shiftKey) {")
                .contains("} else if (pressed === 'y' || (pressed === 'z' && event.shiftKey)) {")
                // 서식 명령·붙여넣기·링크 적용은 바뀌기 직전 모양을 기록에 쌓는다.
                .contains("endTyping();\n                pushHistory();\n                const positions")
                .contains("richHistory: new Map()");
    }

    @Test
    void linksWithoutASchemeGetHttpsAndOtherSchemesAreRefused() throws IOException {
        String script = resource("/static/js/admin-structured-editor.js");

        assertThat(script)
                .contains("placeholder: 'example.com 또는 https://example.com'")
                .contains("text: '주소만 입력해도 https://가 자동으로 추가됩니다.'")
                .contains("if (!/^https?:\\/\\//i.test(raw)) {")
                .contains("if (/^[a-z][a-z0-9+.-]*:/i.test(raw) && !/^[^\\s/:]+:\\d+(?:[/?#]|$)/.test(raw)) return null;")
                .contains("url = `https://${raw.replace(/^\\/\\//, '')}`;")
                .contains("return isWebUrl(url) ? url : null;")
                .contains("const url = normalizeLinkUrl(linkInput.value);");
        // 서버도 http / https 가 아닌 링크는 글만 남긴다. (화면 보정을 건너뛴 값이 와도 같다)
        for (String href : List.of("javascript:alert(1)", "data:text/html,x", "file:///c:/x", "vbscript:x", "tripbora.com")) {
            assertThat(StructuredRichTextHtml.sanitize("<p><a href=\"" + href + "\">링크</a></p>"))
                    .as(href).isEqualTo("<p>링크</p>");
        }
    }

    private List<String> arrayValues(String script, String name) {
        Matcher matcher = Pattern.compile("const " + name + " = Object\\.freeze\\(\\[([^\\]]*)]\\);").matcher(script);
        assertThat(matcher.find()).as(name).isTrue();
        return Pattern.compile("'([^']+)'").matcher(matcher.group(1)).results()
                .map(result -> result.group(1))
                .toList();
    }

    private String resource(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
