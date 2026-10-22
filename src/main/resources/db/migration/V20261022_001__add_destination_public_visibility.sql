-- 기존 여행지와 신규 등록은 공개 상태를 유지한다. 데이터 및 참조 관계는 변경하지 않는다.
ALTER TABLE destinations
    ADD COLUMN is_public TINYINT(1) NOT NULL DEFAULT 1 COMMENT '공개 여부: 1 공개, 0 숨김',
    ADD CONSTRAINT chk_destinations_is_public CHECK (is_public IN (0, 1));
