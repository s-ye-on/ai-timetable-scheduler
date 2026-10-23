-- 기존 슬롯에는 BLUE를 적용하고 이미 저장된 사용자 선택 색상은 변경하지 않는다.
-- 서버 중지·백업·복제본 검증 후 실행한다. H2 DDL은 자동 커밋되므로 실패 시 상태를 확인한다.
SET LOCK_TIMEOUT 5000;
ALTER TABLE timetable_slot ADD COLUMN IF NOT EXISTS color VARCHAR(16) DEFAULT 'BLUE' NOT NULL;
ALTER TABLE timetable_slot ADD CONSTRAINT IF NOT EXISTS ck_timetable_slot_color
    CHECK (color IN ('BLUE', 'MINT', 'PEACH', 'LAVENDER', 'ROSE', 'LEMON', 'LIME', 'AQUA', 'APRICOT', 'SLATE'));
