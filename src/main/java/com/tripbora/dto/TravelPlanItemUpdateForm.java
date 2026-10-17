package com.tripbora.dto;

import lombok.Data;
import com.tripbora.model.TravelPlanItemDetails;

/**
 * A 일정 수정 폼.
 * version 은 화면이 들고 있던 값이며, 서버가 낙관적 잠금 조건으로 쓴다.
 */
@Data
public class TravelPlanItemUpdateForm {
    private String content;
    private Integer version;
    private String itemType;
    private String startTime;
    private TravelPlanItemDetails details;
}
