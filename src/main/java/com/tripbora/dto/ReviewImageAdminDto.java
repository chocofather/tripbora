package com.tripbora.dto;

import lombok.Data;

@Data
public class ReviewImageAdminDto {
    private Long id;
    private String imageUrl;
    private String galleryStatus;
    private String reviewReason;
    private Long userId;
    private String nickname;
    private Long commentId;
    private String content;
    private Long destinationId;
    private String destinationName;
    private java.time.LocalDateTime createdAt;
    private String analysisState;
    private Integer pixelWidth;
    private Integer pixelHeight;
    private Double meanBrightness;
    private Double sharpness;
    private String qualityFlags;
    private long exactCount;
    private long similarCount;
    private Boolean eligible;
    private String eligibilityReason;
    public boolean isApprovable() {
        return ("PENDING".equals(galleryStatus) || "APPROVED".equals(galleryStatus))
                && Boolean.TRUE.equals(eligible) && eligibilityReason==null;
    }
    public boolean isRejectable() { return "PENDING".equals(galleryStatus); }
    public boolean isRevocable() { return "APPROVED".equals(galleryStatus); }
    public boolean isReviewable() { return isApprovable() || isRejectable() || isRevocable(); }
    public String getEligibilityMessage() { return ReviewPhotoMatch.eligibilityMessage(galleryStatus,eligible,eligibilityReason); }
    public boolean isExactDuplicate() { return exactCount > 0; }
    public boolean isSimilarDuplicate() { return similarCount > 0; }
    private Integer galleryPriority;
    private long galleryPriorityVersion;

    public Double getAspectRatio() {
        return pixelWidth == null || pixelHeight == null || pixelHeight <= 0 ? null : (double)pixelWidth/pixelHeight;
    }

    public String getQualitySummary() {
        if (qualityFlags == null || qualityFlags.isBlank()) return "기본 규격 충족 (참고)";
        return java.util.Arrays.stream(qualityFlags.split(",")).map(flag -> switch(flag) {
            case "SMALL" -> "작은 해상도"; case "BLUR" -> "흐림 가능성";
            case "DARK" -> "어두움"; case "BRIGHT" -> "밝음"; case "ASPECT" -> "화면 비율 확인";
            default -> flag;
        }).collect(java.util.stream.Collectors.joining(" · "));
    }
}
