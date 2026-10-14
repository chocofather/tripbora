package com.tripbora.service.travelinfo.structured;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 읽어 낸 구조화 콘텐츠가 저장해도 되는 값인지 본다. 블록 종류마다 필수 값과 길이를 따로 검사한다.
 *
 * <p>DB 에서 읽은 JSON 도 믿지 않고 같은 규칙으로 다시 검사할 수 있게 상태 없이 둔다.
 * 오류 문구는 "3번째 블록(이미지 슬라이더) 2번째 이미지: …" 처럼 관리자가 고칠 자리를 가리킨다.
 */
@Component
public class StructuredContentValidator {

    static final int MAX_BLOCKS = 60;
    static final int MAX_SLIDER_ITEMS = 40;
    /** 업로드 때 줄여 저장하므로 실제 값은 훨씬 작다. 말이 안 되는 값만 막는다. */
    static final int MAX_IMAGE_DIMENSION = 8000;
    static final int MAX_ID_LENGTH = 40;
    /** 이미지 배치 블록의 칸 수. */
    static final Set<Integer> GRID_COLUMNS = Set.of(2, 3);
    /** 일반 글 서식 본문(html)의 길이. 글 자체는 RICH_TEXT 한도로 보고, 이 값은 태그가 지나치게 많은 값만 막는다. */
    static final int MAX_RICH_TEXT_HTML_LENGTH = 20000;

    /** 번역 글을 받는 폼 필드. (언어별 번역 슬롯) */
    static final String TEXT_FIELD = "translations";

    private static final Pattern ID_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{1," + MAX_ID_LENGTH + "}$");

    /** 글 칸의 이름·길이·줄바꿈 허용 여부. 오류 문구의 조사도 함께 둔다. */
    enum TextField {
        TITLE("제목", "제목을", "제목은", 120, false),
        RICH_TEXT("본문", "본문을", "본문은", 5000, true),
        IMAGE_TEXT("본문", "본문을", "본문은", 3000, true),
        CAPTION("캡션", "캡션을", "캡션은", 300, false),
        ALT("이미지 설명(alt)", "이미지 설명(alt)을", "이미지 설명(alt)은", 200, false),
        ITEM_TITLE("이미지 제목", "이미지 제목을", "이미지 제목은", 100, false),
        CALLOUT("강조 문구", "강조 문구를", "강조 문구는", 500, true),
        CREDIT_AUTHOR("저작자", "저작자를", "저작자는", 100, false),
        CREDIT_SOURCE("출처명", "출처명을", "출처명은", 100, false),
        CREDIT_SOURCE_URL("출처 URL", "출처 URL을", "출처 URL은", 500, false),
        CREDIT_LICENSE("라이선스", "라이선스를", "라이선스는", 100, false),
        CREDIT_LICENSE_URL("라이선스 URL", "라이선스 URL을", "라이선스 URL은", 500, false);

        private final String label;
        private final String objectLabel;
        private final String subjectLabel;
        private final int maxLength;
        private final boolean multiline;

        TextField(String label, String objectLabel, String subjectLabel, int maxLength,
                  boolean multiline) {
            this.label = label;
            this.objectLabel = objectLabel;
            this.subjectLabel = subjectLabel;
            this.maxLength = maxLength;
            this.multiline = multiline;
        }

        /** 관리자 에디터의 같은 한도와 맞는지 확인하는 테스트가 읽는다. */
        int maxLength() {
            return maxLength;
        }
    }

