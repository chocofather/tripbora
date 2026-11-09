-- 기존 댓글과 답글의 별점은 NULL로 보존한다. 기존 참조 데이터는 변경하지 않는다.
ALTER TABLE destination_comments
    ADD COLUMN rating TINYINT UNSIGNED NULL DEFAULT NULL,
    ADD CONSTRAINT chk_destination_comment_rating
        CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    ADD CONSTRAINT chk_destination_comment_reply_rating
        CHECK (parent_comment_id IS NULL OR rating IS NULL),
    ADD INDEX idx_destination_comment_user_review (user_id, destination_id, parent_comment_id, rating);
