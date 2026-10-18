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
	void 성공_응답의_빈_busy와_busy_생략을_허용한다(String body) {
		assertThat(조회(200, body)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"calendars\":{}}", "{\"calendars\":{\"other\":{\"busy\":[]}}}",
		"{\"calendars\":{\"primary\":null}}"})
	void 요청한_Calendar_응답_누락은_빈_일정으로_처리하지_않는다(String body) {
		조회_실패_검증(() -> 조회(200, body));
	}

	@ParameterizedTest
	@ValueSource(strings = {"notFound", "internalError", "futureUnknownError"})
	void busy_데이터가_있어도_Calendar_오류가_있으면_거절한다(String reason) {
		조회_실패_검증(() -> 조회(200, """
			{"calendars":{"primary":{"errors":[{"domain":"global","reason":"%s"}],
			"busy":[{"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"}]}}}
			""".formatted(reason)));
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 429, 500, 503})
	void HTTP_실패를_거절한다(int status) {
		조회_실패_검증(() -> 조회(status, "{\"error\":{\"message\":\"test-only-error\"}}"));
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
	void 잘못된_busy_구간을_조용히_누락하지_않는다(String interval) {
		조회_실패_검증(() -> 조회(200, "{\"calendars\":{\"primary\":{\"busy\":[" + interval + "]}}}"));
	}

	@Test
	void 유효한_busy_구간을_서울_시각으로_변환한다() {
		assertThat(조회(200, """
			{"calendars":{"primary":{"busy":[{"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"}]}}}
			""")).containsExactly(new BusyBlock(start.plusHours(10), start.plusHours(11)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{", "null", "[]"})
	void 전체_응답이_잘못되면_API_실패로_처리한다(String body) {
		조회_실패_검증(() -> 조회(200, body));
	}

	@Test
	void 네트워크_장애를_빈_일정으로_처리하지_않는다() {
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
				throw new java.net.SocketTimeoutException("test-only-timeout");
			}
		};
		조회_실패_검증(() -> 조회(transport));
	}

	@Test
	void 불완전한_구간이_하나라도_있으면_전체_결과를_거절한다() {
		조회_실패_검증(() -> 조회(200, """
			{"calendars":{"primary":{"busy":[
			 {"start":"2026-10-20T01:00:00Z","end":"2026-10-20T02:00:00Z"},
			 {"start":"2026-10-20T03:00:00Z"}]}}}
			"""));
	}

	private List<BusyBlock> 조회(int status, String body) {
		var transport = new MockHttpTransport.Builder().setLowLevelHttpResponse(new MockLowLevelHttpResponse()
			.setStatusCode(status).setContentType("application/json").setContent(body)).build();
		return 조회(transport);
	}

	private List<BusyBlock> 조회(HttpTransport transport) {
		var sdk = new Calendar.Builder(transport, GsonFactory.getDefaultInstance(), null).build();
		// SDK 응답 파싱은 실제로 수행하고, HTTP 전송만 격리한다.
		try (var construction = mockConstruction(Calendar.Builder.class, (builder, context) -> {
			when(builder.setApplicationName(anyString())).thenReturn(builder);
			when(builder.build()).thenReturn(sdk);
		})) {
			return new GoogleCalendarClient(properties).getBusyBlocks("test-only-access-token", "primary", start, end);
		}
	}

	private void 조회_실패_검증(Runnable action) {
		assertThatThrownBy(action::run).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
	}
}
