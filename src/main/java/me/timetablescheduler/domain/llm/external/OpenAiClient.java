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
			- "오늘 밤 한 시"는 01:00으로 추출하세요. 요청 시각 이후의 날짜 선택은 Java가 담당합니다. 밤을 EVENING으로 강제하지 마세요.
			- "매주", "매일" 등의 반복 일정은 현재 미지원이므로 clarificationReason으로 안내하세요.
			- 날짜·시각만 있고 활동이 없으면 title과 category는 null이며 활동명 확인이 필요합니다. 공부나 약속 같은 활동을 지어내지 마세요.
			- "반드시", "에만 가능"처럼 명시한 필수 조건은 보존하세요. 필수 표현이 없으면 임의로 true로 만들지 마세요.
			- "목요일 7시"처럼 오전·오후가 없는 1~12시는 확인이 필요합니다. "19시 10분"은 19:10을 그대로 보존하세요.
			- 정확 시각의 시간대 자동 추론은 Java가 수행합니다. 명시된 시간대가 없으면 preferredTimeRange는 null로 두어도 됩니다.
			- 알 수 없는 값은 null로 설정하세요.
			- 응답은 반드시 제공된 JSON Schema에 맞춰야 합니다.

			필드 규칙:
			- title: 일정의 짧은 한국어 제목입니다. 알 수 없으면 null입니다.
			- category: STUDY, ASSIGNMENT, APPOINTMENT, PERSONAL 중 하나입니다. 불명확하면 null입니다.
			- durationMinutes: 30분 단위의 양수입니다. 사용자가 말하지 않았으면 null입니다.
			- preferredDate: 특정 날짜가 하나 있으면 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- preferredDayOfWeek: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY 중 하나입니다. 없으면 null입니다.
			- preferredDateRange: 기간 조건이 있으면 startDate/endDate를 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- preferredDate와 preferredDayOfWeek는 함께 설정하지 마세요. preferredDateRange는 날짜 또는 요일 선호와 함께 설정할 수 있습니다.
			- 날짜 조건이 없으면 세 필드는 모두 null입니다.
			- preferredTimeRange: MORNING, LUNCH, AFTERNOON, EVENING, ANYTIME 중 하나입니다. 시간 조건이 없으면 null입니다.
			- dateRequired: 특정 날짜 또는 요일이 반드시 지켜져야 하면 true, 선호이면 false입니다. 날짜 또는 요일이 없으면 false입니다. 판단할 수 없으면 null입니다.
			- timeRequired: preferredStartTime이 있으면 그 시각, 없으면 preferredTimeRange가 반드시 지켜져야 하면 true, 선호이면 false입니다. 시간 조건이 없으면 false입니다. 판단할 수 없으면 null입니다.
			- preferredStartTime: 사용자가 명시한 시작 시각이 있으면 HH:mm 형식으로 설정하고, 아니면 null입니다. "7시"처럼 오전/오후를 판단할 수 없으면 null입니다.
			- clarificationReason: 모호하거나 현재 지원하지 않는 조건 때문에 확인이 필요하면 그 이유를 한국어로 설정하고, 아니면 null입니다.
			- deadline: 마감일이 있으면 yyyy-MM-dd 형식으로 설정하고, 아니면 null입니다.
			- priority: LOW, NORMAL, HIGH 중 하나입니다. 불명확하면 null입니다.
			- description: 사용자의 원문 메시지를 그대로 넣으세요.
			""".formatted(requestDate);
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
