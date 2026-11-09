-- 기존 시각/버전/상태는 그대로 보존한다. 원본 이벤트를 추측해서 역기입하지 않는다.
ALTER TABLE destination_comment_images
    ADD COLUMN gallery_consent_id BIGINT NULL,
    MODIFY COLUMN gallery_consent_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_destination_comment_image_consent
        FOREIGN KEY (gallery_consent_id) REFERENCES user_policy_consents (id) ON DELETE RESTRICT;
