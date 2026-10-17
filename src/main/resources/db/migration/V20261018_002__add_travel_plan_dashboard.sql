-- Shared plain-text checklist, notice and KRW expenses. Applied by Spring Boot Flyway.
CREATE TABLE travel_plan_checklist (
    id BIGINT NOT NULL AUTO_INCREMENT,
    travel_plan_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    completed TINYINT(1) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_travel_plan_checklist_plan (travel_plan_id, id),
    CONSTRAINT fk_travel_plan_checklist_plan FOREIGN KEY (travel_plan_id) REFERENCES travel_plans(id) ON DELETE CASCADE,
    CONSTRAINT chk_travel_plan_checklist_completed CHECK (completed IN (0, 1)),
    CONSTRAINT chk_travel_plan_checklist_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE travel_plan_notices (
    travel_plan_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (travel_plan_id),
    CONSTRAINT fk_travel_plan_notices_plan FOREIGN KEY (travel_plan_id) REFERENCES travel_plans(id) ON DELETE CASCADE,
    CONSTRAINT chk_travel_plan_notices_length CHECK (CHAR_LENGTH(content) <= 10000),
    CONSTRAINT chk_travel_plan_notices_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE travel_plan_expenses (
    id BIGINT NOT NULL AUTO_INCREMENT,
    travel_plan_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    amount DECIMAL(15,2) NOT NULL,
    memo VARCHAR(1000) NOT NULL DEFAULT '',
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_travel_plan_expenses_plan (travel_plan_id, id),
    CONSTRAINT fk_travel_plan_expenses_plan FOREIGN KEY (travel_plan_id) REFERENCES travel_plans(id) ON DELETE CASCADE,
    CONSTRAINT chk_travel_plan_expenses_amount CHECK (amount >= 0),
    CONSTRAINT chk_travel_plan_expenses_version CHECK (version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
