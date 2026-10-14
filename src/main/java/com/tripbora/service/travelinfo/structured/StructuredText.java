package com.tripbora.service.travelinfo.structured;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * {@code travel_info_translations.structured_text} 의 구조. 한 언어의 글만 블록 id 로 덮어쓴다.
 *
 * <pre>
 * { "blocks": { "blockId": { "title": "...", "items": { "itemId": { "caption": "..." } } } } }
 * </pre>
 *
 * <p>이미지(url / width / height), 블록 종류, 순서, 위치는 여기에 들어올 수 없다. 그런 키는 읽는 단계에서
 * 모르는 필드로 거부된다. 비어 있거나 없는 값은 "덮어쓰지 않음" 이다. (원문 값을 그대로 쓴다)
 * 원문에 없는 블록 id 를 정리하는 일은 원문과 합치는 단계에서 한다.
 */
public record StructuredText(Map<String, BlockText> blocks) {

    public static final StructuredText EMPTY = new StructuredText(Map.of());

    public StructuredText {
        blocks = blocks == null ? Map.of() : Map.copyOf(blocks);
    }

    /**
     * 한 블록의 번역 글. 블록 종류마다 쓰는 칸만 채운다.
     * 섹션 제목의 짧은 소개(lead)는 없어졌다. 예전 번역 JSON 의 lead 키는 읽고 버린다. (다시 저장하면 남지 않는다)
     *
     * <p>html 은 일반 글(RICH_TEXT) 번역의 서식 본문이다. 원문 {@link StructuredBlock.RichText} 와 같은 허용 목록으로
     * 만들어질 때마다 정리한다. 일반 글 번역에 html 이 있으면 text 보다 html 을 쓴다.
     * (다른 블록 종류에는 서식이 없어 원문과 합치는 단계에서 html 을 뺀다)
     */
    @JsonIgnoreProperties({"lead"})
    public record BlockText(String title, String text, String caption, String alt,
                            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, ItemText> items,
                            String html) {
        public BlockText {
            title = StructuredJson.blankToNull(title);
            text = StructuredJson.blankToNull(text);
            html = StructuredRichTextHtml.sanitize(html);
            caption = StructuredJson.blankToNull(caption);
            alt = StructuredJson.blankToNull(alt);
            items = items == null ? Map.of() : Map.copyOf(items);
        }

        /** 서식 없는 번역 글. */
        public BlockText(String title, String text, String caption, String alt, Map<String, ItemText> items) {
            this(title, text, caption, alt, items, null);
        }

        /**
         * 덮어쓸 글이 하나라도 있다. Jackson 이 속성으로 읽지 않는 이름이라
         * 입력 JSON 에 같은 이름의 키가 와도 모르는 필드로 거부된다.
         */
        public boolean hasOverrides() {
            return title != null || text != null || caption != null || alt != null
                    || html != null || !items.isEmpty();
        }
    }

    /** 슬라이더 이미지 한 장의 번역 글. */
    public record ItemText(String title, String caption, String alt) {
        public ItemText {
            title = StructuredJson.blankToNull(title);
            caption = StructuredJson.blankToNull(caption);
            alt = StructuredJson.blankToNull(alt);
        }

        /** 덮어쓸 글이 하나라도 있다. */
        public boolean hasOverrides() {
            return title != null || caption != null || alt != null;
        }
    }
}
