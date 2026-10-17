package com.tripbora.dto;

import lombok.Data;
import com.tripbora.model.TravelPlanItemDetails;

/**
 * A 일정 추가 폼.
 * content 원문은 유지하며 타입과 선택 시간을 함께 받는다.
 * 첫 줄은 제목, 다음 줄부터는 메모로 표시한다.
 */
@Data
public class TravelPlanItemCreateForm {
    private String content;
    private String itemType;
    private String startTime;
    private boolean emptyCard;
    /** null이면 상세 필드가 없는 과거 요청이다. nested binding 때만 생성된다. */
    private TravelPlanItemDetails details;
}
