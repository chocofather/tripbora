package com.tripbora.service.travelinfo.structured;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 구조화 콘텐츠 저장·조회 입구. 도메인별 이미지 policy와 공용 읽기 → 검사 → 정규화 → 파생 content를 묶는다.
 *
 * <p>저장 규칙
 * <ul>
 *   <li>원문/번역 JSON 은 검사를 마친 typed model 을 다시 쓴 정규 JSON 으로만 저장한다.</li>
 *   <li>파생 content 는 {@link StructuredContentTextRenderer} 가 escape 와 4바이트 문자 처리를 마친 HTML 이다.
 *       Quill 용 sanitizer 를 다시 거치면 숫자 참조가 4바이트 문자로 풀려 utf8mb3 저장이 실패하므로 거치지 않는다.</li>
 * </ul>
 */
@Service
@Slf4j
public class StructuredContentService {

    private final StructuredContentParser parser;
    private final StructuredContentValidator validator;
    private final StructuredContentSerializer serializer;
    private final StructuredContentTextRenderer renderer;
    private final StructuredContentLocalizer localizer;

    public StructuredContentService(StructuredContentParser parser,
                                    StructuredContentValidator validator,
                                    StructuredContentSerializer serializer,
                                    StructuredContentTextRenderer renderer,
                                    StructuredContentLocalizer localizer) {
        this.parser = parser;
        this.validator = validator;
        this.serializer = serializer;
        this.renderer = renderer;
        this.localizer = localizer;
    }

    /** 관리자가 보낸 원문 블록 JSON 을 저장할 값으로 만든다. 잘못된 값이면 검증 예외다. */
    public PreparedContent prepareContent(String json) {
        return prepareContent(json, StructuredImagePolicy.TRAVEL_INFO);
    }

    public PreparedContent prepareContent(String json, StructuredImagePolicy imagePolicy) {
        StructuredContent content = parser.parseContent(json, imagePolicy);
        return new PreparedContent(content, serializer.write(content), renderer.render(content));
    }

    /**
     * 한 언어의 번역 글을 저장할 값으로 만든다. 원문에 맞지 않는 칸은 빼고, 원문과 합친 결과를
     * 블록 종류별 규칙으로 다시 검사한다. 덮어쓸 글이 없으면 두 값 모두 null 이다.
     *
     * @param languageLabel 오류 문구 앞에 붙일 언어 이름 (예: English)
     */
    public PreparedText prepareTranslation(StructuredContent base, String textJson, String languageLabel) {
        return prepareTranslation(base, textJson, languageLabel, StructuredImagePolicy.TRAVEL_INFO);
    }

    public PreparedText prepareTranslation(StructuredContent base, String textJson, String languageLabel,
                                            StructuredImagePolicy imagePolicy) {
        try {
            StructuredText aligned = localizer.alignToBase(base, parser.parseText(textJson));
            if (aligned.blocks().isEmpty()) {
                return PreparedText.EMPTY;
            }
            StructuredContent localized = localizer.apply(base, aligned);
            validator.validate(localized, imagePolicy);
            return new PreparedText(serializer.write(aligned), renderer.render(localized));
        } catch (StructuredContentValidationException exception) {
            throw new StructuredContentValidationException(StructuredContentValidator.TEXT_FIELD,
                    languageLabel + " 번역 · " + exception.getMessage());
        }
    }

    /**
     * 문서가 쓰는 모든 이미지 url. 여러 블록이 같은 이미지를 써도 한 번만 담고, 블록 순서를 지킨다.
     * 읽고 검사를 마친 model 을 받는다. (JSON 문자열을 뒤지지 않는다) null 이면 빈 집합이다.
     */
    public Set<String> collectImageUrls(StructuredContent content) {
        if (content == null) {
            return Set.of();
        }
        Set<String> urls = new LinkedHashSet<>();
        for (StructuredBlock block : content.blocks()) {
            for (StructuredImage image : block.images()) {
                if (image.url() != null) {
                    urls.add(image.url());
                }
            }
        }
        return urls;
    }

