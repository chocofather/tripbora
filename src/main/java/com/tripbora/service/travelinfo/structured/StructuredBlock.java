package com.tripbora.service.travelinfo.structured;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

/**
 * 구조화 콘텐츠 블록 하나. JSON 의 {@code type} 으로 아래 record 중 하나가 정해진다.
 *
 * <p>하위 형식은 여기 등록한 이름만 받는다. 등록하지 않은 type, type 이 없는 블록은 읽는 단계에서 거부한다.
 * 블록에는 내용(글·이미지)과 정해진 프리셋(이미지 위치, 본문 폭)만 있고 margin·width 같은 디자인 숫자는 없다.
 * 모양은 공개 화면 템플릿이 정한다.
 *
 * <p>값이 비었는지·길이 같은 규칙은 {@link StructuredContentValidator} 가 본다.
 * 그래서 record 는 JSON 에 없던 글 값을 null 그대로 둔다.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = StructuredBlock.SectionTitle.class, name = "SECTION_TITLE"),
        @JsonSubTypes.Type(value = StructuredBlock.RichText.class, name = "RICH_TEXT"),
        @JsonSubTypes.Type(value = StructuredBlock.FullImage.class, name = "FULL_IMAGE"),
        @JsonSubTypes.Type(value = StructuredBlock.ImageText.class, name = "IMAGE_TEXT"),
        @JsonSubTypes.Type(value = StructuredBlock.ImageSlider.class, name = "IMAGE_SLIDER"),
        @JsonSubTypes.Type(value = StructuredBlock.ImageGrid.class, name = "IMAGE_GRID"),
        @JsonSubTypes.Type(value = StructuredBlock.Callout.class, name = "CALLOUT")
})
public sealed interface StructuredBlock {

    /** 문서 안에서 겹치지 않는 블록 식별자. 번역(structured_text)과 화면 id 가 이 값으로 블록을 찾는다. */
    String id();

    StructuredBlockType type();

    /**
     * 이 블록이 쓰는 이미지. 파일 정리(수정·삭제 후 지울 파일 찾기)가 이 목록만 본다.
     * 새 블록 종류를 더할 때 빠뜨리지 않도록 기본 구현을 두지 않는다.
     * (Jackson 속성 이름 규칙에 맞지 않아 JSON 에는 쓰이지 않는다)
     */
    List<StructuredImage> images();

    /**
     * 글 안의 소제목. 제목만 있다.
     * 예전에는 짧은 소개(lead)도 받았다. 그렇게 저장된 JSON 도 읽을 수 있게 lead 키만 콕 집어 읽고 버린다.
     * (다른 모르는 키는 여전히 거부한다) 그래서 다시 저장하면 lead 는 남지 않는다.
     */
    @JsonIgnoreProperties({"lead"})
    record SectionTitle(String id, String title) implements StructuredBlock {
        public SectionTitle {
            title = StructuredJson.blankToNull(title);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.SECTION_TITLE;
        }

        @Override
        public List<StructuredImage> images() {
            return List.of();
        }
    }

    /**
     * 일반 글 본문. layout 은 본문 폭 프리셋이다. 예전 JSON 처럼 값이 없으면 기본(DEFAULT)으로 본다.
     *
     * <p>서식이 없으면 text 만 있다. text 는 HTML 이 아니며 줄바꿈과 빈 줄(문단)만 의미가 있다.
     * 서식(문단 스타일·굵게·강조·링크·목록·인용·글꼴 프리셋)을 쓰면 html 에 제한형 HTML 이 들어간다.
     * html 은 만들어질 때마다 {@link StructuredRichTextHtml} 이 허용 목록대로 정리하고,
     * 그때 text 는 html 의 글로 다시 만든다. (통합검색·요약·길이 검사는 언제나 text 를 본다)
     */
    record RichText(String id, RichTextLayout layout, String text, String html) implements StructuredBlock {
        public RichText {
            layout = layout == null ? RichTextLayout.DEFAULT : layout;
            html = StructuredRichTextHtml.sanitize(html);
            text = html != null ? StructuredRichTextHtml.plainText(html) : StructuredJson.blankToNull(text);
        }

        /** 서식 없는 본문. */
        public RichText(String id, RichTextLayout layout, String text) {
            this(id, layout, text, null);
        }

        /** 기본 폭 본문. */
        public RichText(String id, String text) {
            this(id, RichTextLayout.DEFAULT, text);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.RICH_TEXT;
        }

        /** 화면용 문단. 빈 줄로 나누고, 문단 안의 줄바꿈은 그대로 둔다. (CSS 가 줄을 바꿔 보여 준다) */
        public List<String> paragraphs() {
            return paragraphsOf(text);
        }

        @Override
        public List<StructuredImage> images() {
            return List.of();
        }
    }

    /** 큰 이미지 한 장. */
    record FullImage(String id, StructuredImage image, String alt, String caption)
            implements StructuredBlock {
        public FullImage {
            alt = StructuredJson.blankToNull(alt);
            caption = StructuredJson.blankToNull(caption);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.FULL_IMAGE;
        }

        /** 화면 img alt. 입력한 alt → 캡션 → "" (장식 이미지) 순서다. */
        public String effectiveAlt() {
            return firstText(alt, caption);
        }

        @Override
        public List<StructuredImage> images() {
            return imageList(image);
        }
    }

    /** 이미지와 글을 나란히 둔다. 이미지 위치(왼쪽/오른쪽)만 고를 수 있다. */
    record ImageText(String id, ImagePosition imagePosition, StructuredImage image, String alt,
                     String title, String text) implements StructuredBlock {
        public ImageText {
            alt = StructuredJson.blankToNull(alt);
            title = StructuredJson.blankToNull(title);
            text = StructuredJson.blankToNull(text);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.IMAGE_TEXT;
        }

        /** 화면 img alt. 입력한 alt → 제목 → "" 순서다. */
        public String effectiveAlt() {
            return firstText(alt, title);
        }

        /** 화면용 문단. 빈 줄로 나누고, 문단 안의 줄바꿈은 그대로 둔다. */
        public List<String> paragraphs() {
            return paragraphsOf(text);
        }

        @Override
        public List<StructuredImage> images() {
            return imageList(image);
        }
    }

    /** 여러 장을 한 장씩 넘겨 보는 슬라이더. items 순서가 화면 순서다. */
    record ImageSlider(String id, String title, List<SliderItem> items) implements StructuredBlock {
        public ImageSlider {
            title = StructuredJson.blankToNull(title);
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.IMAGE_SLIDER;
        }

        /** 슬라이더 사진 한 장의 화면 img alt. 사진 alt → 사진 제목 → 사진 설명 → 슬라이더 제목 → "" 순서다. */
        public String itemAlt(SliderItem item) {
            return firstText(item.alt(), item.title(), item.caption(), title);
        }

        @Override
        public List<StructuredImage> images() {
            return itemImages(items);
        }
    }

    /**
     * 한 줄에 사진 2장 또는 3장을 나란히 둔다. columns 가 칸 수이고 items 는 언제나 정확히 그 장수다.
     * (관리자 화면의 "이미지 2장 배치" / "이미지 3장 배치" 가 같은 블록이다)
     * 칸 수는 검사 단계에서 2 / 3 만 받으므로 JSON 에 없던 값은 null 그대로 둔다.
     */
    record ImageGrid(String id, Integer columns, List<SliderItem> items) implements StructuredBlock {
        public ImageGrid {
            items = items == null ? List.of() : List.copyOf(items);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.IMAGE_GRID;
        }

        /** 사진 한 칸의 화면 img alt. 사진 alt → 사진 제목 → 사진 설명 → "" 순서다. (블록 제목은 없다) */
        public String itemAlt(SliderItem item) {
            return firstText(item.alt(), item.title(), item.caption());
        }

        @Override
        public List<StructuredImage> images() {
            return itemImages(items);
        }
    }

    /** 짧은 핵심 정보나 강조 문장. */
    record Callout(String id, String text) implements StructuredBlock {
        public Callout {
            text = StructuredJson.blankToNull(text);
        }

        @Override
        public StructuredBlockType type() {
            return StructuredBlockType.CALLOUT;
        }

        @Override
        public List<StructuredImage> images() {
            return List.of();
        }
    }

    /** 슬라이더·이미지 배치의 사진 한 장. id 는 같은 블록 안에서 겹치지 않는다. */
    record SliderItem(String id, StructuredImage image, String alt, String title, String caption) {
        public SliderItem {
            alt = StructuredJson.blankToNull(alt);
            title = StructuredJson.blankToNull(title);
            caption = StructuredJson.blankToNull(caption);
        }
    }

    /**
     * 처음으로 값이 있는 글. 모두 비면 "" 이다. (img 의 alt 속성은 언제나 쓰되, 설명이 없으면 장식 이미지로 둔다)
     * 공개 상세와 관리자 미리보기가 같은 model 을 그리므로 alt 대체 규칙이 한 곳에만 있다.
     */
    private static String firstText(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return "";
    }

    /** 줄바꿈을 \n 으로 맞춘 뒤 빈 줄(공백만 있는 줄 포함)로 문단을 나눈다. 빈 문단은 뺀다. */
    private static List<String> paragraphsOf(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(text.replace("\r\n", "\n").replace('\r', '\n').split("\\n\\s*\\n"))
                .map(String::strip)
                .filter(paragraph -> !paragraph.isEmpty())
                .toList();
    }

    /** 사진 여러 장 블록(슬라이더·이미지 배치)의 목록. 아직 이미지를 넣지 않은 칸은 거른다. */
    private static List<StructuredImage> itemImages(List<SliderItem> items) {
        return items.stream()
                .map(SliderItem::image)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** 이미지 하나짜리 블록의 목록. 검사 전 model 에서 비어 있을 수 있어 null 을 거른다. */
    private static List<StructuredImage> imageList(StructuredImage image) {
        return image == null ? List.of() : List.of(image);
    }

    /** IMAGE_TEXT 의 이미지 위치. 대소문자까지 이 이름만 받는다. */
    enum ImagePosition {
        LEFT,
        RIGHT
    }

    /**
     * RICH_TEXT 의 본문 폭 프리셋. 실제 폭은 공개 화면 CSS 가 정한다. 대소문자까지 이 이름만 받는다.
     * 원문 공통 값이라 번역(structured_text)에는 들어가지 않는다.
     */
    enum RichTextLayout {
        /** 콘텐츠 폭에 넓게, 왼쪽 기준선에 맞춘다. */
        DEFAULT,
        /** 좁은 읽기 폭의 단을 가운데 둔다. (글은 왼쪽 정렬) */
        FOCUSED
    }
}
