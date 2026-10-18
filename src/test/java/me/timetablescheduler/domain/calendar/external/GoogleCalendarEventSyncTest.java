package me.timetablescheduler.domain.calendar.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.LocalDateTime;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GoogleCalendarEventSyncTest {

	private static final GoogleCalendarProperties PROPERTIES = new GoogleCalendarProperties(
		"test-client", "test-only-secret", "http://localhost/callback", "calendar-scope", "test-app", "Asia/Seoul"
	);
	private static final String EVENT_ID = "1234567890abcdef1234567890abcdef";
	private static final String TITLE = "동기화 테스트";
	private static final LocalDateTime START = LocalDateTime.of(2026, 10, 21, 10, 0);
	private static final LocalDateTime END = START.plusHours(1);

	@Test
	void 이벤트_생성은_고정된_ID를_사용하고_반환된_이벤트를_검증한다() {
		String createdId = 클라이언트_생성(200, 이벤트_JSON_생성(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.createEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END);

		assertThat(createdId).isEqualTo(EVENT_ID);
	}

	@Test
	void 이벤트_생성은_불완전하거나_다른_Google_응답을_거절한다() {
		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> 클라이언트_생성(200,
			이벤트_JSON_생성(EVENT_ID, "confirmed", "different-event-id", TITLE, null, false))
			.createEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void 이벤트_생성은_Google_이벤트_ID_형식을_위반한_ID를_거절한다() {
		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED, () -> 클라이언트_생성(200,
			이벤트_JSON_생성(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.createEvent("test-only-access-token", "primary", "not-a-valid-google-event-id", TITLE, null, START, END));
	}

	@Test
	void 이벤트_없음이_확인된_조회_결과만_부재로_처리한다() {
		assertThat(클라이언트_생성(404, "{\"error\":{\"message\":\"not found\"}}")
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {403, 410, 429, 500, 503})
	void 조회_HTTP_상태가_404가_아닌_실패는_존재_여부_불확실로_처리한다(int status) {
		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED, () -> 클라이언트_생성(status,
			"{\"error\":{\"message\":\"test-only-error\"}}")
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void 취소되거나_일치하지_않는_이벤트를_동기화_성공으로_재사용할_수_없다() {
		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> 클라이언트_생성(200,
			이벤트_JSON_생성(EVENT_ID, "cancelled", EVENT_ID, TITLE, null, false))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));

		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> 클라이언트_생성(200,
			이벤트_JSON_생성(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, true))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void 저장된_유효_Google_이벤트는_요청한_동기화_내용과_일치한다() {
		assertThat(클라이언트_생성(200, 이벤트_JSON_생성(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END)).isTrue();
	}

	@Test
	void 네트워크_장애를_이벤트_없음으로_처리하지_않는다() {
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
				throw new java.net.SocketTimeoutException("test-only-timeout");
			}
		};

		GoogleCalendarClient client = new GoogleCalendarClient(PROPERTIES, transport);
		예외_코드_검증(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED, () -> client
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	private GoogleCalendarClient 클라이언트_생성(int status, String body) {
		MockHttpTransport transport = new MockHttpTransport.Builder()
			.setLowLevelHttpResponse(new MockLowLevelHttpResponse()
				.setStatusCode(status)
				.setContentType("application/json")
				.setContent(body))
			.build();
		return new GoogleCalendarClient(PROPERTIES, transport);
	}

	private String 이벤트_JSON_생성(String eventId, String status, String marker, String title, String description, boolean dateOnly) {
		String start = dateOnly ? "\"date\":\"2026-10-21\"" : "\"dateTime\":\"2026-10-21T10:00:00+09:00\"";
		String end = dateOnly ? "\"date\":\"2026-10-22\"" : "\"dateTime\":\"2026-10-21T11:00:00+09:00\"";
		String descriptionField = description == null ? "" : ",\"description\":\"%s\"".formatted(description);
		return """
			{\"id\":\"%s\",\"status\":\"%s\",\"summary\":\"%s\"%s,
			\"start\":{%s},\"end\":{%s},
			\"extendedProperties\":{\"private\":{\"timetableSchedulerSyncId\":\"%s\"}}}
			""".formatted(eventId, status, title, descriptionField, start, end, marker);
	}

	private void 예외_코드_검증(ExceptionCode expected, Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode()).isEqualTo(expected));
	}
}
