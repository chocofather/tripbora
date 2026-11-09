package com.tripbora.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * destination_comment_images 한 행. 여행지 댓글에 첨부된 사진이다.
 * 댓글당 최대 5장이며 display_order 로 순서를 보관한다.
 */
@Data
@NoArgsConstructor
public class DestinationCommentImage {

    private Long id;
    private Long commentId;
    private String imageUrl;
    private Integer displayOrder;
    private Timestamp createdAt;
    private String galleryStatus = "NONE";
    private Long galleryConsentId;
    private Timestamp galleryConsentAt;
    private String galleryConsentVersion;
    private Timestamp galleryConsentWithdrawnAt;
    private Long galleryLicensePolicyId;
    private LocalDateTime galleryLicenseAcceptedAt;
    private Long reviewedBy;
    private Timestamp reviewedAt;
    private String reviewReason;
}
