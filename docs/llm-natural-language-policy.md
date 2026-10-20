# LLM 자연어 해석 확정 정책

> 2026-10-21 업데이트: G29·G32·G38의 현재 정책은 맨 아래 12장을 따른다. 앞선 구현·평가 기록은 당시 상태를 보존한 기록이다.

- 확정일: 2026-10-20 (Asia/Seoul)
- 대상: timetable-scheduler 졸업전시 개선 / T09 후속 LLM Evaluation
- 상태: **사용자 확정 정책. 후속 Java 구현·격리 회귀 검증 완료. 실제 LLM 정확도 평가는 미실행.**
- 관련 문서: [개선 계획](graduation-exhibition-improvement-plan.md), [T09 구현 당시 결과](t09-recommendation-quality.md)

## 후속 구현 결과 (2026-10-20)

아래 2장과 “아직 수행하지 않은 후속 작업” 표는 **정책 확정 당시의 구현 전 점검 기록**이다. 이후 이번 후속 작업에서 다음 코드를 적용했다. 정책 의미와 6개 기대 JSON은 변경하지 않았다.

| 정책 | 이번 구현 결과 |
|---|---|
| G01 | 단독 요일의 가장 가까운 미래 날짜·정확 시각 계산. 명시한 이번 주·다음 주 보존 |
| G02 | 제목 누락을 일반 clarification보다 먼저 검사. MISSING_LLM_TITLE / 400과 지정 안내, Task 미생성 |
| G04 | 정확 시각 보존·기존 밴드 자동 추론. 전체 구간 30점과 EXACT 판정 유지 |
| G05 | 월~일 필수 허용 기간과 요일·시간대 선호 보존. 별도 허용 기간은 교집합, 마감 유지 |
| G37 | 요청 시각을 한 번 캡처하고 엄격히 이후인 01:00 계산. 선호·필수의 대안 차이 유지 |
| G39 | 다음 주 월요일을 특정 날짜로 계산. 명시하지 않은 주간 기간을 추가하지 않음 |

- `LlmParsingService.parseTask()`가 기존 Clock을 Asia/Seoul로 변환하여 한 번 읽고, `OpenAiClient.parseTask(message, requestDate)`의 프롬프트 날짜와 공유한다. 현재 시·분은 프롬프트에 추가하지 않는다.
- [LlmTemporalNormalizer](../src/main/java/me/timetablescheduler/domain/llm/service/LlmTemporalNormalizer.java)는 raw DTO를 변경하지 않고 새 정규화 DTO를 반환한다. 기존 14필드 Structured Outputs·DTO 계약과 Task·DB 스키마를 유지한다. 요일은 원본에서 요일로 추출하고, Java가 원문의 주간·기간 표현과 함께 날짜를 계산한다.
- 반복 요청은 `UNSUPPORTED_LLM_RECURRENCE` / 400으로 미지원을 안내한다. 모호한 오전·오후, 명시 시각 유실·변경, 모순된 조건은 기존 확인 오류로 차단한다.
- 수정 전 정책 테스트 39개 중 32개 실패를 재현했다. 추가 검토에서 마감 숫자와 시작 날짜의 혼동, 소요 시간의 “동안”과 날짜 기간의 혼동도 각각 실패를 재현하고 수정했다.
- 최종 Java 전체 427개·UI 29개 통과, 실패·오류·skip 0. 신규 정책 테스트 52개와 파서→API→격리 H2→추천 통합 6개, 자정 Clock/프롬프트 공유 HTTP Stub 테스트를 포함한다. 기존 테스트는 삭제·약화하지 않았다.
- 기존 추천 점수·Calendar 프로덕션 코드와 DB 설정·데이터는 변경하지 않았다. T07·T08·T10 회귀를 유지한다. 실제 OpenAI·Google API는 호출하지 않았다.

**남은 작업:** Golden Dataset 전체와 실제 API 평가 도구는 만들지 않았다. 이 구현은 확정 정책의 지원 표현을 정규화하며, 한국어 전체 의미 해석이나 LLM의 제목·필수 여부 추출 정확도를 보장하지 않는다. 실제 모델 출력과 Java 정규화 결과의 정확도는 별도 승인된 평가에서 분리 측정해야 한다.

