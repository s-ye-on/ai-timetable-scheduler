# LLM 설계  
LLM 패키지는 추천 엔진이 아니라 **자연어 파싱 보조 모듈**  

Task를 만들기 위한 구조화 데이터를 반환하는 역할

## dto 
### LlmParseRequest
사용자 입력 원문을 받는 DTO  
일정 요청 파싱 하나만 있으니 일단 record 하나만으로 진행
추후 확장 시 분리 
- 일정 생성 요청 파싱
- 일정 수정 요청 파싱
- 일정 삭제 요청 파싱
- 일반 대화 응답 

### LlmParseResponse
LLM이 반환해야 하는 구조화 결과 
```json
{
  "title": "밥약속",
  "taskType": "APPOINTMENT",
  "preferredDayOfWeek": "TUESDAY",
  "preferredTimeLabel": "LUNCH",
  "preferredDate": null,
  "durationMinutes": 60,
  "description": "화요일 점심에 밥약속"
}
```
JSON 형식으로 나오게 만들기 

--- 
### LLM 코드 흐름 
```text
사용자 자연어 입력
→ LlmParsingService
→ OpenAiClient 호출
→ ParsedTaskResponse 반환
→ 검증/보정
→ TaskCreateRequest로 변환
→ Task 저장
→ 추천 로직 실행
```
LLM 결과 바로 저장x  
한 번 검증 후 Task로 바꿔서 저장

## 1차 구현에서는 PreferredTimeRange가 null인 경우 ANYTIME으로 보정 