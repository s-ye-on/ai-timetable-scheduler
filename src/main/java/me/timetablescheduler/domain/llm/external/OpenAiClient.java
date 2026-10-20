package me.timetablescheduler.domain.llm.external;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import static java.util.Map.entry;

@Component
public class OpenAiClient {
	private static final Logger log = LoggerFactory.getLogger(OpenAiClient.class);

	private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
	private static final String RESPONSES_PATH = "/responses";
	private static final String SEOUL_ZONE_ID = "Asia/Seoul";

	private final OpenAiProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient restClient;

	/// todo :
	/// OpenAiClient를 interface로 분리
	/// OpenAiClient 구현체 생성, FakeLlmClient 생성
	/// 테스트에서는 FakeOpenAiClient 구현체 사용
	protected OpenAiClient() {
		this.properties = null;
		this.objectMapper = null;
		this.restClient = null;
	}

	@Autowired
	public OpenAiClient(
		OpenAiProperties properties,
		ObjectMapper objectMapper,
		RestClient.Builder restClientBuilder
	) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.restClient = restClientBuilder
			.baseUrl(normalizeBaseUrl(properties.baseUrl()))
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
			.defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
			.build();
	}

	public ParsedTaskResponse parseTask(String message) {
		return parseTask(message, LocalDate.now(ZoneId.of(SEOUL_ZONE_ID)));
	}

	public ParsedTaskResponse parseTask(String message, LocalDate requestDate) {
		try {
			String responseBody = restClient.post()
				.uri(RESPONSES_PATH)
				.body(createRequestBody(message, requestDate))
				.retrieve()
				.body(String.class);

			String outputText = extractOutputText(responseBody);
			return objectMapper.readValue(outputText, ParsedTaskResponse.class);
		} catch (LlmException exception) {
			throw exception;
		} catch (RestClientException exception) {
			log.warn("OpenAI API 호출 실패. exceptionType={}", exception.getClass().getSimpleName());
			throw new LlmException(ExceptionCode.OPENAI_API_FAILED);
		} catch (Exception exception) {
			log.warn(
				"OpenAI 응답 파싱 실패. exceptionType={}",
				exception.getClass().getSimpleName()
			);
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}
	}

	private Map<String, Object> createRequestBody(String message, LocalDate requestDate) {
		return Map.of(
			"model", properties.model(),
			"instructions", createInstructions(requestDate),
			"input", message,
			"store", false,
			"text", Map.of("format", createResponseFormat())
		);
	}

	private String createInstructions(LocalDate requestDate) {
		return """
			당신은 한국어 자연어 일정 요청을 구조화된 JSON으로 변환하는 파서입니다.
			오늘 날짜는 Asia/Seoul 기준 %s 입니다.
			"오늘", "내일", "이번 주", "다음 주" 같은 상대 날짜는 이 날짜를 기준으로 해석하세요.

			규칙:
			- 추천 시간 자체를 결정하지 마세요. 사용자가 말한 조건만 추출하세요.
			- 주는 월요일부터 일요일입니다. 상대 날짜의 최종 계산은 Java가 같은 기준 날짜로 수행합니다.
			- 단독 요일과 "이번 주 목요일", "다음 주 월요일"은 preferredDayOfWeek로 추출하고 preferredDate는 null로 두세요. 반복 의미가 아닙니다.
			- "이번 주 안에"처럼 허용 기간을 명시하면 해당 주 월요일~일요일을 preferredDateRange로 추출하고 요일 선호는 별도로 보존하세요.
			- "다음 주 월요일"이라는 특정 날짜 표현만으로 preferredDateRange를 추가하지 마세요.
			- "모레 오후에"는 선호 날짜와 시간대입니다. "그날 안에만" 같은 별도 기간 제한이 없다면 하루짜리 preferredDateRange로 바꾸지 마세요.
			- preferredDateRange는 그 자체로 반드시 지킬 허용 기간입니다. 기간의 필수 여부를 dateRequired로 표현하지 마세요.
			- "2일부터 5일 사이에만"처럼 기간만 있으면 preferredDate/preferredDayOfWeek는 null, dateRequired=false입니다. 이 경우에도 허용 기간은 필수입니다.
			- "4일이면 좋겠고 2일부터 5일 안에"는 선호 날짜와 필수 기간을 함께 추출합니다. 실제 "4일 하루 안에"라는 제한은 하루짜리 기간으로 보존하세요.
			- "11월 4일 오후에만"의 '만'은 오후에만 적용됩니다. dateRequired=false, timeRequired=true이며, "11월 4일에만 오후에"는 반대로 true/false입니다.
			- 초 단위 시작 시각, "월/화 중 하루" 같은 복수 요일 선택, "한 시간 뒤" 같은 상대 시간 간격은 현재 미지원입니다. 초를 절삭하거나 요일을 연속 기간으로 바꾸거나 상대 조건을 누락하지 말고 clarificationReason으로 확인을 요청하세요.
			- 활동명 속 "30초 영상 편집", "월/화 수업자료 복습", "뒤풀이 준비"는 위 미지원 일정 조건과 구분하세요.
			- "오늘 밤 한 시"는 01:00으로 추출하세요. 요청 시각 이후의 날짜 선택은 Java가 담당합니다. 밤을 EVENING으로 강제하지 마세요.
			- "매주", "매일" 등의 반복 일정은 현재 미지원이므로 clarificationReason으로 안내하세요.
			- 활동명은 날짜·시간의 구체성과 별개로 추출하세요. "공부", "과제"처럼 포괄적인 활동도 유효한 제목입니다.
			- "공부하고 싶어", "과제할 시간"에도 활동이 있습니다. 과목명·과제명이 없다는 이유로 title을 null로 만들지 마세요.
			- 활동 자체가 없는 날짜·시각 요청에서만 title과 category를 null로 두고 활동명을 확인하세요. 없는 활동을 지어내지는 마세요.
			- 날짜·시간을 지정하지 않은 활동 요청도 정상입니다. 날짜 필드와 시간 필드는 null, dateRequired와 timeRequired는 false이며 그 누락만으로 clarificationReason을 만들지 마세요.
			- "반드시", "에만 가능"처럼 명시한 필수 조건은 보존하세요. 필수 표현이 없으면 임의로 true로 만들지 마세요.
			- 정확한 시작 시각의 존재와 시간의 필수 여부는 독립적입니다. 시각을 말했다는 이유만으로 timeRequired=true로 만들지 마세요.
			- "저녁 7시에 공부하고 싶어"는 preferredStartTime="19:00", timeRequired=false입니다. "저녁 7시에만 공부할 수 있어"는 같은 시각에 timeRequired=true입니다.
			- 시간대도 "오후에 공부"는 선호(false), "오후에만 공부 가능"은 필수(true)입니다. 시간대만 말했으면 정확한 시작 시각을 추가하지 마세요.
			- 날짜와 시간의 필수 여부는 각각 판단하세요. "수요일에만"은 날짜만, "8시에만"은 시간만 필수이며 다른 조건을 함께 true로 바꾸지 마세요.
			- "목요일 7시"처럼 오전·오후가 없는 1~12시는 확인이 필요합니다. "19시 10분"은 19:10을 그대로 보존하세요.
			- 오전·오후 확인과 필수 여부 판단도 별개입니다. "7시에 공부하고 싶어"는 timeRequired=false, "7시에만 공부할 수 있어"는 true이지만 둘 다 오전·오후를 확인하고 preferredStartTime은 null로 두세요.
			- 정확 시각의 시간대 자동 추론은 Java가 수행합니다. 명시된 시간대가 없으면 preferredTimeRange는 null로 두어도 됩니다.
			- 알 수 없는 값은 null로 설정하세요.
			- 응답은 반드시 제공된 JSON Schema에 맞춰야 합니다.

			필드 규칙:
			- title: 원문에 있는 활동을 짧은 한국어 제목으로 보존하세요. 공부·과제만 있어도 각각 "공부"·"과제"입니다. 활동 자체를 알 수 없을 때만 null입니다.
			- category: STUDY, ASSIGNMENT, APPOINTMENT, PERSONAL 중 하나입니다. 불명확하면 null입니다.
			- durationMinutes: 30분 단위의 양수입니다. 사용자가 말하지 않았으면 null입니다.
			- preferredDate: 특정 날짜가 하나 있으면 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- preferredDayOfWeek: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY 중 하나입니다. 없으면 null입니다.
			- preferredDateRange: 기간 조건이 있으면 startDate/endDate를 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- preferredDate와 preferredDayOfWeek는 함께 설정하지 마세요. preferredDateRange는 날짜 또는 요일 선호와 함께 설정할 수 있습니다.
			- 날짜 조건이 없으면 세 필드는 모두 null입니다.
			- preferredTimeRange: MORNING, LUNCH, AFTERNOON, EVENING, ANYTIME 중 하나입니다. 시간 조건이 없으면 null입니다.
			- dateRequired: 특정 날짜 또는 요일이 반드시 지켜져야 하면 true, 선호이면 false입니다. 날짜 또는 요일이 없으면 false입니다. 판단할 수 없으면 null입니다.
			- timeRequired: 시간 조건을 반드시 지켜야 한다고 명시한 경우에만 true입니다. 일반 요청·선호·시간 미지정은 false이며, 정확 시각이 있어도 기본은 false입니다. 필수인지 실제로 판단할 수 없을 때만 null입니다.
			- timeRequired=true일 때 강제할 대상은 preferredStartTime이 있으면 그 시각, 없으면 preferredTimeRange입니다. 이 대상 선택 규칙을 true 설정의 근거로 사용하지 마세요.
			- preferredStartTime: 사용자가 명시한 시작 시각이 있으면 HH:mm 형식으로 설정하고, 아니면 null입니다. "7시"처럼 오전/오후를 판단할 수 없으면 null입니다.
			- clarificationReason: 모호하거나 현재 지원하지 않는 조건 때문에 확인이 필요하면 그 이유를 한국어로 설정하고, 아니면 null입니다.
			- deadline: 마감일이 있으면 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- priority: LOW, NORMAL, HIGH 중 하나입니다. 불명확하면 null입니다.
			- description: 사용자의 원문 메시지를 그대로 넣으세요.

			대조 예시: 같은 활동에서도 날짜 필수·시간 필수는 독립적입니다.
			아래 input/output은 설명용입니다. 실제 응답은 해당 output과 같은 14필드 객체 하나만 반환하고, 예시의 날짜나 활동을 현재 요청에 복사하지 마세요.
			<contrast_examples>
			[
			  {
			    "input": "수요일 저녁 8시에 운동 1시간 하고 싶어",
			    "output": {
			      "title": "운동", "category": "PERSONAL", "durationMinutes": 60,
			      "preferredDate": null, "preferredDayOfWeek": "WEDNESDAY", "preferredDateRange": null,
			      "preferredTimeRange": "EVENING", "deadline": null, "priority": null,
			      "description": "수요일 저녁 8시에 운동 1시간 하고 싶어",
			      "dateRequired": false, "timeRequired": false, "preferredStartTime": "20:00", "clarificationReason": null
			    }
			  },
			  {
			    "input": "수요일 저녁 8시에만 운동 1시간 할 수 있어",
			    "output": {
			      "title": "운동", "category": "PERSONAL", "durationMinutes": 60,
			      "preferredDate": null, "preferredDayOfWeek": "WEDNESDAY", "preferredDateRange": null,
			      "preferredTimeRange": "EVENING", "deadline": null, "priority": null,
			      "description": "수요일 저녁 8시에만 운동 1시간 할 수 있어",
			      "dateRequired": false, "timeRequired": true, "preferredStartTime": "20:00", "clarificationReason": null
			    }
			  },
			  {
			    "input": "수요일에만 저녁 8시에 운동 1시간 하고 싶어",
			    "output": {
			      "title": "운동", "category": "PERSONAL", "durationMinutes": 60,
			      "preferredDate": null, "preferredDayOfWeek": "WEDNESDAY", "preferredDateRange": null,
			      "preferredTimeRange": "EVENING", "deadline": null, "priority": null,
			      "description": "수요일에만 저녁 8시에 운동 1시간 하고 싶어",
			      "dateRequired": true, "timeRequired": false, "preferredStartTime": "20:00", "clarificationReason": null
			    }
			  },
			  {
			    "input": "공부 한 시간 할래",
			    "output": {
			      "title": "공부", "category": "STUDY", "durationMinutes": 60,
			      "preferredDate": null, "preferredDayOfWeek": null, "preferredDateRange": null,
			      "preferredTimeRange": null, "deadline": null, "priority": null,
			      "description": "공부 한 시간 할래",
			      "dateRequired": false, "timeRequired": false, "preferredStartTime": null, "clarificationReason": null
			    }
			  },
			  {
			    "input": "과제 한 시간 할래",
			    "output": {
			      "title": "과제", "category": "ASSIGNMENT", "durationMinutes": 60,
			      "preferredDate": null, "preferredDayOfWeek": null, "preferredDateRange": null,
			      "preferredTimeRange": null, "deadline": null, "priority": null,
			      "description": "과제 한 시간 할래",
			      "dateRequired": false, "timeRequired": false, "preferredStartTime": null, "clarificationReason": null
			    }
			  },
			  {
			    "input": "반드시 수요일 저녁 8시에만 가능해",
			    "output": {
			      "title": null, "category": null, "durationMinutes": null,
			      "preferredDate": null, "preferredDayOfWeek": "WEDNESDAY", "preferredDateRange": null,
			      "preferredTimeRange": "EVENING", "deadline": null, "priority": null,
			      "description": "반드시 수요일 저녁 8시에만 가능해",
			      "dateRequired": true, "timeRequired": true, "preferredStartTime": "20:00",
			      "clarificationReason": "어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요."
			    }
			  }
			]
			</contrast_examples>
			기간·필수 범위 대조 예시도 같은 14필드 계약을 따릅니다. 예시 자체의 날짜를 현재 요청에 복사하지 마세요.
			<constraint_examples>
			[
			  {
			    "input": "모레 오후에 공부 한 시간 반",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 90,
			      "preferredDate": "%s",
			      "preferredDayOfWeek": null,
			      "preferredDateRange": null,
			      "preferredTimeRange": "AFTERNOON",
			      "deadline": null,
			      "priority": null,
			      "description": "모레 오후에 공부 한 시간 반",
			      "dateRequired": false,
			      "timeRequired": false,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  },
			  {
			    "input": "2026년 11월 2일부터 5일 사이에만 공부 1시간",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 60,
			      "preferredDate": null,
			      "preferredDayOfWeek": null,
			      "preferredDateRange": {
			        "startDate": "2026-11-02",
			        "endDate": "2026-11-05"
			      },
			      "preferredTimeRange": null,
			      "deadline": null,
			      "priority": null,
			      "description": "2026년 11월 2일부터 5일 사이에만 공부 1시간",
			      "dateRequired": false,
			      "timeRequired": false,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  },
			  {
			    "input": "2026년 11월 4일 오후에만 공부 2시간",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 120,
			      "preferredDate": "2026-11-04",
			      "preferredDayOfWeek": null,
			      "preferredDateRange": null,
			      "preferredTimeRange": "AFTERNOON",
			      "deadline": null,
			      "priority": null,
			      "description": "2026년 11월 4일 오후에만 공부 2시간",
			      "dateRequired": false,
			      "timeRequired": true,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  },
			  {
			    "input": "2026년 11월 4일에만 오후에 공부 2시간",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 120,
			      "preferredDate": "2026-11-04",
			      "preferredDayOfWeek": null,
			      "preferredDateRange": null,
			      "preferredTimeRange": "AFTERNOON",
			      "deadline": null,
			      "priority": null,
			      "description": "2026년 11월 4일에만 오후에 공부 2시간",
			      "dateRequired": true,
			      "timeRequired": false,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  },
			  {
			    "input": "2026년 11월 4일 하루 안에 공부 1시간",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 60,
			      "preferredDate": null,
			      "preferredDayOfWeek": null,
			      "preferredDateRange": {
			        "startDate": "2026-11-04",
			        "endDate": "2026-11-04"
			      },
			      "preferredTimeRange": null,
			      "deadline": null,
			      "priority": null,
			      "description": "2026년 11월 4일 하루 안에 공부 1시간",
			      "dateRequired": false,
			      "timeRequired": false,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  },
			  {
			    "input": "2026년 11월 4일이면 좋겠고 2일부터 5일 안에 공부 1시간",
			    "output": {
			      "title": "공부",
			      "category": "STUDY",
			      "durationMinutes": 60,
			      "preferredDate": "2026-11-04",
			      "preferredDayOfWeek": null,
			      "preferredDateRange": {
			        "startDate": "2026-11-02",
			        "endDate": "2026-11-05"
			      },
			      "preferredTimeRange": null,
			      "deadline": null,
			      "priority": null,
			      "description": "2026년 11월 4일이면 좋겠고 2일부터 5일 안에 공부 1시간",
			      "dateRequired": false,
			      "timeRequired": false,
			      "preferredStartTime": null,
			      "clarificationReason": null
			    }
			  }
			]
			</constraint_examples>
			""".formatted(requestDate, requestDate.plusDays(2));
	}

	private Map<String, Object> createResponseFormat() {
		return Map.of(
			"type", "json_schema",
			"name", "parsed_task_response",
			"strict", true,
			"schema", createParsedTaskResponseSchema()
		);
	}

	private Map<String, Object> createParsedTaskResponseSchema() {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("title", nullableString());
		properties.put("category", nullableEnum("STUDY", "ASSIGNMENT", "APPOINTMENT", "PERSONAL"));
		properties.put("durationMinutes", nullableInteger());
		properties.put("preferredDate", nullableString());
		properties.put("preferredDayOfWeek", nullableEnum(
			"MONDAY",
			"TUESDAY",
			"WEDNESDAY",
			"THURSDAY",
			"FRIDAY",
			"SATURDAY",
			"SUNDAY"
		));
		properties.put("preferredDateRange", nullableDateRange());
		properties.put("preferredTimeRange", nullableEnum("MORNING", "LUNCH", "AFTERNOON", "EVENING", "ANYTIME"));
		properties.put("dateRequired", nullableBoolean());
		properties.put("timeRequired", nullableBoolean());
		properties.put("preferredStartTime", nullableString());
		properties.put("clarificationReason", nullableString());
		properties.put("deadline", nullableString());
		properties.put("priority", nullableEnum("LOW", "NORMAL", "HIGH"));
		properties.put("description", nullableString());

		return Map.ofEntries(
			entry("type", "object"),
			entry("additionalProperties", false),
			entry("required", List.of(
				"title",
				"category",
				"durationMinutes",
				"preferredDate",
				"preferredDayOfWeek",
				"preferredDateRange",
				"preferredTimeRange",
				"dateRequired",
				"timeRequired",
				"preferredStartTime",
				"clarificationReason",
				"deadline",
				"priority",
				"description"
			)),
			entry("properties", properties)
		);
	}

	private Map<String, Object> nullableBoolean() {
		return Map.of("type", List.of("boolean", "null"));
	}

	private Map<String, Object> nullableDateRange() {
		return Map.of(
			"anyOf",
			List.of(
				Map.of(
					"type", "object",
					"additionalProperties", false,
					"required", List.of("startDate", "endDate"),
					"properties", Map.of(
						"startDate", Map.of("type", "string"),
						"endDate", Map.of("type", "string")
					)
				),
				Map.of("type", "null")
			)
		);
	}

	private Map<String, Object> nullableString() {
		return Map.of("type", List.of("string", "null"));
	}

	private Map<String, Object> nullableInteger() {
		return Map.of("type", List.of("integer", "null"));
	}

	private Map<String, Object> nullableEnum(String... values) {
		List<String> enumValues = List.of(values);
		return Map.of(
			"anyOf",
			List.of(
				Map.of("type", "string", "enum", enumValues),
				Map.of("type", "null")
			)
		);
	}

	private String extractOutputText(String responseBody) throws Exception {
		OpenAiResponsesApiResponse response = objectMapper.readValue(responseBody, OpenAiResponsesApiResponse.class);
		if (response == null || response.output() == null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		return response.output()
			.stream()
			.filter(output -> output.content() != null)
			.flatMap(output -> output.content().stream())
			.map(OpenAiResponseContent::text)
			.filter(text -> text != null && !text.isBlank())
			.findFirst()
			.orElseThrow(() -> new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT));
	}

	private String normalizeBaseUrl(String baseUrl) {
		String normalizedBaseUrl = baseUrl == null || baseUrl.isBlank()
			? DEFAULT_BASE_URL
			: baseUrl;

		if (normalizedBaseUrl.endsWith("/")) {
			return normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
		}

		return normalizedBaseUrl;
	}

	private record OpenAiResponsesApiResponse(
		List<OpenAiResponseOutput> output
	) {
	}

	private record OpenAiResponseOutput(
		List<OpenAiResponseContent> content
	) {
	}

	private record OpenAiResponseContent(
		String text
	) {
	}
}
