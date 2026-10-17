package me.timetablescheduler.domain.calendar.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.calendar.Calendar;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleCalendarFreeBusyTest {

	private final GoogleCalendarProperties properties = new GoogleCalendarProperties("test-client", "test-only-secret",
		"http://localhost/callback", "calendar-scope", "test-app", "Asia/Seoul");
	private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 0, 0);
	private final LocalDateTime end = start.plusDays(1);

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"calendars\":{\"primary\":{\"busy\":[]}}}",
		"{\"calendars\":{\"primary\":{}}}",
		"{\"calendars\":{\"primary\":{\"busy\":null,\"errors\":[]}}}"
	})
	void successfulEmptyOrAbsentBusyIsAllowed(String body) {
		assertThat(query(200, body)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"calendars\":{}}", "{\"calendars\":{\"other\":{\"busy\":[]}}}",
		"{\"calendars\":{\"primary\":null}}"})
	void missingRequestedCalendarIsNotAnEmptySchedule(String body) {
		assertFailed(() -> query(200, body));
	}

	@ParameterizedTest
	@ValueSource(strings = {"notFound", "internalError", "futureUnknownError"})
	void calendarErrorsAreRejectedEvenWithBusyData(String reason) {
		assertFailed(() -> query(200, """
			{"calendars":{"primary":{"errors":[{"domain":"global","reason":"%s"}],
			"busy":[{"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"}]}}}
			""".formatted(reason)));
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 429, 500, 503})
	void httpFailureIsRejected(int status) {
		assertFailed(() -> query(status, "{\"error\":{\"message\":\"test-only-error\"}}"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"{\"start\":\"2026-10-20T01:00:00Z\"}",
		"{\"end\":\"2026-10-20T02:00:00Z\"}",
		"{\"start\":null,\"end\":\"2026-10-20T02:00:00Z\"}",
		"{\"start\":\"2026-10-20T01:00:00Z\",\"end\":null}",
		"{\"start\":\"2026-10-20T02:00:00Z\",\"end\":\"2026-10-20T01:00:00Z\"}",
		"{\"start\":\"2026-10-20T01:00:00Z\",\"end\":\"2026-10-20T01:00:00Z\"}",
		"{\"start\":\"invalid\",\"end\":\"2026-10-20T02:00:00Z\"}",
		"{\"start\":\"2026-10-20\",\"end\":\"2026-10-21\"}",
		"null"
	})
	void malformedBusyIntervalsCannotSilentlyDisappear(String interval) {
		assertFailed(() -> query(200, "{\"calendars\":{\"primary\":{\"busy\":[" + interval + "]}}}"));
	}

	@Test
	void validBusyIntervalsAreConvertedToSeoulTime() {
		assertThat(query(200, """
			{"calendars":{"primary":{"busy":[{"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"}]}}}
			""")).containsExactly(new BusyBlock(start.plusHours(10), start.plusHours(11)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "null", "[]"})
	void malformedWholeResponseIsAnApiFailure(String body) {
		assertFailed(() -> query(200, body));
	}

	@Test
	void networkFailureIsNotAnEmptySchedule() {
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
				throw new java.net.SocketTimeoutException("test-only-timeout");
			}
		};
		assertFailed(() -> query(transport));
	}

	@Test
	void oneIncompleteIntervalInvalidatesTheWholeResult() {
		assertFailed(() -> query(200, """
			{"calendars":{"primary":{"busy":[
			 {"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"},
			 {"start":"2026-10-20T03:00:00Z"}]}}}
			"""));
	}

	private List<BusyBlock> query(int status, String body) {
		var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(new MockLowLevelHttpResponse()
			.setStatusCode(status).setContentType("application/json").setContent(body)).build();
		return query(transport);
	}

	private List<BusyBlock> query(HttpTransport transport) {
		var sdk = new Calendar.Builder(transport, GsonFactory.getDefaultInstance(), null).build();
		// SDK 응답 파싱은 실제로 수행하고, HTTP 전송만 격리한다.
		try (var construction = mockConstruction(Calendar.Builder.class, (builder, context) -> {
			when(builder.setApplicationName(anyString())).thenReturn(builder);
			when(builder.build()).thenReturn(sdk);
		})) {
			return new GoogleCalendarClient(properties).getBusyBlocks("test-only-access-token", "primary", start, end);
		}
	}

	private void assertFailed(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
	}
}
