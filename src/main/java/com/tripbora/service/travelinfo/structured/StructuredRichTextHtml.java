package com.tripbora.service.travelinfo.structured;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 일반 글(RICH_TEXT) 블록의 제한형 서식 HTML.
 *
 * <p>워드프로세서가 아니라 정해진 프리셋만 받는다. 남는 것은 아래뿐이다.
 * <ul>
 *   <li>문단: p, h3(중제목), h4(소제목), ul / ol / li, blockquote</li>
 *   <li>글자: strong, em, u, mark(형광 강조, {@code rt-point} 면 포인트 강조), span(글꼴·글자 크기), a, br</li>
 *   <li>class: 정해진 프리셋 이름만 (span 의 글꼴 {@code rt-font-*}·글자 크기 {@code rt-size-*},
 *       예전 데이터의 p 글자 크기 {@code rt-size-*}, mark 의 {@code rt-point}). 프리셋이 없는 span 은 벗긴다.</li>
 *   <li>링크: http / https 주소만. 새 창으로 열고 rel 은 서버가 정한다.</li>
 * </ul>
 * style·id·이벤트 속성·script·iframe 같은 그 밖의 모든 것은 지운다(태그만 지우고 글은 남긴다).
 *
 * <p>{@link StructuredBlock.RichText} 와 번역 {@link StructuredText.BlockText} 가 만들어질 때마다 이 정리를 거친다.
 * 그래서 관리자가 보낸 값이든 DB 에서 읽은 값이든 화면에 그릴 때는 언제나 정리된 HTML 이다.
 */
final class StructuredRichTextHtml {

    private static final Set<String> BLOCK_TAGS = Set.of("p", "h3", "h4", "ul", "ol", "blockquote");

    /**
     * 태그마다 남길 수 있는 class 프리셋. 여기에 없는 class 는 지운다.
     * 글꼴·글자 크기는 고른 글자에만 씌우는 span 단위다. p 의 글자 크기는 예전에 문단 전체에 붙여 저장한 값을 위해 남긴다.
     */
    private static final Map<String, Set<String>> ALLOWED_CLASSES = Map.of(
            "p", Set.of("rt-size-small", "rt-size-large"),
            "span", Set.of("rt-font-sans", "rt-font-serif", "rt-size-small", "rt-size-large"),
            "mark", Set.of("rt-point"));