    public void validate(StructuredContent content) {
        if (content == null) {
            throw new StructuredContentValidationException("구조화 콘텐츠가 비어 있습니다.");
        }
        if (!Objects.equals(content.version(), StructuredContent.CURRENT_VERSION)) {
            throw new StructuredContentValidationException("지원하지 않는 구조화 콘텐츠 버전입니다.");
        }
        List<StructuredBlock> blocks = content.blocks();
        if (blocks.isEmpty()) {
            throw new StructuredContentValidationException("블록을 하나 이상 추가해 주세요.");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new StructuredContentValidationException(
                    "블록은 " + MAX_BLOCKS + "개까지 추가할 수 있습니다.");
        }

        Set<String> blockIds = new HashSet<>();
        for (int index = 0; index < blocks.size(); index++) {
            StructuredBlock block = blocks.get(index);
            String location = (index + 1) + "번째 블록(" + block.type().getLabel() + ")";
            requireId(block.id(), location, "블록");
            if (!blockIds.add(block.id())) {
                throw invalid(location, "블록 식별자가 중복되었습니다.");
            }
            validateBlock(block, location);
        }
    }

    /** 번역 글의 모양만 본다. 원문에 없는 블록 id 정리와 블록 종류별 길이는 원문과 합칠 때 다시 본다. */
    public void validate(StructuredText text) {
        if (text == null) {
            return;
        }
        if (text.blocks().size() > MAX_BLOCKS) {
            throw new StructuredContentValidationException(TEXT_FIELD,
                    "번역 블록은 " + MAX_BLOCKS + "개까지 저장할 수 있습니다.");
        }
        for (Map.Entry<String, StructuredText.BlockText> entry : text.blocks().entrySet()) {
            String location = "번역 블록(" + displayId(entry.getKey()) + ")";
            requireTextId(entry.getKey(), location, "블록");
            StructuredText.BlockText block = entry.getValue();
            optionalText(block.title(), TextField.TITLE, location, TEXT_FIELD);
            optionalText(block.text(), TextField.RICH_TEXT, location, TEXT_FIELD);
            if (block.html() != null) {
                optionalText(StructuredRichTextHtml.plainText(block.html()), TextField.RICH_TEXT, location, TEXT_FIELD);
                if (block.html().length() > MAX_RICH_TEXT_HTML_LENGTH) {
                    throw new StructuredContentValidationException(TEXT_FIELD,
                            location + ": 본문 서식이 너무 많습니다. 서식을 줄여 주세요.");
                }
            }
            optionalText(block.caption(), TextField.CAPTION, location, TEXT_FIELD);
            optionalText(block.alt(), TextField.ALT, location, TEXT_FIELD);

            if (block.items().size() > MAX_SLIDER_ITEMS) {
                throw new StructuredContentValidationException(TEXT_FIELD,
                        location + ": 번역 이미지는 " + MAX_SLIDER_ITEMS + "장까지 저장할 수 있습니다.");
            }
            for (Map.Entry<String, StructuredText.ItemText> item : block.items().entrySet()) {
                String itemLocation = location + " 이미지(" + displayId(item.getKey()) + ")";
                requireTextId(item.getKey(), itemLocation, "이미지");
                optionalText(item.getValue().title(), TextField.ITEM_TITLE, itemLocation, TEXT_FIELD);
                optionalText(item.getValue().caption(), TextField.CAPTION, itemLocation, TEXT_FIELD);
                optionalText(item.getValue().alt(), TextField.ALT, itemLocation, TEXT_FIELD);
            }
        }
    }

    private void validateBlock(StructuredBlock block, String location) {
        if (block instanceof StructuredBlock.SectionTitle sectionTitle) {
            requireText(sectionTitle.title(), TextField.TITLE, location);
        } else if (block instanceof StructuredBlock.RichText richText) {
            requireText(richText.text(), TextField.RICH_TEXT, location);
            if (richText.html() != null && richText.html().length() > MAX_RICH_TEXT_HTML_LENGTH) {
                throw invalid(location, "본문 서식이 너무 많습니다. 서식을 줄여 주세요.");
            }
        } else if (block instanceof StructuredBlock.FullImage fullImage) {
            requireImage(fullImage.image(), location);
            // alt 는 선택이다. 비면 화면이 캡션 등으로 대신 채운다. (StructuredBlock.FullImage#effectiveAlt)
            optionalText(fullImage.alt(), TextField.ALT, location);
            optionalText(fullImage.caption(), TextField.CAPTION, location);
        } else if (block instanceof StructuredBlock.ImageText imageText) {
            if (imageText.imagePosition() == null) {
                throw invalid(location, "이미지 위치를 선택해 주세요.");
            }
            requireImage(imageText.image(), location);
            optionalText(imageText.alt(), TextField.ALT, location);
            optionalText(imageText.title(), TextField.TITLE, location);
            requireText(imageText.text(), TextField.IMAGE_TEXT, location);
        } else if (block instanceof StructuredBlock.ImageSlider slider) {
            validateSlider(slider, location);
        } else if (block instanceof StructuredBlock.ImageGrid grid) {
            validateGrid(grid, location);
        } else if (block instanceof StructuredBlock.Callout callout) {
            requireText(callout.text(), TextField.CALLOUT, location);
        } else {
            throw invalid(location, "지원하지 않는 블록입니다.");
        }
    }

