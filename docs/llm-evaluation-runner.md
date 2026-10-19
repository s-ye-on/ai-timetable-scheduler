# 실제 LLM 자연어 해석 평가 Runner

## 1. 구현 범위

Runner는 [Golden Dataset 40개](llm-evaluation-golden-dataset.md)를 실제 서비스의 모델·프롬프트·Structured Outputs로 평가한다. **이번 구현·검증에서는 실제 OpenAI·Google API를 호출하지 않았으며, 실제 LLM 정확도는 아직 측정하지 않았다.**

JUnit·Gradle을 사용하며 프로덕션 Java·추천 엔진·DB·화면과 Golden 정답은 변경하지 않는다.

| 구성 | 역할 |
|---|---|
| [LlmLiveEvaluationTest](../src/test/java/me/timetablescheduler/domain/llm/evaluation/LlmLiveEvaluationTest.java) | 호출 승인·환경변수 검사, 서비스 설정 로딩, 실제 HTTP 연결 |
| [LlmEvaluationRunner](../src/test/java/me/timetablescheduler/domain/llm/evaluation/LlmEvaluationRunner.java) | 반복 실행, 실제 요청·원본 응답 캡처, 고정 Clock Java 재생 |
| [GoldenScorer](../src/test/java/me/timetablescheduler/domain/llm/evaluation/GoldenScorer.java) | 필드·문장·중대 오류 판정 |
| [EvaluationSupport](../src/test/java/me/timetablescheduler/domain/llm/evaluation/EvaluationSupport.java) | 엄격 JSON 파싱, 실제 전송 스키마 검증, 버전 해시 |
| [EvaluationReport](../src/test/java/me/timetablescheduler/domain/llm/evaluation/EvaluationReport.java) | 집계·체크포인트·JSON/CSV·비밀값 제거 |
| [LlmEvaluationRunnerTest](../src/test/java/me/timetablescheduler/domain/llm/evaluation/LlmEvaluationRunnerTest.java) | 실제 네트워크를 대체한 Stub 검증 |

## 2. 실제 호출 경로와 고정 시각

기존 OpenAiClient.parseTask(input, referenceDate) → /v1/responses 경로를 사용한다. 프롬프트·스키마 복사본 없이 인터셉터로 **실제로 전송한** 모델·instructions·schema를 확인한다.

1. 사례의 reference.requestedAt을 Clock.fixed(..., Asia/Seoul)로 고정한다.
2. 동일 Clock에서 얻은 날짜를 서비스 프롬프트에 전달한다. 현재 시·분은 LLM에 추가하지 않는다.
3. 모델 원본 JSON의 키 생략·타입·enum·추가 키·중복 키를 검사한다.
4. DTO로 변환 가능하면 실제 LlmParsingService에 재생한다. OpenAI를 다시 호출하지 않는다.
5. 같은 Clock으로 Java 정규화·검증과 Task.create를 확인한다.

원본이 FAIL/MANUAL이어도 DTO로 읽을 수 있으면 Java 검증을 독립적으로 진행한다. Java가 보정·거절했다고 원본 점수를 바꾸지 않는다.

taskCreatable은 **도메인 객체 생성 가능 여부**다. Repository 저장·트랜잭션·HTTP 201·추천 후보·Calendar 등록 검증을 뜻하지 않는다. 오류 HTTP 상태는 ExceptionCode에서 기록하며 실제 HTTP 서버를 띄우지 않는다.

## 3. 실행 방법과 안전장치

일반 회귀 테스트에는 llm-live 태그가 제외된다.

~~~sh
./gradlew test
~~~

실제 평가는 다음 조건을 모두 만족해야 한다.

- OPENAI_API_KEY 환경변수: 기존 서비스 키를 안전하게 주입한다. 키를 명령 기록·문서·Git에 직접 적지 않는다.
- LLM_EVAL_ALLOW_API=true 환경변수
- -PrunLlmEval=true 옵션
- 반복 횟수는 기본 1회 또는 -PevalRepeats=3

키가 이미 환경변수에 주입되어 있다는 전제의 명령:

