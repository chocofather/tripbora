package com.tripbora.dto;

import lombok.Data;

/** Snapshot 필드는 클라이언트에서 받지 않는다. */
@Data
public class CourseItemForm {
    private Long destinationId;
    private Integer displayOrder;
    private String startTime;
    /** 이전 호출부 호환용. 사용자 폼에 bind하지 않고 저장에서도 무시한다. */
    private String endTime;
    private String memo;
}
