package com.tripbora.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 관리자 여행지 목록 한 줄. 데이터 상태(누락 항목)는 목록 쿼리가 한 쪽(30건)에 대해서만 함께 판정해 온다.
 */
@Data
public class AdminDestinationListItemDto {
    private Long id;
    private String name;
    private boolean publicVisible = true;
    private String type;
    private Long regionId;
    private String regionName;

    /** destination_images 가 한 장도 없다. */
    private boolean missingImage;
    /** 이미지는 있지만 대표(is_main = 1)로 지정된 이미지가 없다. */
    private boolean missingMainImage;
    /** destination_categories 연결이 하나도 없다. */
    private boolean missingCategory;
    /** 한국어 원문의 간단 설명·상세 설명이 모두 비어 있다. */
    private boolean missingDescription;
    /** 번역이 완료된 언어 코드(쉼표 구분). 완료 판정은 Mapper XML 의 adminCompleteTranslationRows 와 같다. */
    private String completeTranslationLanguages;

    /** 번역이 필요한 언어(en·ja·zh-CN·zh-TW) 중 완료되지 않은 언어. 등록폼 번역 슬롯 순서를 따른다. */
    public List<String> getMissingTranslationLanguages() {
        List<String> complete = completeTranslationLanguages == null
                ? List.of()
                : Arrays.stream(completeTranslationLanguages.split(",")).map(String::strip).toList();
        List<String> missing = new ArrayList<>();
        for (String language : DestinationForm.SUBTYPE_TRANSLATION_LANGUAGES) {
            if (complete.stream().noneMatch(language::equalsIgnoreCase)) {
                missing.add(language);
            }
        }
        return missing;
    }

    public boolean isMissingTranslation() {
        return !getMissingTranslationLanguages().isEmpty();
    }

    /** 위 누락 항목 중 하나라도 있으면 목록에 상태 표시를 붙인다. */
    public boolean hasDataIssue() {
        return missingImage || missingMainImage || missingCategory || missingDescription || isMissingTranslation();
    }
}