## 1. 적용 범위와 역할

이 문서는 G01·G02·G04·G05·G37·G39와 공통 자연어 해석 정책의 기준이다. 이전 제안이나 테스트 Fixture와 충돌하면 이 문서의 확정 정책을 따른다. 과거에 실행한 테스트 결과를 새 정책의 검증 결과로 바꾸어 기록하지 않는다.

- LLM은 활동·소요 시간·필수 여부·날짜 표현·정확 시각 등 자연어 조건을 추출한다.
- Java는 요청 기준 날짜·시각 계산과 정규화, 제약 검증, 후보 생성·충돌 검사·추천 판단을 담당한다.
- **LLM 원본 응답과 Java 정규화 결과를 각각 보관·채점한다.** Java가 계산하거나 보정한 값을 LLM 해석 성공으로 계산하지 않는다.
- 필수 조건·명시한 마감일·허용 기간은 임의로 완화하지 않는다.
- 기존 Java 점수 공식·가중치·30분 후보 생성·명시적 비정규 시작 시각 지원을 유지한다.
- 추천 생성과 Calendar 등록은 분리한다. 사용자 선택 후 최신 검증을 거쳐 등록한다.

정책 최초 문서화에서는 코드·테스트·DB를 변경하거나 실행하지 않았다. 이후 Java 구현 및 격리 검증은 위 후속 구현 결과에 구분해 기록했다. 실제 OpenAI·Google API는 호출하지 않았다.

## 2. 현재 구현과 확정 정책의 차이

2026-10-20 정책 확정 시점의 Serena 정적 확인 기록이다. 아래 “필요한 후속 작업” 중 이번 구현 완료 범위는 위 후속 구현 결과를 따른다.

| 영역 | 현재 코드에서 확인한 상태 | 필요한 후속 작업 |
|---|---|---|
| 날짜 기준 | `OpenAiClient.createInstructions()`가 `LocalDate.now(Asia/Seoul)`로 날짜를 직접 읽어 LLM에 전달한다. 시·분은 전달하지 않는다. | 요청 시작 시각을 한 번 캡처하고 프롬프트 날짜와 Java 계산에 공유한다. |
| G01 | 날짜 또는 요일을 저장할 수 있지만 단독 요일을 가장 가까운 미래의 특정 날짜로 결정하는 Java 단계는 없다. | 요일·명시 주간 표현을 구분하고 G01에 따라 특정 날짜를 계산한다. |
| G02 | `MISSING_LLM_TITLE`은 이미 HTTP 400이다. 현재 안내 문구는 확정 문구와 다르며 일반 `clarificationReason` 검사가 먼저 실행된다. | 활동 누락을 전용 코드로 분류하고 확정 문구를 사용한다. Task를 생성하지 않는다. |
| G04 | 정확한 19:10 저장·후보 생성·원문 시각 검증은 존재한다. | 정확 시각에서 시간대 enum을 자동 추론하는 Java 처리를 연결한다. |
| G05 | 선호 요일과 허용 기간의 공존을 표현할 수 있다. | 월~일 주간 계산, 지난 화요일 보존, 활동 누락 처리를 연결한다. |
| G37 | `normalizePeriodHour()`는 밤 한 시를 01:00으로 비교한다. | 요청 Clock에 따른 오늘/다음 날 결정과 한 번 캡처한 기준 시각의 공유가 필요하다. |
| G39 | 특정 날짜 D의 선호 조건에서 D~D+13일을 탐색하는 정책은 존재한다. | 월요일 시작 기준으로 다음 주 월요일을 계산한다. 임의의 주간 필수 범위를 추가하지 않는다. |

코드 근거:

- [OpenAiClient](../src/main/java/me/timetablescheduler/domain/llm/external/OpenAiClient.java): `createInstructions()` / `createParsedTaskResponseSchema()`
- [LlmParsingService](../src/main/java/me/timetablescheduler/domain/llm/service/LlmParsingService.java): `parseTask()` / `validate()` / `validateSourceIntent()` / `normalizePeriodHour()`
- [Task](../src/main/java/me/timetablescheduler/domain/task/Task.java): `validateIntentCondition()` / `validateIntentBounds()`
- [RecommendationPolicy](../src/main/java/me/timetablescheduler/domain/recommendation/policy/RecommendationPolicy.java): `resolveCandidateDates()` / `matchesRequestedTime()` / `calculateScore()`
- [ExceptionCode](../src/main/java/me/timetablescheduler/global/exception/ExceptionCode.java), [ApiExceptionHandler](../src/main/java/me/timetablescheduler/global/exception/ApiExceptionHandler.java), [ErrorResponse](../src/main/java/me/timetablescheduler/global/exception/ErrorResponse.java)

