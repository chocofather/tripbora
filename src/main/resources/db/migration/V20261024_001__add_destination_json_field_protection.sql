CREATE TABLE destination_field_protections (
    destination_id BIGINT NOT NULL,
    field_path VARCHAR(180) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    protected_by BIGINT DEFAULT NULL,
    protected_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (destination_id, field_path),
    CONSTRAINT fk_destination_field_protection_destination
        FOREIGN KEY (destination_id) REFERENCES destinations (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
