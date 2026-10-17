package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDate;

/** travel_plan_days 한 행. 여행 기간의 하루(DAY 1 부터). */
@Data
@NoArgsConstructor
public class TravelPlanDay {
    private Long id;
    private Long travelPlanId;
    private Integer dayNumber;
    private LocalDate planDate;
    private String title;
    private Integer titleVersion;
    private Timestamp createdAt;
    private Timestamp updatedAt;
}
