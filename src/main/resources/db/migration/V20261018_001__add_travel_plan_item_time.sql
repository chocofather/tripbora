-- 기존 자유 텍스트와 tag는 보존한다. tag의 NULL은 애플리케이션에서 SCHEDULE로 표시한다.
-- 시간은 여행 날짜(travel_plan_days.plan_date)에 속하는 현지 시각이며 선택 입력이다.
ALTER TABLE travel_plan_items
    ADD COLUMN start_time TIME NULL AFTER tag,
    ADD CONSTRAINT chk_travel_plan_items_start_time
        CHECK (start_time IS NULL OR (start_time >= '00:00:00' AND start_time < '24:00:00'));

ALTER TABLE travel_plan_final_items
    ADD COLUMN start_time TIME NULL AFTER tag,
    ADD CONSTRAINT chk_travel_plan_final_items_start_time
        CHECK (start_time IS NULL OR (start_time >= '00:00:00' AND start_time < '24:00:00'));
