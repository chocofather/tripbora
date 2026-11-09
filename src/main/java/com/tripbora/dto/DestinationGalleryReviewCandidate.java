package com.tripbora.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class DestinationGalleryReviewCandidate extends ReviewGalleryPhoto {
    private Integer galleryPriority;
    private LocalDateTime reviewCreatedAt;
    private Integer displayOrder;
}