~~~sh
LLM_EVAL_ALLOW_API=true ./gradlew llmEval -PrunLlmEval=true
LLM_EVAL_ALLOW_API=true ./gradlew llmEval -PrunLlmEval=true -PevalRepeats=3
~~~

각각 40회·120회 요청을 시도하며 비용이 발생한다. 자동 API 재시도는 추가하지 않는다. Gradle과 JUnit의 실행 승인을 모두 통과해야 하며, 평가 결과를 Gradle 캐시로 대체하지 않는다.

설정은 Spring Boot ConfigData/Binder로 읽는다. 기본 위치는 src/main/resources/application.properties와 application.yaml이다. OPENAI_MODEL, OPENAI_BASE_URL, SPRING_CONFIG_LOCATION, SPRING_PROFILES_ACTIVE 등 기존 Spring 설정 방식을 사용할 수 있다. 테스트용 application.properties를 서비스 설정으로 오인하지 않도록 기본 위치를 명시하며 프로파일 설정도 반영한다. 키는 반드시 OPENAI_API_KEY에서 가져온다.

Spring ApplicationContext·서버·JWT·DB는 기동하지 않는다. 실제 Runner는 공식 https://api.openai.com의 기본 경로 또는 /v1만 허용하고 리다이렉트를 따르지 않는다. 사용자 지정 프록시는 지원하지 않는다. 연결 제한 10초, 읽기 제한 60초다.

## 4. 채점 기준

| 상태 | 의미 |
|---|---|
| PASS | 자동 정답 확인 |
| FAIL | 기대값·계약과 불일치 |
| MANUAL | 의미 동등성에 사람의 판단 필요 |
| ERROR | HTTP·전송·거절·미완성·JSON 또는 평가기 오류 |
| NOT_EVALUATED | 유효 출력/DTO가 없어 평가 불가 |
| EXCLUDED | Golden의 CONTRACT_ONLY/NOT_APPLICABLE이며 의미 정확도 제외 |

- schemaStatus(실제 스키마), dtoStatus(변환 가능), rawStatus(계약·의미를 합친 엄격 문장 판정), rawSemanticStatus(의미 필드만), javaStatus(정규화·검증)를 구분한다.
- 정확도: PASS / (해당 항목 전체 시도 - EXCLUDED). **MANUAL·ERROR·NOT_EVALUATED는 분모에 남기고 정답으로 세지 않는다.**
- 자동 판정률: (PASS + FAIL) / 분모. 성공한 응답만 골라 집계하지 않는다.
- 문장 전체가 PASS이려면 모든 평가 필드가 통과해야 한다. FAIL이 없고 의미 미판정이 있으면 MANUAL이다.
- byField는 의미 정확도만 집계한다. CONTRACT_ONLY/NOT_APPLICABLE은 틀려도 이 의미 분모에서 제외하며 byContractField에 별도로 실패를 기록한다. 계약 실패는 엄격 문장 rawStatus에 반영한다.
- 제목은 Golden acceptedExamples를 공백·유니코드 표기 정규화 후 자동 인정한다. 목록 밖 비어 있지 않은 표현은 MANUAL이다.
- 확인 사유는 canonical 문구가 같으면 자동 인정하고, 다른 비어 있지 않은 문구는 Golden requiredConcepts를 사람이 확인한다. 표현 차이를 곧바로 오답으로 단정하지 않는다.
- 날짜·시각·필수 여부·허용 기간·마감·enum·description은 Golden 규칙대로 비교한다. 19:10:00은 19:10과 같으며 19:10:30은 다르다.
- taskCreation은 생성 가능 여부, clarification은 기대 오류 코드/확인 요청 일치율이다. Java 문장 판정은 정규화 값·오류 코드·오류 HTTP 상태·지정된 안내 문구도 검사한다.
- 필수 true 누락, 정확 시각 변경/임의 확정, 필수 날짜 손실, 허용 기간·마감 손실, 활동명 임의 생성, 확인 사유 누락, G28 소요 시간 반올림, 차단 대상 Task 생성은 중대 오류다. 미판정 의미 오류를 확정 중대 오류로 세지 않는다.
- rawErrorBlocked는 오답 원본을 실제 Java 4xx 검증으로 차단한 경우다. 내부 평가 오류를 안전 차단으로 계산하지 않는다.

