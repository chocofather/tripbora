package com.tripbora.service.travelinfo.structured;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.stereotype.Component;

/**
 * 구조화 콘텐츠 JSON(원문 블록 / 언어별 번역 글)을 typed model 로 읽고 규칙 검사까지 마친다.
 *
 * <p>관리자 입력이든 DB 에서 읽은 값이든 같은 경로로 다시 검사한다. 읽기를 얼마나 엄격하게 하는지는
 * {@link StructuredJson} 에 모아 두었다.
 */
@Component
public class StructuredContentParser {

    /** 블록 60개 · 긴 본문을 넉넉히 담는 크기. 이보다 큰 값은 읽기 전에 거부한다. */
    static final int MAX_JSON_LENGTH = 1_000_000;

    private static final ObjectReader CONTENT_READER =
            StructuredJson.MAPPER.readerFor(StructuredContent.class);
    private static final ObjectReader TEXT_READER =
            StructuredJson.MAPPER.readerFor(StructuredText.class);

    private final StructuredContentValidator validator;

    public StructuredContentParser(StructuredContentValidator validator) {
        this.validator = validator;
    }

    /** {@code structured_content} 를 읽는다. STRUCTURED 글에는 반드시 있어야 하므로 비어 있으면 오류다. */
    public StructuredContent parseContent(String json) {
        return parseContent(json, StructuredImagePolicy.TRAVEL_INFO);
    }

    public StructuredContent parseContent(String json, StructuredImagePolicy imagePolicy) {
        if (json == null || json.isBlank()) {
            throw new StructuredContentValidationException("구조화 콘텐츠가 비어 있습니다.");
        }
        StructuredContent content =
                read(CONTENT_READER, json, StructuredContentValidationException.CONTENT_FIELD);
        validator.validate(content, imagePolicy);
        return content;
    }

    /** {@code structured_text} 를 읽는다. 번역은 선택이라 비어 있으면 덮어쓸 글이 없는 것으로 본다. */
    public StructuredText parseText(String json) {
        if (json == null || json.isBlank()) {
            return StructuredText.EMPTY;
        }
        StructuredText text = read(TEXT_READER, json, StructuredContentValidator.TEXT_FIELD);
        if (text == null) {
            return StructuredText.EMPTY;
        }
        validator.validate(text);
        return text;
    }

    private <T> T read(ObjectReader reader, String json, String field) {
        if (json.length() > MAX_JSON_LENGTH) {
            throw new StructuredContentValidationException(field, "구조화 콘텐츠가 너무 큽니다.");
        }
        try {
            return reader.readValue(json);
        } catch (InvalidTypeIdException exception) {
            throw new StructuredContentValidationException(field,
                    "알 수 없는 블록 종류입니다." + location(exception));
        } catch (UnrecognizedPropertyException exception) {
            throw new StructuredContentValidationException(field,
                    "허용하지 않는 항목이 있습니다: " + safeName(exception.getPropertyName())
                            + location(exception));
        } catch (JsonProcessingException exception) {
            // 원본 메시지에는 입력 일부가 섞이므로 화면에는 돌려주지 않는다.
            throw new StructuredContentValidationException(field,
                    "구조화 콘텐츠 형식이 올바르지 않습니다." + location(exception));
        }
    }

    /** blocks[2].items[0].image 처럼 문제 자리만 알려 준다. 문법 오류는 자리를 따로 붙이지 않는다. */
    private String location(JsonProcessingException exception) {
        if (!(exception instanceof JsonMappingException mapping) || mapping.getPath().isEmpty()) {
            return "";
        }
        StringBuilder path = new StringBuilder();
        for (JsonMappingException.Reference reference : mapping.getPath()) {
            if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            } else if (reference.getFieldName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(safeName(reference.getFieldName()));
            }
        }
        return path.isEmpty() ? "" : " (위치: " + path + ")";
    }

    private String safeName(String name) {
        if (name == null) {
            return "";
        }
        String safe = name.replaceAll("[^A-Za-z0-9_-]", "?");
        return safe.length() > 40 ? safe.substring(0, 40) + "…" : safe;
    }
}
