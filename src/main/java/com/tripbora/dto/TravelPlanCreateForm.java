package com.tripbora.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * 공동 여행계획 방 생성 폼.
 * 값 검증은 TravelPlanService 가 하고, 여기서는 입력만 담는다.
 * 대표 이미지는 업로드 기능을 만드는 단계에서 추가한다.
 */
@Data
public class TravelPlanCreateForm {
    private Long sourceCourseId;
    private String creationToken;
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    /** 이 방에서만 쓰는 표시 이름. users.nickname 을 자동으로 쓰지 않는다. */
    private String displayName;
}
