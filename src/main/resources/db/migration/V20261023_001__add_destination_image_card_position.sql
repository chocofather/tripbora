ALTER TABLE destination_images
    ADD COLUMN card_position_x TINYINT UNSIGNED NOT NULL DEFAULT 50,
    ADD COLUMN card_position_y TINYINT UNSIGNED NOT NULL DEFAULT 50,
    ADD CONSTRAINT chk_destination_image_card_position
        CHECK (card_position_x BETWEEN 0 AND 100 AND card_position_y BETWEEN 0 AND 100);