## 3. 공통 해석 기준

### 3.1 요청 기준 시각과 명시적 주간 조건

- Java가 요청 시작 시각을 한 번 캡처하고 Asia/Seoul 기준으로 사용한다. 프롬프트의 오늘 날짜도 같은 기준을 사용한다.
- 한 주는 월요일 00:00부터 일요일까지다. `preferredDateRange`의 날짜 양 끝은 포함한다.
- 단독 요일과 정확 시각은 **요청 시작 시각보다 엄격히 뒤인 가장 가까운 해당 요일·시각**을 가리킨다. 충돌 여부는 날짜 해석에 섞지 않는다.
- 명시적인 “이번 주”, “다음 주”는 해당 주를 기준으로 해석한다. 이미 지났다는 이유로 다른 주로 이동하지 않는다.
- “이번 주 안에”는 실제 필수 허용 기간이다. 반면 “다음 주 월요일”은 특정 날짜를 계산하는 표현으로, 다음 주 전체를 필수 기간으로 추가하지 않는다.
- 정규화가 끝나도 추천·확정 단계의 최신 현재 시각·충돌 검사는 유지한다. 처리 중 시각이 지났다면 기존 안전장치로 처리하며, 요청 기준을 다시 읽어 해석 날짜를 몰래 바꾸지 않는다.

### 3.2 필수 조건, 반복, 모호한 표현

- 필수 표현이 없으면 날짜·시간을 임의로 필수로 바꾸지 않는다. G01·G04·G37·G39의 예시는 선호 조건이다.
- `timeRequired=true`이고 정확 시각이 있으면 그 시작 시각을 강제한다. 정확 시각이 없으면 지정 시간대에 전체 수행 구간이 들어가야 한다.
- “매주 목요일” 같은 반복 요청은 **현재 미지원**으로 안내한다. 단일 Task를 만들고 반복을 지원했다고 표시하지 않는다. `preferredDayOfWeek` 자체는 반복 일정 필드가 아니다.
- “목요일 7시”처럼 오전·오후가 불명확하면 확인을 요청한다. “반드시 1시에만”도 밤/오전/01:00 등의 맥락 없이 단독으로 쓰였다면 임의로 새벽으로 해석하지 않는다.
- 모호하거나 모순된 조건을 확인할 수 있지만, 모든 요청마다 필수 여부를 확인받는 UI는 도입하지 않는다.

### 3.3 정확 시각의 시간대 자동 추론

정확 시각에 대응하는 시간대는 기존 `PreferredTimeRange`의 경계를 사용한다. 새로운 시간대나 가중치를 추가하지 않는다.

| 시작 시각 | 자동 추론 값 |
|---|---|
| 09:00 이상~12:00 미만 | MORNING |
| 12:00 이상~14:00 미만 | LUNCH |
| 14:00 이상~18:00 미만 | AFTERNOON |
| 18:00 이상~22:00 미만 | EVENING |
| 그 밖의 시각 | null |

- 19:10은 EVENING이다. 01:00은 대응 밴드가 없으므로 null이며 ANYTIME으로 바꾸지 않는다.
- 사용자가 명시한 시간대와 정확 시각이 모순되면 자동 추론으로 원문 조건을 덮어쓰지 말고 확인을 요청한다.
- 자동 추론 사실을 UI에 별도로 설명하지 않는다. 후보의 실제 점수·변경 유형·추천 이유는 사실대로 표시한다.
- Task 시간대는 기존 코드에서 Preference 시간대보다 우선한다. 자동 추론으로 점수 입력값은 달라질 수 있지만 공식·가중치는 유지한다.
- 기존 30점은 **전체 수행 구간이 시간대 안에 들어갈 때** 적용한다. 시작 시각만 보고 무조건 30점을 주지 않는다.
- 정확 시각이 있으면 원조건 일치는 그 시작 시각을 우선 비교한다. 다른 EVENING 후보가 같은 가점을 받아도 정확 시각이 다르면 시간 변경 대안이다.

