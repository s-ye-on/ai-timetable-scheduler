# LLM 자연어 해석 Golden Dataset v1

- 작성일: 2026-10-20 / 기준 구현: `e384270`
- 데이터: [golden-dataset-v1.json](../src/test/resources/llm/golden-dataset-v1.json)
- 정책: [확정 자연어 정책](llm-natural-language-policy.md), [T09 당시 구현·Fixture 보고서](t09-recommendation-quality.md)
- 상태: **정답 데이터 작성·정적 검증. 실제 LLM 응답이나 정확도 측정 결과가 아니다.**

## 1. 구성과 출처

이전 대화의 “T09 후속 — LLM 자연어 해석 정확도 평가 설계” 최종 답변에서 G01~G40의 입력과 A~F 기준 시각을 복원했다. 기존 문장·ID·기준은 모두 유지했다. 이후 확정된 G01·G02·G04·G05·G37·G39의 의미 정답을 반영했으며, 정책 승인 대기인 사례는 없다. 원본 대화 식별자는 JSON의 provenance에 기록했다.

| 유형 | 개수 | 의미 |
|---|---:|---|
| NORMAL | 17 | 정상 조건·필수/선호·소요 시간·중요도 |
| BOUNDARY | 10 | 밴드·자정·월말·연말·과거·일요일 경계 |
| AMBIGUOUS | 4 | 활동 누락·오전/오후 모호함·필수 조건 모순 |
| UNSUPPORTED | 9 | OR·제외·초 단위·20분·상대 시각·임의 시간 구간 |

정상 Task 생성 가능 27개, 확인 요청 13개다. 유형과 처리 결과는 다른 축이다. 예를 들어 과거 날짜 G25는 날짜 해석에 성공하고 Task를 생성할 수 있지만 과거 후보를 추천해도 된다는 의미는 아니다. 중요도 CRITICAL/HIGH/NORMAL은 평가 결함의 중요도이며 Task의 priority와 다르다.

## 2. JSON 구조

| 경로 | 내용 |
|---|---|
| datasetVersion / provenance | 버전·기준 커밋·원래 설계 출처·모델 출력 미사용 |
| contract | 현재 ParsedTaskResponse의 14개 키·enum·날짜/시각 표기 |
| referenceContexts | A~F의 고정 시각, 모두 Asia/Seoul·+09:00 |
| cases[].id / input / reference | ID·원문·각 사례의 고정 요청 시각 |
| cases[].type / importance / evaluationAspects | 유형·중요도·평가 항목 |
| cases[].llmExpected.parsedTaskResponse | Java 실행 전 원본 응답의 완전한 14필드 canonical JSON |
| cases[].llmExpected.fieldRules | 동의 표현·확인 사유·일부 필드의 허용값과 채점 범위 |
| cases[].javaExpected.normalized | 성공 시 정규화 후 14필드 JSON, 중단 시 null |
| cases[].javaExpected.fieldRules | 정규화 후에도 보존되는 제목의 의미 동등성 |
| cases[].javaExpected.outcome / taskCreatable | CREATE_TASK 또는 CLARIFICATION, Task 생성 가능 여부 |
| cases[].javaExpected.errorCode / httpStatus | 유효한 인증·사용자가 있을 때 API 처리의 기대값 |
| cases[].rationale / criticalFailures / notes | 정책 근거·중대 오류·현재 구현상의 해석 주의점 |

향후 실행에서는 각 reference.requestedAt을 Asia/Seoul Clock으로 고정하고, 같은 기준 날짜를 OpenAiClient와 Java 정규화에 전달해야 한다. 프롬프트만 실제 오늘 날짜를 쓰는 실행은 이 데이터의 유효한 평가가 아니다.

원본/성공 정규화 DTO는 키 생략 없이 14필드를 가진다. 오류 사례의 normalized=null은 기대값을 빠뜨린 것이 아니라 **현재 서비스가 정규화 DTO를 반환하기 전에 중단한다는 정답**이다. G02·G05 정책 문서의 조건 JSON을 실제 반환 DTO로 오인하지 않는다. HTTP 201은 Task 생성이 가능한 입력이라는 API 계약상의 기대값이며, 이 파일을 작성하며 실제 저장·호출한 결과는 아니다.