    /**
     * DB 에 저장된 원문을 다시 읽고 검사한다. 저장 값이라도 믿지 않는다.
     * 읽지 못하면 기록을 남기고 null 을 돌려준다. (화면은 파생 content 로 대신 보여 줄 수 있다)
     */
    public StructuredContent readStored(String storedJson) {
        return readStored(storedJson, StructuredImagePolicy.TRAVEL_INFO);
    }

    public StructuredContent readStored(String storedJson, StructuredImagePolicy imagePolicy) {
        try {
            return parser.parseContent(storedJson, imagePolicy);
        } catch (StructuredContentValidationException exception) {
            log.warn("저장된 구조화 콘텐츠를 읽지 못했습니다: {}", exception.getMessage());
            return null;
        }
    }

    /**
     * 수정 화면 복원용 정규 JSON. 저장 값이 규칙에 맞지 않으면 그대로 돌려준다.
     * 다시 저장할 때 같은 검사로 걸러지므로 화면을 막지 않는다.
     */
    public String restoreContentJson(String storedJson) {
        return restoreContentJson(storedJson, StructuredImagePolicy.TRAVEL_INFO);
    }

    public String restoreContentJson(String storedJson, StructuredImagePolicy imagePolicy) {
        StructuredContent content = readStored(storedJson, imagePolicy);
        return content == null ? storedJson : serializer.write(content);
    }

    /** 수정 화면 복원용 번역 정규 JSON. 덮어쓸 글이 없으면 null, 규칙에 맞지 않으면 저장 값 그대로. */
    public String restoreTextJson(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return null;
        }
        try {
            StructuredText text = parser.parseText(storedJson);
            return text.blocks().isEmpty() ? null : serializer.write(text);
        } catch (StructuredContentValidationException exception) {
            log.warn("저장된 구조화 콘텐츠 번역을 읽지 못했습니다: {}", exception.getMessage());
            return storedJson;
        }
    }

    /**
     * 공개 화면용. 원문에 한 언어 번역 글을 덮어쓴다. 저장된 번역이 잘못되었으면 기록만 남기고
     * 원문 글을 그대로 보여 준다. (번역 하나 때문에 상세가 열리지 않으면 안 된다)
     */
    public StructuredContent localize(StructuredContent base, String storedTextJson) {
        return localize(base, storedTextJson, StructuredImagePolicy.TRAVEL_INFO);
    }

    public StructuredContent localize(StructuredContent base, String storedTextJson, StructuredImagePolicy imagePolicy) {
        if (storedTextJson == null || storedTextJson.isBlank()) {
            return base;
        }
        try {
            StructuredText aligned = localizer.alignToBase(base, parser.parseText(storedTextJson));
            StructuredContent localized = localizer.apply(base, aligned);
            validator.validate(localized, imagePolicy);
            return localized;
        } catch (StructuredContentValidationException exception) {
            log.warn("저장된 구조화 콘텐츠 번역을 읽지 못해 원문 글을 씁니다: {}", exception.getMessage());
            return base;
        }
    }

    /**
     * 저장할 원문.
     *
     * @param content        검사를 마친 model (번역 정리·합치기의 기준)
     * @param json           structured_content 에 넣을 정규 JSON
     * @param derivedContent content 에 넣을 검색/SEO 용 HTML
     */
    public record PreparedContent(StructuredContent content, String json, String derivedContent) {
    }

    /**
     * 저장할 한 언어의 번역.
     *
     * @param json           structured_text 에 넣을 정규 JSON (덮어쓸 글이 없으면 null)
     * @param derivedContent 그 언어 기준 파생 HTML (덮어쓸 글이 없으면 null)
     */
    public record PreparedText(String json, String derivedContent) {
        public static final PreparedText EMPTY = new PreparedText(null, null);
    }
}
