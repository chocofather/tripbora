package com.tripbora.service.comment;

import com.tripbora.dto.ReviewPhotoMatch;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ReviewPhotoEligibilityMessageTest {
    @Test void jsonExposesSeparateApprovalRejectionAndRevocationCapabilitiesOnBothPhotoDtos() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var match=new ReviewPhotoMatch();match.setGalleryStatus("PENDING");match.setEligible(false);match.setEligibilityReason("PHOTO_CONSENT_MISSING");
        var admin=new com.tripbora.dto.ReviewImageAdminDto();admin.setGalleryStatus("PENDING");admin.setEligible(false);admin.setEligibilityReason("PHOTO_CONSENT_MISSING");
        for(Object dto:java.util.List.of(match,admin)) {
            var json=mapper.valueToTree(dto);
            assertThat(json.get("approvable").asBoolean()).isFalse();assertThat(json.get("rejectable").asBoolean()).isTrue();
            assertThat(json.get("revocable").asBoolean()).isFalse();assertThat(json.get("reviewable").asBoolean()).isTrue();
            assertThat(json.get("eligibilityMessage").asText()).contains("동의 기록 없음");
        }
        match.setGalleryStatus("APPROVED");assertThat(match.isRevocable()).isTrue();assertThat(match.isRejectable()).isFalse();
        match.setEligible(true);match.setEligibilityReason(null);assertThat(match.isApprovable()).isTrue();
        match.setEligible(null);assertThat(match.isApprovable()).isFalse();
        match.setGalleryStatus("NONE");match.setEligible(true);assertThat(match.isApprovable()).isFalse();
    }
    @Test void pendingAndApprovedHaveOperationsEvenWhenApprovalIsRestricted() {
        var photo=new ReviewPhotoMatch();photo.setEligible(true);
        for(String status:java.util.List.of("PENDING","APPROVED")){photo.setGalleryStatus(status);assertThat(photo.isReviewable()).isTrue();}
        for(String status:java.util.List.of("NONE","REJECTED","REVOKED")){photo.setGalleryStatus(status);assertThat(photo.isReviewable()).isFalse();assertThat(photo.getEligibilityMessage()).contains("참고용");}
        photo.setGalleryStatus("APPROVED");photo.setEligibilityReason("REVIEW_DELETED");assertThat(photo.isReviewable()).isTrue();
        photo.setGalleryStatus("PENDING");photo.setEligible(false);assertThat(photo.isReviewable()).isTrue();
    }
    @Test void eachRestrictionReportsItsActualCauseAndUnknownNeverMeansConsented() {
        var p=new ReviewPhotoMatch();
        assertThat(p.getEligibilityMessage()).contains("확인할 수 없습니다");
        p.setEligible(true);assertThat(p.getEligibilityMessage()).isNull();p.setEligible(false);
        Map.of("PHOTO_CONSENT_MISSING","사진의 갤러리 활용 동의 기록 없음","PHOTO_CONSENT_WITHDRAWN","동의 철회",
                "MEMBER_CONSENT_MISSING","회원의 갤러리 활용 동의 없음","MEMBER_CONSENT_WITHDRAWN","동의 거절·철회",
                "POLICY_UNAVAILABLE","정책 없음","REVIEW_DELETED","삭제된 리뷰","REVIEW_HIDDEN","비공개된 리뷰",
                "MEMBER_RESTRICTED","회원 상태 제한","DESTINATION_PRIVATE","여행지 비공개","REPLY_NOT_REVIEW","답글 사진")
                .forEach((code,message)->{p.setEligibilityReason(code);assertThat(p.getEligibilityMessage()).contains(message);});
    }
}