계약 근거: [ParsedTaskResponse](../src/main/java/me/timetablescheduler/domain/llm/dto/ParsedTaskResponse.java), [OpenAiClient](../src/main/java/me/timetablescheduler/domain/llm/external/OpenAiClient.java), [LlmParsingService](../src/main/java/me/timetablescheduler/domain/llm/service/LlmParsingService.java), [LlmTemporalNormalizer](../src/main/java/me/timetablescheduler/domain/llm/service/LlmTemporalNormalizer.java).

## 3. 원본과 Java 결과를 채점하는 방법

1. 먼저 실제 Structured Outputs의 14개 required 키·nullable 타입·enum·추가 필드 금지와 DTO 변환 가능 여부를 검사한다. 각 필드의 canonical 값은 기본적으로 정확히 비교한다.
2. 개별 fieldRules가 canonical 값보다 우선한다. 제목은 활동·주제를 유지하는 동의 표현을 허용하며 acceptedExamples는 예시 목록이다. 목록 밖 표현은 사람이 검토하고 결과를 기록한다. 빈 제목이나 다른 활동·알고리즘 주제 누락은 동등하지 않다.
3. description은 원문과 정확히 비교한다. clarificationReason은 비어 있지 않고 requiredConcepts의 사유를 설명해야 하며 문구 일치는 요구하지 않는다. “확인이 필요합니다”만 있고 무엇을 확인할지 설명하지 않으면 해당 의미 항목을 통과시키지 않는다.
4. 날짜·허용 기간·마감·명시 정확 시각·해석 가능한 필수 여부는 엄격하게 비교한다. HH:mm:00은 실제 DTO에서 HH:mm과 같은 시각이지만, 30초나 소수 초를 절삭하여 같다고 판정하지 않는다.
5. G04 원본 밴드는 null이 canonical이고 EVENING도 계약상 허용하지만 raw 의미 정확도에서 제외한다(CONTRACT_ONLY). 19:10과 요일 추출이 원본 평가 대상이며, EVENING·10/22·기본 60분/NORMAL은 Java 결과에서 검사한다.
6. G37 원본 날짜는 null이 canonical이다. 현 계약에서 오늘 날짜나 다음 날 날짜를 반환해도 이 필드는 raw 의미 정확도에 포함하지 않는다(CONTRACT_ONLY). 모델에는 시·분을 주지 않으므로 “요청 이후 가장 가까운 01:00”은 Java 결과에서만 판정한다. 시각 01:00·필수 여부·활동·60분은 별도로 엄격하게 비교한다.
7. G05의 원본 ISO 허용 기간과 G06/G11/G12/G21~G24의 상대 날짜는 **현 프롬프트가 원본 응답에 요구하는 값**이다. 원본 날짜를 임의로 Java 결과에서 복사한 것이 아니다. Java도 같은 기준 날짜로 기간·상대일을 계산하므로 raw 값과 Java 최종 값을 따로 채점하며, Java가 보정한 결과를 LLM 원본 정답으로 소급하지 않는다. 현재 14필드 계약에는 “이번 주/내일”이라는 별도 의미 토큰이 없어 이 표현의 중간 의도를 독립 필드로 측정하는 데 한계가 있다.
8. G03은 활동명도 없어 Java 오류가 MISSING_LLM_TITLE이다. LLM은 여전히 오전·오후 모호함을 알려야 한다. G03/G40의 dateRequired는 이미 확인이 필요한 요청에서 날짜 강제 범위를 임의 확정하지 않도록 NOT_APPLICABLE로 둔다. 이를 정상 요청의 필수 조건 완화 허용으로 확대하지 않는다.
9. G28의 canonical durationMinutes는 null이다. 20을 반환하는 것도 확인 사유를 보존하면 허용하지만 30/60으로 반올림하면 중대 오류다. Structured Outputs의 실제 스키마는 nullable integer이며 30분 단위 검사는 Java/프롬프트의 의미 규칙이다.
10. LLM 의미 오류, Java 안전 차단, 기대 오류 코드, Task 생성 여부는 분리한다. 예를 들어 20분을 Java가 INVALID_LLM_DURATION으로 막아도 기대한 LLM 확인 요청의 성공으로 세지 않는다. CONTRADICTION 등 필요한 사유 없이 다른 오류로 거절한 것도 정상 해석 성공이 아니다.

G02·G03·G05의 서버 안내는 정확히 “어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요.”다. 나머지 확인 사례는 LLM_CLARIFICATION_REQUIRED / 400을 기대한다. Task 미생성이 핵심이며, 잘못된 입력을 500으로 막은 것만으로 통과시키지 않는다.

