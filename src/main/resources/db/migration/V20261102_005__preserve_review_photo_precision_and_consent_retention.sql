-- 적용된 .004는 유지한다. 사진 생성 시각 정밀도와 동의 이력 보관기간 정리를 별도 반영한다.
-- gallery_consent_id / gallery_consent_at은 .004에서 이미 추가·변경했으므로 반복하지 않는다.
ALTER TABLE destination_comment_images
    MODIFY COLUMN created_at TIMESTAMP(6) NULL DEFAULT CURRENT_TIMESTAMP(6);

ALTER TABLE destination_comment_images
    DROP FOREIGN KEY fk_destination_comment_image_consent;

ALTER TABLE destination_comment_images
    ADD CONSTRAINT fk_destination_comment_image_consent
        FOREIGN KEY (gallery_consent_id) REFERENCES user_policy_consents (id) ON DELETE SET NULL;
