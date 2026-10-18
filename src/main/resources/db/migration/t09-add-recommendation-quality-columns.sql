-- 기존 행을 변경하거나 삭제하지 않고 T09 컬럼을 추가한다.
-- 신규 컬럼의 NULL은 기존 Task와 추천 이력의 의미를 보존한다.

ALTER TABLE task ADD COLUMN IF NOT EXISTS date_required BOOLEAN;
ALTER TABLE task ADD COLUMN IF NOT EXISTS time_required BOOLEAN;
ALTER TABLE task ADD COLUMN IF NOT EXISTS preferred_start_time TIME(0);

ALTER TABLE recommendation ADD COLUMN IF NOT EXISTS match_type VARCHAR(32);
ALTER TABLE recommendation ADD CONSTRAINT IF NOT EXISTS ck_recommendation_match_type
    CHECK (match_type IS NULL OR match_type IN ('EXACT', 'TIME_CHANGED', 'DATE_CHANGED', 'DATE_AND_TIME_CHANGED', 'GENERAL'));
