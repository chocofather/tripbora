-- 기존 사진은 NONE으로 유지한다. 댓글 사진의 공개 여부와 갤러리 활용 동의는 별개다.
ALTER TABLE destination_comment_images
    ADD COLUMN gallery_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NONE',
    ADD COLUMN gallery_consent_at DATETIME NULL,
    ADD COLUMN gallery_consent_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN gallery_consent_withdrawn_at DATETIME NULL,
    ADD COLUMN reviewed_by BIGINT NULL,
    ADD COLUMN reviewed_at DATETIME NULL,
    ADD COLUMN review_reason VARCHAR(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL,
    ADD CONSTRAINT chk_destination_comment_image_gallery_status
        CHECK (gallery_status IN ('NONE', 'PENDING', 'APPROVED', 'REJECTED', 'REVOKED')),
    ADD CONSTRAINT chk_destination_comment_image_gallery_consent
        CHECK (gallery_status NOT IN ('PENDING', 'APPROVED') OR
               (gallery_consent_at IS NOT NULL AND gallery_consent_version IS NOT NULL
                AND gallery_consent_withdrawn_at IS NULL)),
    ADD CONSTRAINT fk_destination_comment_image_reviewer
        FOREIGN KEY (reviewed_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD INDEX idx_destination_comment_image_gallery_queue (gallery_status, created_at, id);
