-- 적용된 V20261102_001의 원본을 보존하고, 해시만 계산 가능한 사진의 분석 상태를 별도 확장한다.
-- 기존 네 상태를 모두 허용하며 사진·리뷰·회원 데이터와 분석/우선순위 이력은 변경하지 않는다.
ALTER TABLE destination_comment_images
    DROP CHECK chk_review_photo_analysis_state,
    ADD CONSTRAINT chk_review_photo_analysis_state
        CHECK (analysis_state IN ('PENDING','RUNNING','READY','HASH_ONLY','FAILED'));