## 4. 40개 사례 검토표

아래 기대 결과는 정책에서 정한 정답이며 실제 모델 실행 결과가 아니다. 모든 사례의 기준 시각은 JSON에 중복 없이 참조만 둔 것이 아니라 각 reference에도 명시했다.

| ID | 유형 | 입력 | Java 기대 처리 |
|---|---|---|---|
| G01 | 정상 | 목요일 저녁 7시에 알고리즘 공부 2시간 | Task 가능 · 2026-10-22 19:00 |
| G02 | 확인 | 반드시 목요일 저녁 7시에만 가능해 | MISSING_LLM_TITLE / 400, Task 없음 |
| G03 | 확인 | 목요일 7시에만 가능해 | MISSING_LLM_TITLE / 400, Task 없음 |
| G04 | 정상 | 목요일 19시 10분에 공부하고 싶어 | Task 가능 · 2026-10-22 19:10 |
| G05 | 확인 | 이번 주 안에 화요일 점심이면 좋겠어 | MISSING_LLM_TITLE / 400, Task 없음 |
| G06 | 정상 | 내일 오후에 과제 2시간 | Task 가능 · 2026-10-20 |
| G07 | 정상 | 과제할 시간 1시간 추천해 줘 | Task 가능 |
| G08 | 정상 | 밤 한 시에 공부하고 싶어 | Task 가능 01:00 |
| G09 | 정상 | 2026년 10월 22일 저녁 7시에 공부 2시간 | Task 가능 · 2026-10-22 19:00 |
| G10 | 정상 | 2026년 10월 22일에만 가능하고 저녁 7시에만 공부 2시간 | Task 가능 · 2026-10-22 19:00 |
| G11 | 정상 | 오늘 오전에 공부 1시간 | Task 가능 · 2026-10-19 |
| G12 | 정상 | 모레 오후에 공부 한 시간 반 | Task 가능 · 2026-10-21 |
| G13 | 정상 | 2026년 10월 20일부터 23일 사이에만 공부 1시간 | Task 가능 · 2026-10-20~2026-10-23 |
| G14 | 정상 | 2026년 10월 22일 점심에 공부 1시간이면 좋겠어. 10월 20일부터 25일 사이만 가능해 | Task 가능 · 2026-10-22 · 2026-10-20~2026-10-25 |
| G15 | 정상 | 2026년 10월 23일까지 끝내야 하는 과제 2시간 | Task 가능 · 마감 2026-10-23 |
| G16 | 정상 | 중요도가 높은 공부 1시간 추천해 줘 | Task 가능 |
| G17 | 정상 | 중요도가 낮은 공부 30분 추천해 줘 | Task 가능 |
| G18 | 경계 | 2026년 10월 22일 오전 9시 10분에 공부 1시간 | Task 가능 · 2026-10-22 09:10 |
| G19 | 경계 | 2026년 10월 22일 점심 12:00에 공부 1시간 | Task 가능 · 2026-10-22 12:00 |
| G20 | 경계 | 2026년 10월 22일 저녁 18:00에 공부 1시간 | Task 가능 · 2026-10-22 18:00 |
| G21 | 경계 | 내일 오후에 공부 1시간 | Task 가능 · 2026-10-20 |
| G22 | 경계 | 내일 오후에 공부 1시간 | Task 가능 · 2026-10-21 |
| G23 | 경계 | 내일 저녁에 공부 1시간 | Task 가능 · 2026-11-01 |
| G24 | 경계 | 내일 저녁에 공부 1시간 | Task 가능 · 2027-01-01 |
| G25 | 경계 | 어제 저녁에 공부 1시간 | Task 가능 · 2026-10-18 |
| G26 | 정상 | 알고리즘 공부 30분 추천해 줘 | Task 가능 |
| G27 | 정상 | 알고리즘 공부 한 시간 반 추천해 줘 | Task 가능 |
| G28 | 미지원 | 공부할 시간 20분 추천해 줘 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G29 | 미지원 | 2026년 10월 22일 저녁 7시 10분 30초에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G30 | 미지원 | 월요일 또는 화요일에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G31 | 미지원 | 월요일이나 화요일에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G32 | 미지원 | 월/화 중 하루에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G33 | 미지원 | 화요일을 제외하고 이번 주에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G34 | 미지원 | 오후 7시 이후에 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G35 | 확인 | 2026년 10월 22일에만 공부 1시간. 마감은 2026년 10월 21일이야 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G36 | 정상 | 2026년 10월 22일 오후에만 공부 2시간 | Task 가능 · 2026-10-22 |
| G37 | 경계 | 오늘 밤 한 시에 공부 1시간 | Task 가능 · 2026-10-20 01:00 |
| G38 | 미지원 | 한 시간 뒤에 공부 1시간 잡아줘 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |
| G39 | 경계 | 다음 주 월요일 점심에 공부 1시간 | Task 가능 · 2026-10-26 |
| G40 | 미지원 | 2026년 10월 22일 13:00부터 15:00 사이에만 공부 1시간 | LLM_CLARIFICATION_REQUIRED / 400, Task 없음 |

