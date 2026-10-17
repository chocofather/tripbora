package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalTime;

/**
 * travel_plan_final_items 한 행. 완료된 계획의 일정 한 줄.
 * 최종본에는 누가 썼는지를 두지 않는다. 무엇을 하기로 했는지만 남는다.
 */
@Data
@NoArgsConstructor
public class TravelPlanFinalItem {
    private Long id;
    private Long finalDayId;
    private String content;
    private String tag;
    private LocalTime startTime;
    private TravelPlanItemDetails details;
    /** 1 부터. DB CHECK 가 1 미만을 막는다. */
    private Integer displayOrder;
    private Timestamp createdAt;

    public TravelPlanItemType getItemType() { return TravelPlanItemType.fromTag(tag); }

    public String getTitle() {
        return content == null ? "" : content.split("\\R", 2)[0];
    }

    public String getNote() {
        if (content == null) return "";
        String[] lines = content.split("\\R", 2);
        return lines.length > 1 ? lines[1] : "";
    }
}
