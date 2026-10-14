package com.tripbora.service.travelinfo.structured;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 일반 글(RICH_TEXT)의 제한형 서식(html). 허용 목록만 남는지, 예전 일반 글·번역과 함께 읽히는지 본다.
 */
class StructuredRichTextTest {

    private final StructuredContentParser parser =
            new StructuredContentParser(new StructuredContentValidator());
    private final StructuredContentSerializer serializer = new StructuredContentSerializer();
    private final StructuredContentLocalizer localizer = new StructuredContentLocalizer();

    @Test
    void plainTextBlocksStayPlain() {
        StructuredContent content = parser.parseContent("""
                {"version":1,"blocks":[{"type":"RICH_TEXT","id":"t","text":"경복궁은 조선 왕조의\\n\\n법궁입니다."}]}""");

        StructuredBlock.RichText block = (StructuredBlock.RichText) content.blocks().get(0);
        assertThat(block.html()).isNull();
        assertThat(block.text()).isEqualTo("경복궁은 조선 왕조의\n\n법궁입니다.");
        assertThat(serializer.write(content)).doesNotContain("html");
    }

    @Test
    void presetFormattingSurvivesAndTextIsDerivedFromIt() {
        String html = "<h3><span class=\"rt-font-serif\">근정전</span></h3>"
                + "<p class=\"rt-size-large\"><strong>굵게</strong> <em>기울임</em> <u>밑줄</u> "
                + "<mark>형광</mark> <mark class=\"rt-point\">포인트</mark></p>"
                + "<p class=\"rt-size-small\">작은 글씨<br>둘째 줄</p>"
                + "<h4>소제목</h4>"
                + "<ul><li>하나</li><li><strong><span class=\"rt-font-sans\">둘</span></strong></li></ul>"
                + "<ol><li>첫째</li></ol>"
                + "<blockquote>인용문</blockquote>"
                + "<p><a href=\"https://www.royalpalace.go.kr/\">공식 홈페이지</a></p>";

        StructuredBlock.RichText block = new StructuredBlock.RichText("t", null, "무시되는 글", html);

        assertThat(block.html()).isEqualTo("<h3><span class=\"rt-font-serif\">근정전</span></h3>"
                + "<p class=\"rt-size-large\"><strong>굵게</strong> <em>기울임</em> <u>밑줄</u> "
                + "<mark>형광</mark> <mark class=\"rt-point\">포인트</mark></p>"
                + "<p class=\"rt-size-small\">작은 글씨<br>둘째 줄</p>"
                + "<h4>소제목</h4>"
                + "<ul><li>하나</li><li><strong><span class=\"rt-font-sans\">둘</span></strong></li></ul>"
                + "<ol><li>첫째</li></ol>"
                + "<blockquote>인용문</blockquote>"
                + "<p><a href=\"https://www.royalpalace.go.kr/\" rel=\"noopener noreferrer nofollow\" "
                + "target=\"_blank\">공식 홈페이지</a></p>");
        assertThat(block.text()).isEqualTo("근정전\n\n굵게 기울임 밑줄 형광 포인트\n\n작은 글씨\n둘째 줄\n\n소제목"
                + "\n\n하나\n둘\n\n첫째\n\n인용문\n\n공식 홈페이지");
        // 다시 만들어도 같은 값이다. (DB → model → JSON 을 거듭해도 바뀌지 않는다)
        assertThat(new StructuredBlock.RichText("t", null, null, block.html())).isEqualTo(block);
    }

    @Test
    void scriptsEventHandlersStylesAndUnknownClassesAreRemoved() {
        String html = "<script>alert(1)</script><style>p{color:red}</style>"
                + "<p class=\"evil rt-size-small\" style=\"color:red\" onclick=\"alert(1)\" id=\"x\">"
                + "<img src=x onerror=alert(1)>안녕<iframe src=\"https://example.com\"></iframe></p>"
                + "<p><a href=\"javascript:alert(1)\">위험</a> <a href=\"/relative\">상대</a> "
                + "<a href=\"mailto:a@b.c\">메일</a></p>"
                + "<div>div 는 문단</div><b>b</b><i>i</i><span style=\"font-size:40px\">글자</span>"
                + "<h1>큰 제목</h1><mark class=\"rt-size-small\">mark</mark><p><br></p>";

        String safe = StructuredRichTextHtml.sanitize(html);

        assertThat(safe).doesNotContain("script", "style", "onclick", "onerror", "iframe", "img",
                "javascript", "evil", "id=", "href=\"/relative\"", "mailto", "span", "h1");
        assertThat(safe).isEqualTo("<p class=\"rt-size-small\">안녕</p>"
                + "<p>위험 상대 메일</p>"
                + "<p>div 는 문단</p>"
                + "<p><strong>b</strong><em>i</em>글자큰 제목<mark>mark</mark></p>");
    }

    /** 글꼴은 고른 글자를 감싼 span 의 프리셋 class 만 남는다. 임의 글꼴·style·문단 글꼴 class 는 지운다. */
    @Test
    void fontPresetsSurviveOnlyAsSpanClasses() {
        String safe = StructuredRichTextHtml.sanitize("<p class=\"rt-font-serif rt-size-large\">"
                + "기본 <span class=\"rt-font-serif\">명조</span> <span class=\"rt-font-sans x\">고딕</span> "
                + "<span class=\"rt-font-comic\" style=\"font-family: Comic Sans\">임의</span> "
                + "<span style=\"font-size: 40px\">크기</span><span class=\"rt-font-serif\"></span></p>"
                + "<h3 class=\"rt-size-large\">제목</h3>");

        assertThat(safe).isEqualTo("<p class=\"rt-size-large\">기본 <span class=\"rt-font-serif\">명조</span> "
                + "<span class=\"rt-font-sans\">고딕</span> 임의 크기</p><h3>제목</h3>");
    }

