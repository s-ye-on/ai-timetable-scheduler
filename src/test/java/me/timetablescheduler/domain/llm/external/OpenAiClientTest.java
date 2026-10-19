package me.timetablescheduler.domain.llm.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(OutputCaptureExtension.class)
class OpenAiClientTest {

	private static final String PRIVATE_TEXT = "test-only-private-user-input";
	private final ObjectMapper mapper = new ObjectMapper();
	private MockRestServiceServer server;
	private OpenAiClient client;

	@BeforeEach
	void 테스트_준비() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new OpenAiClient(new OpenAiProperties("test-only-key", "test-model", "https://openai.test/v1"), mapper, builder);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 429, 500, 503})
	void HTTP_실패는_응답을_노출하지_않고_외부_API_오류로_처리한다(int status, CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"" + PRIVATE_TEXT + "\"}}"));

		오류_검증("OPENAI_API_FAILED", () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT, "test-only-key");
		server.verify();
	}

	@Test
	void 시간_초과는_예외_메시지를_노출하지_않고_외부_API_오류로_처리한다(CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withException(new SocketTimeoutException(PRIVATE_TEXT)));

		오류_검증("OPENAI_API_FAILED", () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "{}", "null", "{\"output\":[]}"})
	void 유효하지_않은_성공_응답은_파싱_오류로_처리한다(String body, CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		오류_검증(ExceptionCode.INVALID_LLM_PARSE_RESULT.name(), () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@Test
	void 잘못된_구조화_응답은_원문을_노출하지_않고_파싱_오류로_처리한다(CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(응답_본문_감싸기("{\"title\":\"" + PRIVATE_TEXT + "\",\"category\":\"INVALID\"}"), MediaType.APPLICATION_JSON));
		오류_검증(ExceptionCode.INVALID_LLM_PARSE_RESULT.name(), () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@Test
	void 유효한_구조화_응답은_조건을_변경하지_않고_해석한다() {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(응답_본문_감싸기("""
				{"title":"과제","category":"ASSIGNMENT","durationMinutes":90,
				 "preferredDate":"2026-10-20","preferredTimeRange":"AFTERNOON","priority":"HIGH"}
				"""), MediaType.APPLICATION_JSON));
		var result = client.parseTask("화요일 오후 과제 90분");
		assertThat(result.title()).isEqualTo("과제");
		assertThat(result.durationMinutes()).isEqualTo(90);
		assertThat(result.preferredDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 20));
		assertThat(result.preferredTimeRange().name()).isEqualTo("AFTERNOON");
		assertThat(result.priority().name()).isEqualTo("HIGH");
		server.verify();
	}

	private String 응답_본문_감싸기(String text) {
		return mapper.writeValueAsString(Map.of("output", List.of(Map.of("content", List.of(Map.of("text", text))))));
	}

	@Test
	void 자정에_날짜가_바뀌어도_요청_시각을_한번_읽고_프롬프트와_정규화에_공유한다() {
		java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
		java.time.Clock advancing = new java.time.Clock() {
			public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
			public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
			public java.time.Instant instant() {
				return java.time.Instant.parse("2026-10-18T14:59:59Z").plusSeconds(reads.getAndIncrement() * 2L);
			}
		};
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.instructions")
				.value(org.hamcrest.Matchers.containsString("Asia/Seoul 기준 2026-10-18")))
			.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.instructions")
				.value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("23:59"))))
			.andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.text.format.strict").value(true))
			.andRespond(withSuccess(응답_본문_감싸기("""
				{"title":"공부","category":"STUDY","durationMinutes":60,
				 "preferredDate":null,"preferredDayOfWeek":"MONDAY","preferredDateRange":null,
				 "preferredTimeRange":"LUNCH","deadline":null,"priority":null,"description":null,
				 "dateRequired":false,"timeRequired":false,"preferredStartTime":null,"clarificationReason":null}
				"""), MediaType.APPLICATION_JSON));
		var service = new me.timetablescheduler.domain.llm.service.LlmParsingService(client, advancing);
		var result = service.parseTask(new me.timetablescheduler.domain.llm.dto.LlmParseRequest("다음 주 월요일 점심에 공부 1시간"));
		assertThat(reads.get()).isEqualTo(1);
		assertThat(result.preferredDate()).isEqualTo(java.time.LocalDate.of(2026, 10, 19));
		assertThat(result.preferredDateRange()).isNull();
		assertThat(result.preferredDayOfWeek()).isNull();
		server.verify();
	}

	private void 오류_검증(String expected, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(LlmException.class)
			.satisfies(error -> {
				var code = ((LlmException) error).getExceptionCode();
				assertThat(code.name()).isEqualTo(expected);
				assertThat(code.getStatus().value()).isEqualTo(expected.equals("OPENAI_API_FAILED") ? 502 : 400);
			});
	}
}
