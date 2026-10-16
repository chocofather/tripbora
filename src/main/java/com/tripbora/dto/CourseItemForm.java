package com.tripbora.dto;

import lombok.Data;
import java.math.BigDecimal;

/** destinationId가 없는 직접 입력 항목에만 장소 필드를 사용한다. */
@Data
public class CourseItemForm {
    private Long destinationId;
    private Long countryId;
    private String itemType;
    private String placeName;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private Integer displayOrder;
    private String startTime;
    /** 이전 호출부 호환용. 사용자 폼에 bind하지 않고 저장에서도 무시한다. */
    private String endTime;
    private String memo;
}
