package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalTime;

/** travel_plan_items 한 행. DAY 안의 A 일정(자유 텍스트 한 덩어리). */
@Data
@NoArgsConstructor
public class TravelPlanItem {
    private Long id;
    private Long travelPlanDayId;
    private String content;
    /** TravelPlanItemType 코드. 기존 NULL은 일반 일정이다. */
    private String tag;
    private LocalTime startTime;
    private TravelPlanItemDetails details;
    private Integer displayOrder;
    /** 작성자의 travel_plan_members.id. 멤버가 지워지면 NULL 이 된다. */
    private Long createdByMemberId;
    private Integer version;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public TravelPlanItemType getItemType() { return TravelPlanItemType.fromTag(tag); }

    public String getTitle() {
        if (content == null) return "";
        return content.split("\\R", 2)[0];
    }

    public String getNote() {
        if (content == null) return "";
        String[] lines = content.split("\\R", 2);
        return lines.length > 1 ? lines[1] : "";
    }
}