현재 실제 스키마의 object/string/integer/boolean/null, enum, anyOf, required, additionalProperties를 검사한다. 미지원 스키마 키워드는 통과시키지 않고 오류로 남긴다. 범용 JSON Schema 검증기는 아니다.

거절·미완성은 정상 JSON 결과와 별도로 기록한다. 관련 계약: [OpenAI Structured Outputs 문서](https://developers.openai.com/api/docs/guides/structured-outputs).

## 5. 결과 파일과 버전 비교

실행마다 build/llm-evaluation/날짜시각-고유값/에 저장한다. 기본 build 경로는 Git 대상에서 제외된다.

| 파일 | 내용 |
|---|---|
| manifest.json | LIVE/STUB, 실행 시각·완료 여부, 모델, 반복·시도 수, Git 커밋, Runner 버전, 데이터셋·프롬프트·스키마 해시 |
| cases.jsonl | 요청마다 즉시 추가하는 체크포인트 |
| cases.json | 입력·고정 기준·기대/실제 원본·정규화·오류·필드 판정·중대 오류·수동 검토 |
| summary.json / summary.csv | 전체·유형·필드 정확도, 계약/스키마/DTO, 생성·확인, 중대 오류·안전 차단 건수 |
| cases.csv / fields.csv | 문장·필드의 기대값·실제값·판정 |
| manual-review.csv | 수동 검토할 원본/Java 필드 |

아래는 **파일 구조 설명 예시이며 실제 평가 결과가 아니다.**

~~~json
{
  "id": "G04",
  "rawStatus": "MANUAL",
  "javaStatus": "MANUAL",
  "schemaStatus": "PASS",
  "manualReviewRequired": true
}
~~~

데이터셋 해시는 로딩한 JSON을 Jackson으로 재직렬화한 값으로, 파일 바이트 해시와 구분한다. 프롬프트 원문 해시와 기준 날짜를 자리표시자로 바꾼 템플릿 해시를 모두 저장한다. Git 커밋·응답 모델도 기록하여 JSON/CSV 버전 비교에 사용한다. 자동 비교 화면/명령은 없다.

HTTP 오류 본문·Authorization·요청 헤더·환경변수 목록·예외 원문은 결과에 저장하지 않는다. 키 값·Bearer·일반 키 형태는 JSON/CSV에서 가리고 CSV 수식 실행 문자를 이스케이프한다. 오류 분류·상태·예외 클래스명과 허용 목록의 API 오류 코드/유형·미완성 원인만 기록한다. 알 수 없는 API 코드는 OTHER로 남긴다. Golden 입력과 모델 출력은 저장되므로 개인정보가 있는 실사용 문장으로 임의 교체하지 않는다.

일부 요청 실패는 기록 후 계속 진행한다. 파일 저장 실패는 실행을 중단한다. 강제 종료 시 마지막 cases.jsonl까지만 보존될 수 있다. 자동 이어 실행·수동 검토 결과 재수입·정확도 합격선은 이번 범위에 포함하지 않는다. 명령의 정상 종료는 보고서 생성 완료이며 정확도 합격을 의미하지 않는다.

## 6. 검증과 실제 평가 전 확인

~~~sh
./gradlew test --tests '*LlmEvaluationRunnerTest'
./gradlew test
node --test src/test/javascript/demo-ui-state.test.cjs
~~~

MockRestServiceServer로 실제 OpenAiClient의 통신을 대체한다. 40개×1회/3회 canonical 응답, 고정 날짜 프롬프트·Java 결과, 오류 후 계속 진행, 필수·정확 시각 오류, 수동 검토·계약 분모, 키 제거·CSV, 설정 프로파일·호출 게이트를 검사한다.

실제 평가 전 모델·키·비용·반복 횟수를 확인하고 첫 실행은 1회로 한다. MANUAL과 실패 원인을 검토한다. Stub 통과는 실제 모델의 언어 해석 정확도를 증명하지 않는다.
