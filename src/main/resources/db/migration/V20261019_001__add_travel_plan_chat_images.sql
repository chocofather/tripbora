-- 기존 텍스트 메시지는 NULL 식별자/첨부 0건으로 그대로 유지한다.
ALTER TABLE travel_plan_chat_messages
    ADD COLUMN client_request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD UNIQUE KEY uq_travel_plan_chat_request (travel_plan_id, sender_member_id, client_request_id);

CREATE TABLE travel_plan_chat_images (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id BIGINT NOT NULL,
    storage_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    content_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    byte_size BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    sort_order TINYINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_travel_plan_chat_image_key (storage_key),
    UNIQUE KEY uq_travel_plan_chat_image_order (message_id, sort_order),
    CONSTRAINT fk_travel_plan_chat_image_message FOREIGN KEY (message_id)
        REFERENCES travel_plan_chat_messages (id) ON DELETE CASCADE,
    CONSTRAINT chk_travel_plan_chat_image_order CHECK (sort_order BETWEEN 0 AND 4),
    CONSTRAINT chk_travel_plan_chat_image_size CHECK (byte_size > 0 AND byte_size <= 10485760),
    CONSTRAINT chk_travel_plan_chat_image_dimensions CHECK (width > 0 AND height > 0),
    CONSTRAINT chk_travel_plan_chat_image_type CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
