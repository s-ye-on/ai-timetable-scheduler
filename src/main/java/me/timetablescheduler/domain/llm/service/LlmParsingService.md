## 현재 정책 관점 
```text
durationMinutes == null → 60분으로 보정
durationMinutes <= 0 → INVALID_LLM_DURATION
durationMinutes % 30 != 0 → INVALID_LLM_DURATION
preferredTimeRange == null → ANYTIME
priority == null → NORMAL
description == null/blank → originalMessage
```