    /** 글자 크기는 고른 글자의 span 에만 남아 같은 문단의 다른 글은 본문 크기 그대로다. 굵게·링크·글꼴과 같이 써도 된다. */
    @Test
    void sizePresetsSurviveOnSelectedTextOnly() {
        String safe = StructuredRichTextHtml.sanitize("<p><span class=\"rt-size-large\">1395년에 창건한 법궁이다.</span>"
                + " 경복궁은 <strong><span class=\"rt-size-large\">백악산</span></strong>을 주산으로 "
                + "<a href=\"https://example.com\"><span class=\"rt-size-small x\" style=\"font-size: 40px\">링크</span></a> "
                + "<span class=\"rt-size-large rt-font-serif\">명조</span> <span class=\"rt-size-huge\">임의</span></p>");

        assertThat(safe).isEqualTo("<p><span class=\"rt-size-large\">1395년에 창건한 법궁이다.</span>"
                + " 경복궁은 <strong><span class=\"rt-size-large\">백악산</span></strong>을 주산으로 "
                + "<a href=\"https://example.com\" rel=\"noopener noreferrer nofollow\" target=\"_blank\">"
                + "<span class=\"rt-size-small\">링크</span></a> "
                + "<span class=\"rt-font-serif rt-size-large\">명조</span> 임의</p>");
    }

    @Test
    void emptyFormattingIsNoContent() {
        assertThat(StructuredRichTextHtml.sanitize("<p><br></p><p>  </p><script>x</script>")).isNull();
        assertThat(StructuredRichTextHtml.sanitize(null)).isNull();
        assertThatThrownBy(() -> parser.parseContent("""
                {"version":1,"blocks":[{"type":"RICH_TEXT","id":"t","html":"<p><br></p>"}]}"""))
                .isInstanceOf(StructuredContentValidationException.class)
                .hasMessageContaining("본문을");
    }

    @Test
    void formattedBlockRoundTripsThroughJson() {
        StructuredContent content = parser.parseContent("""
                {"version":1,"blocks":[{"type":"RICH_TEXT","id":"t","layout":"FOCUSED",
                 "html":"<p><strong>굵게</strong></p><p onclick=\\"x()\\">둘째</p>"}]}""");

        String json = serializer.write(content);

        assertThat(json).contains("\"text\":\"굵게\\n\\n둘째\"")
                .contains("\"html\":\"<p><strong>굵게</strong></p><p>둘째</p>\"")
                .doesNotContain("onclick");
        assertThat(parser.parseContent(json)).isEqualTo(content);
    }

    @Test
    void tooMuchMarkupIsRejected() {
        String html = "<p>" + "<strong>가</strong>".repeat(2000) + "</p>";

        assertThatThrownBy(() -> new StructuredContentValidator().validate(new StructuredContent(1,
                List.of(new StructuredBlock.RichText("t", null, null, html)))))
                .isInstanceOf(StructuredContentValidationException.class)
                .hasMessageContaining("서식");
    }

    @Test
    void translationsMayCarryFormattingOrPlainText() {
        StructuredContent base = new StructuredContent(1, List.of(
                new StructuredBlock.RichText("formatted", null, null, "<p><strong>원문</strong></p>"),
                new StructuredBlock.RichText("plain-override", null, null, "<p><em>원문</em></p>"),
                new StructuredBlock.RichText("untranslated", null, null, "<p><u>원문</u></p>"),
                new StructuredBlock.Callout("callout", "원문 강조")));
        StructuredText text = parser.parseText("""
                {"blocks":{
                  "formatted":{"html":"<p><strong>Original</strong><script>x</script></p>"},
                  "plain-override":{"text":"Plain translation"},
                  "callout":{"text":"Callout","html":"<p>무시</p>"}
                }}""");

        StructuredText aligned = localizer.alignToBase(base, text);
        StructuredContent localized = localizer.apply(base, aligned);

        StructuredBlock.RichText formatted = (StructuredBlock.RichText) localized.blocks().get(0);
        assertThat(formatted.html()).isEqualTo("<p><strong>Original</strong></p>");
        assertThat(formatted.text()).isEqualTo("Original");
        // 번역이 일반 글이면 원문 서식(한국어)을 남기지 않고 번역 글을 그대로 쓴다.
        StructuredBlock.RichText plain = (StructuredBlock.RichText) localized.blocks().get(1);
        assertThat(plain.html()).isNull();
        assertThat(plain.text()).isEqualTo("Plain translation");
        assertThat(localized.blocks().get(2)).isEqualTo(base.blocks().get(2));
        // 강조 문구에는 서식이 없다. 번역 html 은 저장하지 않는다.
        assertThat(aligned.blocks().get("callout")).isEqualTo(
                new StructuredText.BlockText(null, "Callout", null, null, Map.of()));
        assertThat(serializer.write(aligned)).contains("\"html\":\"<p><strong>Original</strong></p>\"");
    }
}
