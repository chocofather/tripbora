package com.tripbora.dto;

import lombok.Data;

@Data
public class ReviewPhotoWorkflowGroup {
    private Long anchorId;
    private String kind;
    private String groupKey;
    private long total;
    private long pending;
    private long approved;
    private long reviewable;
}
