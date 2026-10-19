package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

/** travel_plan_final_days 한 행. 완료된 계획의 하루. */
@Data
@NoArgsConstructor
public class TravelPlanFinalDay {
    private Long id;
    private Long snapshotId;
    private Integer dayNumber;
    private LocalDate planDate;
    /** 확정 후 변경 불가능한 원본 DAY에서 보완하는 제목. */
    private String title;
    private Timestamp createdAt;
}