### 3.4 JSON 예시를 읽는 방법

아래 6개 JSON은 **기존 `ParsedTaskResponse` 형태를 사용한 Java 정규화 후의 Golden 기대값**이다. 실제 모델 출력·테스트 실행 결과가 아니다. JSON 키나 DB 컬럼을 새로 도입했다는 뜻도 아니다.

- 고정 예시 기준: **2026-10-19 월요일 10:00:00+09:00 / Asia/Seoul**. 문서 개정일의 실제 현재 시각이 아니라 재현 가능한 평가 기준이다.
- 정상 사례는 Java 기본값 보정 후 소요 시간·중요도를 표시한다. 원문에 없으면 LLM 원본 기대값은 각각 null이고, 정상 Java 결과는 60분·NORMAL이다.
- G02·G05는 활동 누락으로 중단할 때 보존할 조건의 기대값이다. 성공 응답 DTO나 저장된 Task가 아니며, 소요 시간·중요도의 기본값 보정 성공을 요구하지 않는다.
- 제목은 의미를 보존하는 동의 표현을 허용한다. description은 원문을 그대로 유지한다.
- LLM 원본 Golden은 명시 조건 추출과 확인 필요 여부를 채점한다. 중간 날짜 표현과 Java 정규화의 인터페이스는 평가 도구 구현 전에 고정하고 기록한다. 아래 정규화 JSON을 LLM 원본과 그대로 비교하지 않는다.

## 4. G01 — 가장 가까운 미래의 목요일

입력: **“목요일 저녁 7시에 알고리즘 공부 2시간”**

반복 일정이 아닌 가장 가까운 미래의 목요일 19:00으로 해석한다. 예시 기준에서는 2026-10-22다. 해당 목요일의 19:00과 같거나 이미 지난 시각이면 다음 목요일을 사용한다. 명시적인 이번 주·다음 주가 있으면 그 조건을 먼저 지킨다.

```json
{
  "title": "알고리즘 공부",
  "category": "STUDY",
  "durationMinutes": 120,
  "preferredDate": "2026-10-22",
  "preferredDayOfWeek": null,
  "preferredDateRange": null,
  "preferredTimeRange": "EVENING",
  "deadline": null,
  "priority": "NORMAL",
  "description": "목요일 저녁 7시에 알고리즘 공부 2시간",
  "dateRequired": false,
  "timeRequired": false,
  "preferredStartTime": "19:00",
  "clarificationReason": null
}
```

Java 기대 결과: 정상 Task 생성이 가능하며 10/22 19:00~21:00을 원조건으로 판단한다. 충돌 검증 전 성공을 보장하지 않는다. 선호 날짜가 D=10/22이므로 별도 제한이 없을 때 대안 탐색은 **10/22~11/04**다. 기존 요일 Fixture에서 나왔던 10/19 월요일 대안은 이 확정 정책의 정답이 아니다.

## 5. G02 — 활동 누락과 날짜·시간 필수

입력: **“반드시 목요일 저녁 7시에만 가능해”**

가장 가까운 목요일 19:00이며 날짜·시간 모두 필수다. 무엇을 할지 없으므로 제목·카테고리를 임의로 채우지 않고 Task를 생성하지 않는다.

```json
{
  "title": null,
  "category": null,
  "durationMinutes": null,
  "preferredDate": "2026-10-22",
  "preferredDayOfWeek": null,
  "preferredDateRange": null,
  "preferredTimeRange": "EVENING",
  "deadline": null,
  "priority": null,
  "description": "반드시 목요일 저녁 7시에만 가능해",
  "dateRequired": true,
  "timeRequired": true,
  "preferredStartTime": "19:00",
  "clarificationReason": "어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요."
}
```

Java 기대 결과: **HTTP 400 / `MISSING_LLM_TITLE`**, 사용자 안내는 위 문구 그대로다. Task·추천·Calendar 이벤트를 생성하지 않는다. 서버 장애 500이나 날짜·시간이 모호하다는 일반 안내로 처리하지 않는다.