    private void validateSlider(StructuredBlock.ImageSlider slider, String location) {
        optionalText(slider.title(), TextField.TITLE, location);
        List<StructuredBlock.SliderItem> items = slider.items();
        if (items.isEmpty()) {
            throw invalid(location, "이미지를 한 장 이상 추가해 주세요.");
        }
        if (items.size() > MAX_SLIDER_ITEMS) {
            throw invalid(location, "이미지는 " + MAX_SLIDER_ITEMS + "장까지 추가할 수 있습니다.");
        }
        validateItems(items, location);
    }

    /** 이미지 배치는 2칸 또는 3칸이고, 사진 수가 칸 수와 정확히 같아야 한다. */
    private void validateGrid(StructuredBlock.ImageGrid grid, String location) {
        Integer columns = grid.columns();
        if (columns == null || !GRID_COLUMNS.contains(columns)) {
            throw invalid(location, "이미지 배치는 2장 또는 3장만 선택할 수 있습니다.");
        }
        if (grid.items().size() != columns) {
            throw invalid(location, "이미지 " + columns + "장 배치에는 이미지가 정확히 " + columns + "장 있어야 합니다.");
        }
        validateItems(grid.items(), location);
    }

    /** 슬라이더·이미지 배치의 사진들. 이미지는 필수, 글(alt·제목·설명)은 선택이다. */
    private void validateItems(List<StructuredBlock.SliderItem> items, String location) {
        Set<String> itemIds = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            StructuredBlock.SliderItem item = items.get(index);
            String itemLocation = location + " " + (index + 1) + "번째 이미지";
            requireId(item.id(), itemLocation, "이미지");
            if (!itemIds.add(item.id())) {
                throw invalid(itemLocation, "이미지 식별자가 중복되었습니다.");
            }
            requireImage(item.image(), itemLocation);
            optionalText(item.alt(), TextField.ALT, itemLocation);
            optionalText(item.title(), TextField.ITEM_TITLE, itemLocation);
            optionalText(item.caption(), TextField.CAPTION, itemLocation);
        }
    }

    private void requireImage(StructuredImage image, String location) {
        if (image == null || image.url() == null || image.url().isBlank()) {
            throw invalid(location, "이미지를 선택해 주세요.");
        }
        if (!StructuredImage.URL_PATTERN.matcher(image.url()).matches()) {
            throw invalid(location, "이미지 경로가 올바르지 않습니다.");
        }
        if (!isValidDimension(image.width()) || !isValidDimension(image.height())) {
            throw invalid(location, "이미지 크기 정보가 올바르지 않습니다.");
        }
        validateCredit(image.credit(), location);
    }

    private boolean isValidDimension(Integer value) {
        return value != null && value >= 1 && value <= MAX_IMAGE_DIMENSION;
    }

    /**
     * 이미지 출처표시. 없으면(null) 검사할 것이 없다. 있으면 저작자·출처명 중 하나는 있어야 하고,
     * 링크로 쓸 URL 은 http / https 주소만 받는다. (javascript:, data:, file: 같은 주소는 저장하지 않는다)
     */
    private void validateCredit(StructuredImageCredit credit, String location) {
        if (credit == null) {
            return;
        }
        if (credit.author() == null && credit.source() == null) {
            throw invalid(location, "출처표시에는 저작자 또는 출처명을 입력해 주세요.");
        }
        optionalText(credit.author(), TextField.CREDIT_AUTHOR, location);
        optionalText(credit.source(), TextField.CREDIT_SOURCE, location);
        optionalWebUrl(credit.sourceUrl(), TextField.CREDIT_SOURCE_URL, location);
        optionalText(credit.license(), TextField.CREDIT_LICENSE, location);
        optionalWebUrl(credit.licenseUrl(), TextField.CREDIT_LICENSE_URL, location);
    }

    private void optionalWebUrl(String value, TextField field, String location) {
        if (value == null || value.isBlank()) {
            return;
        }
        checkText(value, field, location, StructuredContentValidationException.CONTENT_FIELD);
        if (!isWebUrl(value)) {
            throw invalid(location, field.subjectLabel + " http 또는 https 주소로 입력해 주세요.");
        }
    }

    /** 호스트가 있는 http / https 절대 주소. 공백 같은 URI 에 쓸 수 없는 문자가 있으면 거부한다. */
    private boolean isWebUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private void requireId(String id, String location, String target) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw invalid(location, target + " 식별자가 올바르지 않습니다.");
        }
    }

    private void requireTextId(String id, String location, String target) {
        if (id == null || !ID_PATTERN.matcher(id).matches()) {
            throw new StructuredContentValidationException(TEXT_FIELD,
                    location + ": " + target + " 식별자가 올바르지 않습니다.");
        }
    }

    private void requireText(String value, TextField field, String location) {
        if (value == null || value.isBlank()) {
            throw invalid(location, field.objectLabel + " 입력해 주세요.");
        }
        checkText(value, field, location, StructuredContentValidationException.CONTENT_FIELD);
    }

    private void optionalText(String value, TextField field, String location) {
        optionalText(value, field, location, StructuredContentValidationException.CONTENT_FIELD);
    }

    private void optionalText(String value, TextField field, String location, String formField) {
        if (value == null || value.isBlank()) {
            return;
        }
        checkText(value, field, location, formField);
    }

    /**
     * 길이는 화면에 보이는 글자 수(code point)로 센다. 줄바꿈은 여러 줄 칸에서만 받고,
     * 그 밖의 제어 문자와 짝이 맞지 않는 surrogate 는 저장하지 않는다.
     */
    private void checkText(String value, TextField field, String location, String formField) {
        if (value.codePointCount(0, value.length()) > field.maxLength) {
            throw new StructuredContentValidationException(formField,
                    location + ": " + field.subjectLabel + " " + field.maxLength + "자 이하로 입력해 주세요.");
        }
        value.codePoints().forEach(codePoint -> {
            if (codePoint == '\n' || codePoint == '\r') {
                if (!field.multiline) {
                    throw new StructuredContentValidationException(formField,
                            location + ": " + field.subjectLabel + " 한 줄로 입력해 주세요.");
                }
            } else if (codePoint != '\t'
                    && (Character.isISOControl(codePoint) || isUnpairedSurrogate(codePoint))) {
                throw new StructuredContentValidationException(formField,
                        location + ": " + field.label + "에 사용할 수 없는 문자가 있습니다.");
            }
        });
    }

    /** {@link String#codePoints()} 는 짝 없는 surrogate 를 그 값 그대로 돌려준다. */
    private boolean isUnpairedSurrogate(int codePoint) {
        return codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE;
    }

    /** 오류 문구에 넣는 번역 키. 형식이 틀린 값이 문구를 어지럽히지 않게 줄인다. */
    private String displayId(String id) {
        if (id == null) {
            return "";
        }
        String safe = id.replaceAll("[^A-Za-z0-9_-]", "?");
        return safe.length() > MAX_ID_LENGTH ? safe.substring(0, MAX_ID_LENGTH) + "…" : safe;
    }

    private StructuredContentValidationException invalid(String location, String reason) {
        return new StructuredContentValidationException(location + ": " + reason);
    }
}
