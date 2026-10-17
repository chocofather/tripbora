ALTER TABLE travel_plan_days
    ADD COLUMN title VARCHAR(150) NULL AFTER plan_date,
    ADD COLUMN title_version INT NOT NULL DEFAULT 0 AFTER title;