기존 전용 코드를 재사용한다. 현재 일반 clarification 검사가 먼저 실행되므로 오류 분류 순서를 보완해야 한다. 또한 `ErrorResponse.of()`는 `ExceptionCode`의 메시지를 사용하므로 개별 예외 생성자에 문구만 전달하는 것으로는 충족되지 않는다. 자연어 clarification 문장을 문자열 분류기로 해석해 오류 종류를 결정하는 구현을 전제로 하지 않는다.

## 6. G04 — 19:10 보존과 EVENING 자동 추론

입력: **“목요일 19시 10분에 공부하고 싶어”**

가장 가까운 미래 목요일 19:10으로 해석한다. 30분 격자에 맞추어 19:00·19:30으로 바꾸지 않는다. EVENING을 자동 추론하며, 소요 시간은 원문에 없으므로 정상 보정 후 60분이다.

```json
{
  "title": "공부",
  "category": "STUDY",
  "durationMinutes": 60,
  "preferredDate": "2026-10-22",
  "preferredDayOfWeek": null,
  "preferredDateRange": null,
  "preferredTimeRange": "EVENING",
  "deadline": null,
  "priority": "NORMAL",
  "description": "목요일 19시 10분에 공부하고 싶어",
  "dateRequired": false,
  "timeRequired": false,
  "preferredStartTime": "19:10",
  "clarificationReason": null
}
```

Java 기대 결과: 10/22 19:10~20:10은 다른 검증을 통과하면 원조건 후보다. 전체 구간이 EVENING에 포함되어 기존 30점 시간대 가점 대상이다. 정확 시작이 다른 후보는 EVENING이어도 EXACT가 아니다. 자동 추론 사실을 별도 UI 문구로 설명할 필요는 없다. 후보 존재·순위·총점은 다른 일정·Preference·마감 등에 따라 달라지므로 이 문서에서 고정하지 않는다.

## 7. G05 — 이번 주 허용 기간과 화요일 점심 선호

입력: **“이번 주 안에 화요일 점심이면 좋겠어”**

이번 주 월요일~일요일 전체가 반드시 지킬 허용 기간이다. 화요일·점심은 선호다. 화요일이 지났어도 다음 주로 이동하지 않는다. 활동명이 없으므로 G02와 동일하게 확인을 요청한다.

```json
{
  "title": null,
  "category": null,
  "durationMinutes": null,
  "preferredDate": null,
  "preferredDayOfWeek": "TUESDAY",
  "preferredDateRange": {
    "startDate": "2026-10-19",
    "endDate": "2026-10-25"
  },
  "preferredTimeRange": "LUNCH",
  "deadline": null,
  "priority": null,
  "description": "이번 주 안에 화요일 점심이면 좋겠어",
  "dateRequired": false,
  "timeRequired": false,
  "preferredStartTime": null,
  "clarificationReason": "어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요."
}
```

Java 기대 결과: **HTTP 400 / `MISSING_LLM_TITLE`**, Task 생성 없음. 두 필수 여부가 false라도 `preferredDateRange`는 필수 허용 기간이다. 기간을 필수로 만들기 위해 `dateRequired=true`로 바꾸지 않는다. 이는 화요일 자체를 필수로 만드는 의미가 된다.

활동이 포함된 별도 정상 요청이라면 이번 주 남은 유효한 시간만 탐색한다. 예를 들어 기준이 10/21 수요일이어도 허용 기간은 10/19~25이고 선호 요일은 TUESDAY다. 지난 화요일의 원조건 후보는 없지만 다른 요일의 대안을 허용하며, 10/27 화요일로 바꾸지 않는다.

## 8. G37 — 요청 이후 가장 가까운 01:00

입력: **“오늘 밤 한 시에 공부 1시간”**

Java의 Asia/Seoul Clock으로 요청 시작 시각보다 엄격히 뒤인 가장 가까운 01:00을 계산한다. 정확히 01:00이면 다음 날 01:00이다. 한 번 캡처한 기준을 일관되게 사용하고, LLM에 현재 시·분을 추가로 전달하는 방식은 우선 지양한다.

