-- 선택 항공/숙소 상세. 기존 content, tag, start_time 및 완료본을 그대로 보존한다.
ALTER TABLE travel_plan_items
    ADD COLUMN detail_json JSON NULL AFTER start_time;

ALTER TABLE travel_plan_final_items
    ADD COLUMN detail_json JSON NULL AFTER start_time;
