package com.tripbora.dto;

import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ReviewPhotoWorkflowPreview extends ReviewPhotoMatch {
    private Long anchorId;
    private String kind;
}