    private static final Safelist SAFELIST = new Safelist()
            .addTags("p", "br", "strong", "em", "u", "mark", "span", "a", "ul", "ol", "li", "blockquote", "h3", "h4")
            .addAttributes("p", "class")
            .addAttributes("span", "class")
            .addAttributes("mark", "class")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "http", "https")
            .addEnforcedAttribute("a", "target", "_blank")
            .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow");

    private StructuredRichTextHtml() {
    }

    /**
     * 허용한 태그·class·링크만 남긴 HTML. 글이 하나도 남지 않으면 null 이다.
     * b / i 는 같은 뜻의 strong / em 으로, div 는 p 로 바꾼 뒤 정리한다. (브라우저 편집기가 만드는 모양)
     */
    static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document dirty = Jsoup.parseBodyFragment(html);
        dirty.body().select("b").tagName("strong");
        dirty.body().select("i").tagName("em");
        dirty.body().select("div").tagName("p");
        Document clean = new Cleaner(SAFELIST).clean(dirty);
        clean.outputSettings().prettyPrint(false);
        Element body = clean.body();
        body.select("[class]").forEach(StructuredRichTextHtml::keepAllowedClasses);
        // http / https 가 아니라 주소가 지워진 링크, 글꼴·글자 크기 프리셋이 없는 span 은 글만 남긴다.
        body.select("a:not([href])").forEach(Element::unwrap);
        body.select("span:not([class])").forEach(Element::unwrap);
        // 목록 밖의 li, li 안의 문단은 브라우저 편집 중에 생길 수 있다. 모양을 단순하게 맞춘다.
        body.select("li").forEach(item -> {
            Element parent = item.parent();
            if (parent == null || !(parent.nameIs("ul") || parent.nameIs("ol"))) {
                item.tagName("p");
            }
        });
        body.select("li p, li h3, li h4, li blockquote, li ul, li ol").forEach(Element::unwrap);
        // 인용 안의 문단들은 줄바꿈으로 이어 한 덩어리로 둔다.
        body.select("blockquote").forEach(quote -> {
            List<Element> nested = quote.children().stream()
                    .filter(child -> child.nameIs("p") || child.nameIs("h3") || child.nameIs("h4"))
                    .toList();
            for (int index = 0; index < nested.size(); index++) {
                if (index < nested.size() - 1) {
                    nested.get(index).after(new Element("br"));
                }
                nested.get(index).unwrap();
            }
        });
        // 문단 안에 다시 들어간 문단은 풀어서 한 단계로 둔다.
        body.select("p p, p h3, p h4, h3 p, h4 p, p ul, p ol, p blockquote").forEach(Element::unwrap);
        wrapLooseInline(body);
        // 글이 없는 문단·목록은 지운다. (빈 줄로 여백을 만드는 일을 막는다)
        body.select("p, h3, h4, li, blockquote").forEach(element -> {
            if (element.text().isBlank()) {
                element.remove();
            }
        });
        body.select("ul, ol").forEach(list -> {
            if (list.children().isEmpty()) {
                list.remove();
            }
        });
        body.select("strong, em, u, mark, span, a").forEach(element -> {
            if (element.text().isEmpty()) {
                element.remove();
            }
        });
        if (body.text().isBlank()) {
            return null;
        }
        return body.html();
    }

    /**
     * 정리된 HTML 의 글만. 문단은 빈 줄, 문단 안의 br 은 줄바꿈, 목록 항목은 한 줄씩이다.
     * 통합검색·SEO 요약·길이 검사가 서식 없는 일반 글과 같은 모양으로 읽는다.
     */
    static String plainText(String safeHtml) {
        if (safeHtml == null || safeHtml.isBlank()) {
            return null;
        }
        Element body = Jsoup.parseBodyFragment(safeHtml).body();
        List<String> paragraphs = new ArrayList<>();
        for (Element block : body.children()) {
            if (block.nameIs("ul") || block.nameIs("ol")) {
                String items = block.children().stream()
                        .map(StructuredRichTextHtml::lineText)
                        .filter(line -> !line.isBlank())
                        .collect(Collectors.joining("\n"));
                if (!items.isBlank()) {
                    paragraphs.add(items);
                }
            } else {
                String text = lineText(block);
                if (!text.isBlank()) {
                    paragraphs.add(text);
                }
            }
        }
        String text = String.join("\n\n", paragraphs);
        return text.isBlank() ? null : text;
    }

    /** br 을 줄바꿈으로 남긴 한 덩어리의 글. */
    private static String lineText(Element element) {
        StringBuilder text = new StringBuilder();
        appendText(element, text);
        return Arrays.stream(text.toString().split("\n", -1))
                .map(String::strip)
                .collect(Collectors.joining("\n"))
                .strip();
    }

    private static void appendText(Node node, StringBuilder text) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode textNode) {
                text.append(textNode.text());
            } else if (child instanceof Element element) {
                if (element.nameIs("br")) {
                    text.append('\n');
                } else {
                    appendText(element, text);
                }
            }
        }
    }

    private static void keepAllowedClasses(Element element) {
        Set<String> allowed = ALLOWED_CLASSES.getOrDefault(element.normalName(), Set.of());
        List<String> kept = element.classNames().stream()
                .filter(allowed::contains)
                .sorted()
                .toList();
        if (kept.isEmpty()) {
            element.removeAttr("class");
        } else {
            element.attr("class", String.join(" ", kept));
        }
    }

    /** 문단 밖에 바로 놓인 글·글자 서식은 이어진 것끼리 p 하나로 묶는다. */
    private static void wrapLooseInline(Element body) {
        List<Node> pending = new ArrayList<>();
        for (Node child : new ArrayList<>(body.childNodes())) {
            boolean block = child instanceof Element element && BLOCK_TAGS.contains(element.normalName());
            if (block) {
                wrap(pending);
            } else {
                pending.add(child);
            }
        }
        wrap(pending);
    }

    private static void wrap(List<Node> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        boolean hasText = nodes.stream().anyMatch(node ->
                node instanceof Element || (node instanceof TextNode textNode && !textNode.isBlank()));
        if (hasText) {
            Element paragraph = new Element("p");
            nodes.get(0).before(paragraph);
            nodes.forEach(node -> paragraph.appendChild(node));
        } else {
            nodes.forEach(Node::remove);
        }
        nodes.clear();
    }
}
