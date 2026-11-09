-- 사진 행과 (comment_id, display_order) UNIQUE는 그대로 보존한다.
ALTER TABLE destination_comment_images
    DROP CHECK chk_destination_comment_images_order,
    ADD CONSTRAINT chk_destination_comment_images_order
        CHECK (display_order BETWEEN 1 AND 5);