```json
{
  "title": "공부",
  "category": "STUDY",
  "durationMinutes": 60,
  "preferredDate": "2026-10-20",
  "preferredDayOfWeek": null,
  "preferredDateRange": null,
  "preferredTimeRange": null,
  "deadline": null,
  "priority": "NORMAL",
  "description": "오늘 밤 한 시에 공부 1시간",
  "dateRequired": false,
  "timeRequired": false,
  "preferredStartTime": "01:00",
  "clarificationReason": null
}
```

| 요청 시작 시각 (Asia/Seoul) | Java 정규화 결과 |
|---|---|
| 2026-10-19 00:30 | 2026-10-19 01:00 |
| 2026-10-19 01:00 | 2026-10-20 01:00 |
| 2026-10-19 10:00 | 2026-10-20 01:00 |
| 2026-10-19 23:59 | 2026-10-20 01:00 |
| 2026-10-31 23:59 | 2026-11-01 01:00 |
| 2026-12-31 23:59 | 2027-01-01 01:00 |

Java 기대 결과:

- 원조건은 위 날짜의 01:00~02:00이다. 01:00에 대응하는 기존 시간대가 없으므로 null로 둔다.
- 필수 표현이 없으므로 `timeRequired=false`이며, 01:00이 가용 시간 밖이어도 다른 시간대 대안은 허용한다.
- 밤/새벽 맥락이 명확한 **“오늘 밤 반드시 한 시에만 공부 1시간”**은 `timeRequired=true`로 처리한다. 다른 시간은 추천하지 않는다. 날짜까지 필수인지는 원문의 날짜 필수 표현에 따라 별도로 판단한다.
- 가용 시간이 09~22시이면 정확 01:00 후보가 없는 것은 정상이다. 선호 요청은 낮 시간 대안이 가능하고, 필수 시각 요청은 그 시각을 지킬 수 없으면 후보가 없다.
- **시각 해석 성공과 추천 후보 존재 여부는 별도 지표**다. 후보 없음 자체를 LLM 해석 오류로 세지 않는다.
- LLM이 문자 그대로 오늘 날짜를 반환하더라도 이 정책에 따른 Java의 다음 날 정규화를 정상 처리로 구분한다. 다른 시각·명시 날짜·마감·허용 기간과의 실제 모순까지 일괄 덮어쓰지 않는다.

## 9. G39 — 다음 주 월요일의 특정 날짜

입력: **“다음 주 월요일 점심에 공부 1시간”**

한 주는 월요일~일요일이다. 예시 기준에서 다음 주 월요일은 2026-10-26이다. 별도 필수 기간을 추가하지 않으며 기존 D~D+13일 대안 정책을 유지한다.

```json
{
  "title": "공부",
  "category": "STUDY",
  "durationMinutes": 60,
  "preferredDate": "2026-10-26",
  "preferredDayOfWeek": null,
  "preferredDateRange": null,
  "preferredTimeRange": "LUNCH",
  "deadline": null,
  "priority": "NORMAL",
  "description": "다음 주 월요일 점심에 공부 1시간",
  "dateRequired": false,
  "timeRequired": false,
  "preferredStartTime": null,
  "clarificationReason": null
}
```

Java 기대 결과: 10/26 점심을 우선 원조건으로 삼고 별도 제한이 없으면 **10/26~11/08**에서 대안을 찾는다. “다음 주”를 날짜 계산에 사용했다는 이유만으로 허용 기간을 10/26~11/01로 제한하지 않는다. “다음 주 안에만”이라는 별도 조건이 있을 때는 그 필수 기간을 지킨다.

## 10. Golden Dataset의 확정 정답과 평가 경계

기존 평가 설계에서 정책 보류였던 여섯 사례의 의미 정답은 이 문서로 확정한다. 중간 표현의 필드 계약과 실행 도구는 아직 구현하지 않았다.

