package com.tripbora.dto;

import lombok.Data;

@Data
public class ReviewPhotoMatch {
    private Long id;
    private Long commentId;
    private String nickname;
    private String imageUrl;
    private boolean exact;
    private Integer distance;
    private String galleryStatus;
    private Long userId;
    private String sha256;
    private Boolean eligible;
    private String eligibilityReason;

    public boolean isReviewable() {
        return isApprovable() || isRejectable() || isRevocable();
    }

    public boolean isApprovable() {
        return ("PENDING".equals(galleryStatus) || "APPROVED".equals(galleryStatus))
                && Boolean.TRUE.equals(eligible) && eligibilityReason==null;
    }

    public boolean isRejectable() { return "PENDING".equals(galleryStatus); }
    public boolean isRevocable() { return "APPROVED".equals(galleryStatus); }

    public String getEligibilityMessage() {
        return eligibilityMessage(galleryStatus,eligible,eligibilityReason);
    }

    static String eligibilityMessage(String galleryStatus,Boolean eligible,String eligibilityReason) {
        if(eligibilityReason==null) {
            if("NONE".equals(galleryStatus)) return "갤러리 활용 미동의 · 참고용 사진";
            if("REJECTED".equals(galleryStatus)) return "갤러리 활용 반려 완료 · 참고용 사진";
            if("REVOKED".equals(galleryStatus)) return "갤러리 활용 철회·승인 취소 완료 · 참고용 사진";
            return Boolean.TRUE.equals(eligible)?null:"심사 자격을 확인할 수 없습니다.";
        }
        return switch(eligibilityReason) {
            case "PHOTO_CONSENT_MISSING" -> "이 사진의 갤러리 활용 동의 기록 없음 (동의 전 업로드 사진은 소급 적용되지 않습니다.)";
            case "PHOTO_CONSENT_INVALID" -> "사진의 원본 동의 근거 또는 현재 허용 범위를 확인할 수 없습니다.";
            case "PHOTO_CONSENT_WITHDRAWN" -> "이 사진의 갤러리 활용 동의 철회";
            case "MEMBER_CONSENT_MISSING" -> "현재 정책에 대한 회원의 갤러리 활용 동의 없음";
            case "MEMBER_CONSENT_WITHDRAWN" -> "현재 정책에 대한 회원의 갤러리 활용 동의 거절·철회";
            case "POLICY_UNAVAILABLE" -> "현재 시행 중인 갤러리 활용 정책 없음";
            case "REVIEW_DELETED" -> "삭제된 리뷰";
            case "REVIEW_HIDDEN" -> "관리자 조치로 비공개된 리뷰";
            case "REPLY_NOT_REVIEW" -> "일반 리뷰가 아닌 답글 사진";
            case "MEMBER_RESTRICTED" -> "회원 상태 제한 (활성 회원 아님)";
            case "DESTINATION_PRIVATE" -> "여행지 비공개 또는 삭제";
            default -> "심사 자격을 확인할 수 없습니다.";
        };
    }
}
