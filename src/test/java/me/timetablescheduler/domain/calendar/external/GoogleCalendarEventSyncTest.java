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
	void createEventUsesStableEventIdAndVerifiesTheReturnedEvent() {
		String createdId = client(200, eventJson(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.createEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END);

		assertThat(createdId).isEqualTo(EVENT_ID);
	}

	@Test
	void createEventRejectsIncompleteOrDifferentGoogleResponses() {
		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> client(200,
			eventJson(EVENT_ID, "confirmed", "different-event-id", TITLE, null, false))
			.createEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void createEventRejectsIdsOutsideTheGoogleEventIdContract() {
		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED, () -> client(200,
			eventJson(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.createEvent("test-only-access-token", "primary", "not-a-valid-google-event-id", TITLE, null, START, END));
	}

	@Test
	void missingEventIsTheOnlyLookupResultTreatedAsAbsent() {
		assertThat(client(404, "{\"error\":{\"message\":\"not found\"}}")
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = {403, 410, 429, 500, 503})
	void non404LookupHttpFailuresRemainUnknown(int status) {
		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED, () -> client(status,
			"{\"error\":{\"message\":\"test-only-error\"}}")
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void cancelledOrMismatchedEventsCannotBeReusedAsSuccessfulSyncs() {
		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> client(200,
			eventJson(EVENT_ID, "cancelled", EVENT_ID, TITLE, null, false))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));

		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH, () -> client(200,
			eventJson(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, true))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	@Test
	void validPersistedGoogleEventMatchesTheRequestedSync() {
		assertThat(client(200, eventJson(EVENT_ID, "confirmed", EVENT_ID, TITLE, null, false))
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END)).isTrue();
	}

	@Test
	void networkFailureDoesNotBecomeAnAbsentEvent() {
		HttpTransport transport = new HttpTransport() {
			@Override
			protected LowLevelHttpRequest buildRequest(String method, String url) throws IOException {
				throw new java.net.SocketTimeoutException("test-only-timeout");
			}
		};

		GoogleCalendarClient client = new GoogleCalendarClient(PROPERTIES, transport);
		assertCode(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED, () -> client
			.hasMatchingEvent("test-only-access-token", "primary", EVENT_ID, TITLE, null, START, END));
	}

	private GoogleCalendarClient client(int status, String body) {
		MockHttpTransport transport = new MockHttpTransport.Builder()
			.setLowLevelHttpResponse(new MockLowLevelHttpResponse()
				.setStatusCode(status)
				.setContentType("application/json")
				.setContent(body))
			.build();
		return new GoogleCalendarClient(PROPERTIES, transport);
	}

	private String eventJson(String eventId, String status, String marker, String title, String description, boolean dateOnly) {
		String start = dateOnly ? "\"date\":\"2026-10-21\"" : "\"dateTime\":\"2026-10-21T10:00:00+09:00\"";
		String end = dateOnly ? "\"date\":\"2026-10-22\"" : "\"dateTime\":\"2026-10-21T11:00:00+09:00\"";
		String descriptionField = description == null ? "" : ",\"description\":\"%s\"".formatted(description);
		return """
			{\"id\":\"%s\",\"status\":\"%s\",\"summary\":\"%s\"%s,
			\"start\":{%s},\"end\":{%s},
			\"extendedProperties\":{\"private\":{\"timetableSchedulerSyncId\":\"%s\"}}}
			""".formatted(eventId, status, title, descriptionField, start, end, marker);
	}

	private void assertCode(ExceptionCode expected, Runnable action) {
		assertThatThrownBy(action::run)
			.isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode()).isEqualTo(expected));
	}
}