| ID | 원본 LLM에서 평가할 핵심 추출 사실 | Java 정규화·최종 처리 정답 |
|---|---|---|
| G01 | 목요일·저녁·19:00·120분·비반복·필수 표현 없음 | 예시 기준 10/22 19:00, 특정 날짜로 정규화. 기존 월요일 대안 Fixture를 정답으로 쓰지 않음 |
| G02 | 목요일·19:00·날짜/시간 필수·활동 없음 | 조건은 보존하되 HTTP 400 / MISSING_LLM_TITLE, 확정 안내 문구, Task 없음 |
| G04 | 목요일·정확 19:10·공부·소요 시간 미지정 | 10/22 19:10, EVENING, 정상 보정 후 60분. 120분인 과거 Fixture와 구분 |
| G05 | 이번 주 안이라는 제한·화요일/점심 선호·활동 없음 | 월~일 허용 기간, TUESDAY/LUNCH 선호, 다음 주 이동 없음. HTTP 400 / MISSING_LLM_TITLE, Task 없음 |
| G37 | 오늘 밤·한 시=01:00·60분·필수 표현 없음 | 요청 Clock에 따른 가장 가까운 미래 01:00, 기본 timeRequired=false. 정확 후보 유무와 별도 채점 |
| G39 | 다음 주 월요일·점심·60분·필수 기간 표현 없음 | 10/26 특정 날짜, range=null, D~D+13 대안 탐색 |

평가 원칙:

1. Golden 기대값은 사용자 정책에서 독립적으로 정의하고 실제 모델 출력에 맞추어 바꾸지 않는다.
2. 원본 JSON 계약 준수, DTO 변환, 명시 조건 추출, Java 정규화, 최종 오류/Task 생성 가능 여부를 분리한다.
3. Java가 추가한 시간대·계산한 날짜·기본값을 원본 LLM 정확도에 합산하지 않는다.
4. `dateRequired`/`timeRequired` 누락, 명시 시각 변경, 필수 조건 완화, 모호한 시각의 임의 확정은 중대 오류로 집계한다.
5. G02·G05는 일반 파싱 실패나 500으로 막힌 것만으로 통과하지 않는다. 정해진 코드·문구와 Task 미생성을 확인한다.
6. G37에 자정·01:00 직전/정각/직후·월/연도 경계 테스트를 둔다. G01에는 목요일 요청 시각 직전/정각/직후와 명시 주간 제한을 둔다.
7. 일반 “목요일 7시”의 확인 요청과 “오늘 밤 한 시”의 확정된 Java 규칙을 혼동하지 않는다.
8. 실제 API 평가와 기존 Mock/Fixture 회귀를 구분한다. 별도 승인 전 실제 API를 호출하거나 새로운 정확도 수치를 기록하지 않는다.

## 11. 기존 안정성 정책과 후속 작업

### 유지할 사항

- 기존 H2 데이터와 nullable 레거시 필드의 의미를 보존한다. 저장된 Task를 이 문서만으로 소급 재해석하거나 기존 추천 이력을 삭제하지 않는다.
- T06 조건 변경 시 PROPOSED 무효화, T07 최신 충돌 검사·사용자 단위 락, T08 멱등성·불확실 이벤트 확인·복구, T10 서버 상태 복원을 유지한다.
- P01: 미연동 추천 가능 / Google 일정 미반영 안내. P02: 확정은 연결 필수. P03: 연결된 FreeBusy 실패는 빈 일정으로 취급하지 않고 차단. P04: 연결 전 후보도 연결 후 최신 검증을 거친다.
- 내부 원점수·가중치·최종 정렬을 보존하고 표시만 140점 기준 100점 척도로 환산한다. 점수는 확률이 아니다.
- 추천만으로 Calendar에 등록하지 않는다. 사용자 선택 후 기존 검증·복구 흐름을 사용한다.

### 아직 수행하지 않은 후속 작업

| 작업 | 완료 기준 |
|---|---|
| 요청 시간·프롬프트 | 한 번 캡처한 기준으로 프롬프트 날짜와 Java 계산이 일치함. LLM에 현재 시·분을 추가하는 방식은 우선 사용하지 않음 |
| 추출·정규화 연결 | LLM 중간 표현 계약을 명시하고 기존 DTO·Task에 확정 날짜·시간대를 전달함. 일반 NLP 엔진이나 반복 일정 기능을 새로 만들지 않음 |
| G02/G05 오류 | 제목 누락 코드·정확한 안내·400·Task 미생성 확인 |
| 경계·추천 회귀 | G01/G37 날짜 경계, 명시 주간·필수 조건, G04 가점/EXACT 판정, 가용 시간 밖 선호/필수 대안 차이 확인 |
| Golden·평가 도구 | 위 여섯 정답을 반영하고 LLM 원본과 Java 결과를 분리 저장·채점함. 일반 회귀에서 실제 API가 자동 실행되지 않음 |
| 실제 LLM 평가 | 별도 승인 후 기존 모델·프롬프트/스키마 버전을 기록해 반복 실행. 중대 오류·Java 차단·오차단을 각각 보고 |

