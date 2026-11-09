package com.tripbora.dto;

import lombok.Data;
import java.time.LocalDateTime;

/** 공개 응답에는 포함하지 않는 keyset 위치. null priority는 정렬의 마지막 그룹이다. */
@Data
public class DestinationGalleryPosition {
    private String section;
    private Long imageId;
    private Integer orderIndex;
    private Integer priority;
    private LocalDateTime reviewCreatedAt;
    private Long commentId;
    private Integer displayOrder;

    public int getPriorityNull() { return priority == null ? 1 : 0; }
    public int getPriorityValue() { return priority == null ? 0 : priority; }
}