## 5. 주요 경계와 후속 범위

- G21/G22: 자정 직전·직후의 “내일”은 각각 10/20·10/21이다. G23/G24는 월·연도 경계를 넘어 11/01·2027/01/01이다.
- G37: A 기준(10/19 10:00)에서는 10/20 01:00이다. 정확 01:00이면 다음 날이라는 규칙은 변경하지 않는다. 직전·정각·직후·월말·연말 조합은 기존 [정책 Java 테스트](../src/test/java/me/timetablescheduler/domain/llm/service/LlmNaturalLanguagePolicyTest.java)에 별도로 있으며, 기존 40개 문장·기준을 유지하기 위해 추가 사례로 부풀리지 않았다.
- G39: F는 일요일 10/25다. 다음 주 월요일은 10/26이며 허용 기간은 null이다. 추천 단계의 기본 대안은 11/08까지이고 다음 주 일요일에 임의로 자르지 않는다.
- G18~G20: 09:10 분 단위 시각과 점심 12:00·저녁 18:00 경계를 보존한다. 기존 전체 구간 30점 가점과 EXACT 판정은 변경하지 않으며 이 데이터는 후보 점수/순위를 평가하지 않는다.
- G08과 G37: 01:00 해석 성공과 해당 시각의 후보 존재는 별개다. 가용 시간 밖이어도 선호 요청은 다른 시간대 대안이 가능하다.
- G25: 과거 선호 날짜 보존과 과거 추천 차단은 별개다. Golden을 현재 구현의 추천 결과에 맞춰 미래 날짜로 바꾸지 않는다.

남은 검증 범위:

- 실제 모델 출력·필드 정확도·오차단률은 아직 측정하지 않았다. 후속 [평가 Runner](llm-evaluation-runner.md)는 구현했으며 실제 API 호출 없이 Stub으로 검증한다. Golden의 정답은 Runner 출력에 맞춰 변경하지 않는다.
- 원래 40개에는 반복 일정 문장이 없다. “매주” 미지원 정책은 유지하며 기존 Java 회귀로 다뤄진다. 실제 LLM 반복 해석 평가는 데이터셋 버전을 올릴 때 별도 사례로 추가해야 한다. 기존 ID를 바꿔 넣지는 않았다.
- G31/G32/G38처럼 잘못된 LLM 출력이 정규식 방어 밖으로 빠질 가능성은 별도 오류 차단 평가 대상이다. 올바른 canonical 응답의 기대값을 작성한 것이 모든 잘못된 응답의 안전성을 증명하지 않는다.
- 제목·확인 사유의 의미 동등성 중 허용 예시 밖 표현은 수동 검토가 필요하다. 날짜/시간/필수 조건의 오답까지 문장 유사성으로 허용하지 않는다.

## 6. 이번 검증 범위

JSON 문법·중복 객체 키·40개 연속 ID·ID 유일성·기존 설계 문장/기준 보존·14필드 DTO 형태·nullable 타입/enum·ISO 날짜와 시각·정상 기본값·중단 결과·유형별 개수·확정 6개 정책·날짜 경계 기대값을 정적으로 검사한다. 문서 로컬 링크도 검사한다.

이번에는 Java/JavaScript/프롬프트/추천/DB를 변경하지 않고 기존 테스트도 수정하지 않았다. Java 전체 테스트를 재실행하거나 이 파일을 Java에 재생한 것은 아니다. OpenAI·Google API도 호출하지 않았다. 이 JSON은 일반 회귀 테스트에서 자동으로 실제 API를 호출하지 않는다.
