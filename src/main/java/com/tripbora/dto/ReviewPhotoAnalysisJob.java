package com.tripbora.dto;

import lombok.Data;

@Data
public class ReviewPhotoAnalysisJob {
    private Long id;
    private Long commentId;
    private Long destinationId;
    private String imageUrl;
}
