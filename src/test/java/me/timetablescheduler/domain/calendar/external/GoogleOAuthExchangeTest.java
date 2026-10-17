package me.timetablescheduler.domain.calendar.external;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class GoogleOAuthExchangeTest {

	private static final String TEST_SECRET = "test-only-sensitive-client-secret";
	private static final String TEST_CODE = "test-only-sensitive-authorization-code";
	private final GoogleCalendarProperties properties = new GoogleCalendarProperties("test-client", TEST_SECRET,
		"http://localhost:8080/api/calendar/oauth/callback", "calendar-scope", "test-app", "Asia/Seoul");

	@Test
	void tokenRequestMatchesAuthorizationClientAndRedirectAndParsesResponse(CapturedOutput output) throws Exception {
		var transport = transport(200, """
			{"access_token":"test-only-access-token","refresh_token":"test-only-refresh-token","expires_in":3600,"token_type":"Bearer"}
			""");
		var client = new GoogleOAuthClient(properties, transport);
		var query = UriComponentsBuilder.fromUriString(client.createAuthorizationUrl("test-only-state")).build().getQueryParams();
		var result = client.exchangeCode(TEST_CODE);
		var request = transport.getLowLevelHttpRequest();
		Map<String, String> form = form(request.getContentAsString());

		assertThat(form.get("client_id")).isEqualTo(query.getFirst("client_id")).isEqualTo(properties.clientId());
		assertThat(form.get("redirect_uri")).isEqualTo(URLDecoder.decode(query.getFirst("redirect_uri"), StandardCharsets.UTF_8))
			.isEqualTo(properties.redirectUri());
		assertThat(form.get("client_secret")).isEqualTo(TEST_SECRET);
		assertThat(form.get("code")).isEqualTo(TEST_CODE);
		assertThat(form.get("grant_type")).isEqualTo("authorization_code");
		assertThat(form).doesNotContainKey("state");
		assertThat(request.getUrl()).isEqualTo("https://oauth2.googleapis.com/token");
		assertThat(result.getAccessToken()).isEqualTo("test-only-access-token");
		assertThat(result.getRefreshToken()).isEqualTo("test-only-refresh-token");
		assertThat(result.getExpiresInSeconds()).isEqualTo(3600L);
		assertThat(output.getAll()).doesNotContain(TEST_SECRET, TEST_CODE, "test-only-state", "test-only-access-token", "test-only-refresh-token");
	}

	@ParameterizedTest
	@CsvSource({"401,invalid_client", "400,invalid_grant", "400,invalid_request"})
	void logsOnlyHttpStatusAndAllowedOAuthError(int status, String error, CapturedOutput output) throws Exception {
		String body = GsonFactory.getDefaultInstance().toString(Map.of("error", error,
			"error_description", TEST_SECRET + " " + TEST_CODE + " test-only-sensitive-state test-only-sensitive-token"));
		var client = new GoogleOAuthClient(properties, transport(status, body));

		assertExchangeFailed(() -> client.exchangeCode(TEST_CODE));
		assertThat(output.getAll()).contains("httpStatus=" + status, "error=" + error)
			.doesNotContain(TEST_SECRET, TEST_CODE, "test-only-sensitive-state", "test-only-sensitive-token", "error_description");
	}

	@Test
	void arbitraryProviderErrorCannotInjectSensitiveTextIntoLogs(CapturedOutput output) throws Exception {
		String body = GsonFactory.getDefaultInstance().toString(Map.of("error", TEST_SECRET + "\n" + TEST_CODE,
			"error_description", "test-only-sensitive-state"));
		var client = new GoogleOAuthClient(properties, transport(400, body));
		assertExchangeFailed(() -> client.exchangeCode(TEST_CODE));
		assertThat(output.getAll()).contains("httpStatus=400, error=unknown")
			.doesNotContain(TEST_SECRET, TEST_CODE, "test-only-sensitive-state");
	}

	@Test
	void nonJsonProviderResponseDoesNotLeakRawContent(CapturedOutput output) {
		var client = new GoogleOAuthClient(properties, transport(502, "<html>" + TEST_SECRET + "</html>"));
		assertExchangeFailed(() -> client.exchangeCode(TEST_CODE));
		assertThat(output.getAll()).contains("httpStatus=502, error=unknown").doesNotContain(TEST_SECRET, TEST_CODE, "<html>");
	}

	@Test
	void networkFailureIsDistinguishedWithoutLoggingExceptionMessage(CapturedOutput output) {
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
				throw new SocketTimeoutException(TEST_SECRET + " " + TEST_CODE);
			}
		};
		var client = new GoogleOAuthClient(properties, transport);
		assertExchangeFailed(() -> client.exchangeCode(TEST_CODE));
		assertThat(output.getAll()).contains("httpStatus=none, error=transport_error").doesNotContain(TEST_SECRET, TEST_CODE);
	}

	@Test
	void unresolvedSecretPreventsAnyHttpRequest(CapturedOutput output) {
		AtomicInteger calls = new AtomicInteger();
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) {
				calls.incrementAndGet();
				throw new AssertionError("잘못된 설정으로 HTTP 요청을 생성하면 안 됩니다.");
			}
		};
		var invalid = new GoogleCalendarProperties("test-client", "${MISSING_TEST_OAUTH_SECRET}", properties.redirectUri(),
			properties.scope(), properties.applicationName(), properties.timeZone());
		assertThatThrownBy(() -> new GoogleOAuthClient(invalid, transport).exchangeCode(TEST_CODE))
			.isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode()).isEqualTo(ExceptionCode.GOOGLE_OAUTH_CONFIGURATION_INVALID));
		assertThat(calls.get()).isZero();
		assertThat(output.getAll()).contains("field=google.calendar.client-secret").doesNotContain("${MISSING_TEST_OAUTH_SECRET}", TEST_CODE);
	}

	private MockHttpTransport transport(int status, String body) {
		return new MockHttpTransport.Builder().setLowLevelHttpResponse(new MockLowLevelHttpResponse()
			.setStatusCode(status).setContentType("application/json").setContent(body)).build();
	}

	private Map<String, String> form(String body) {
		return Arrays.stream(body.split("&")).map(part -> part.split("=", 2)).collect(Collectors.toMap(
			part -> URLDecoder.decode(part[0], StandardCharsets.UTF_8),
			part -> URLDecoder.decode(part[1], StandardCharsets.UTF_8)));
	}

	private void assertExchangeFailed(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode()).isEqualTo(ExceptionCode.GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED));
	}
}
