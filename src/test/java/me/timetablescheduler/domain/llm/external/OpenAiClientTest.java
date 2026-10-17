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
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new OpenAiClient(new OpenAiProperties("test-only-key", "test-model", "https://openai.test/v1"), mapper, builder);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 429, 500, 503})
	void httpFailuresAreUpstreamErrorsWithoutLeakingResponse(int status, CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body("{\"error\":{\"message\":\"" + PRIVATE_TEXT + "\"}}"));

		assertError("OPENAI_API_FAILED", () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT, "test-only-key");
		server.verify();
	}

	@Test
	void timeoutIsAnUpstreamErrorWithoutLeakingExceptionMessage(CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withException(new SocketTimeoutException(PRIVATE_TEXT)));

		assertError("OPENAI_API_FAILED", () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "{}", "null", "{\"output\":[]}"})
	void invalidSuccessfulResponsesRemainParsingErrors(String body, CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertError(ExceptionCode.INVALID_LLM_PARSE_RESULT.name(), () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@Test
	void malformedStructuredOutputRemainsAParsingErrorAndDoesNotLeakText(CapturedOutput output) {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(envelope("{\"title\":\"" + PRIVATE_TEXT + "\",\"category\":\"INVALID\"}"), MediaType.APPLICATION_JSON));
		assertError(ExceptionCode.INVALID_LLM_PARSE_RESULT.name(), () -> client.parseTask(PRIVATE_TEXT));
		assertThat(output.getAll()).doesNotContain(PRIVATE_TEXT);
		server.verify();
	}

	@Test
	void validStructuredOutputIsParsedWithoutChangingConditions() {
		server.expect(requestTo("https://openai.test/v1/responses"))
			.andRespond(withSuccess(envelope("""
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

	private String envelope(String text) {
		return mapper.writeValueAsString(Map.of("output", List.of(Map.of("content", List.of(Map.of("text", text))))));
	}

	private void assertError(String expected, Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(LlmException.class)
			.satisfies(error -> {
				var code = ((LlmException) error).getExceptionCode();
				assertThat(code.name()).isEqualTo(expected);
				assertThat(code.getStatus().value()).isEqualTo(expected.equals("OPENAI_API_FAILED") ? 502 : 400);
			});
	}
}