최초 문서 커밋은 정책 확정의 기록이다. 후속 코드 구현·회귀 완료와 아직 수행하지 않은 전체 Golden Dataset·실제 LLM 평가는 위 후속 구현 결과로 구분한다.


## 12. G29·G32·G38 확정 정책 적용 (2026-10-21)

- G29: 시작 시각의 초 표기는 일반·필수 요청 및 명시적 00초 모두 HTTP 400 / `UNSUPPORTED_LLM_START_SECONDS`로 거절한다. 안내는 “초 단위 시작 시간은 지원하지 않아요. 분 단위로 입력해 주세요.”이다. 초 절삭·자동 변환·secondsRequired 필드는 도입하지 않는다. “30초 영상 편집” 같은 활동명은 허용한다.
- G32: “월/화 중 하루”는 기존 확인 요청을 유지하며 연속 기간으로 바꾸지 않는다.
- G38: “한 시간 뒤에 공부할래”와 “한 시간 뒤부터 공부할래”는 활동 시작 의도 `EXACT`이다. 가용 시간 하한인 “한 시간 뒤부터 가능한 공부 시간 추천해 줘”는 `NOT_BEFORE`이며 HTTP 400 / `UNSUPPORTED_LLM_RELATIVE_START_RANGE`로 안내한다. 활동명이 없으면 `MISSING_LLM_TITLE`이 우선한다.

LLM JSON은 기존 14필드에 nullable `relativeStartMode`(EXACT / NOT_BEFORE), `startOffsetMinutes`(간격의 분 수)를 추가한 16필드 계약이다. 상대 간격과 활동 소요 시간은 별개다. “한 시간 뒤에 공부 2시간”은 간격 60분, 소요 120분이며 원본 preferredStartTime은 null이다. 상대 요청이 없으면 두 신규 필드는 null이다. 기존 DTO 생성자와 Task API·저장 모델은 유지한다.

Java는 요청 시작 시각을 Asia/Seoul Clock으로 한 번만 캡처해 사용한다. 간격을 더한 뒤 초·나노초가 남으면 다음 분으로 올린다. 예를 들어 10:00:45 + 60분은 11:01로 처리한다. 계산 결과를 기존 preferredDate/preferredStartTime과 dateRequired=true/timeRequired=true로 저장한다. 허용 기간·마감일과 모순되면 거절하며 조건을 완화하지 않는다. 재추천·새로고침·확정·복구에서 간격을 다시 계산하지 않는다.

고정 시각이 충돌하거나 가용 시간 밖이면 다른 시간으로 대체하지 않는다. 목표 시각이 이미 지났다면 추천·신규 등록을 차단한다. 이미 생성된 일치 이벤트의 T08 복구는 기존 ID·시각을 유지한다. T07 사용자 락·최신 검증, T08 멱등성, T10 서버 상태 복원과 기존 점수 계산은 보존한다.

UI는 기존 서버 응답의 고정 날짜·시각을 표시하고 비차단 안내만 추가한다. 계산 이력을 DB나 브라우저에 새로 저장하지 않으며, 복원한 기존 고정 일정에도 사실인 공통 문구를 사용한다. DB 컬럼·마이그레이션은 추가하지 않는다.

Golden v1과 보관된 실측 결과는 유지한다. 새 정책 정답은 [Golden v2](../src/test/resources/llm/golden-dataset-v2.json)에 분리했다. HTTP Stub·격리 H2·UI 회귀로 검증하며 실제 OpenAI·Google API는 호출하지 않는다. 실제 모델의 새 정책 해석 정확도는 재평가 전 미검증이다. Java의 제한된 원문 검사는 조건 추출 누락을 막는 방어이며 한국어 의미나 숫자를 다시 파싱하지 않는다.
