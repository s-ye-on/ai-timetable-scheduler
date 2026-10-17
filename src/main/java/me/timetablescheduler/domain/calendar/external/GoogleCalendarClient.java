package me.timetablescheduler.domain.calendar.external;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.Data;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.FreeBusyCalendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import com.google.api.services.calendar.model.TimePeriod;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class GoogleCalendarClient {

	private static final String SYNC_ID_KEY = "timetableSchedulerSyncId";
	private static final Pattern GOOGLE_EVENT_ID_PATTERN = Pattern.compile("[0-9a-v]{5,1024}");

	private final GoogleCalendarProperties properties;
	private final HttpTransport httpTransport;

	@Autowired
	public GoogleCalendarClient(GoogleCalendarProperties properties) {
		this(properties, new NetHttpTransport());
	}

	GoogleCalendarClient(GoogleCalendarProperties properties, HttpTransport httpTransport) {
		this.properties = properties;
		this.httpTransport = httpTransport;
	}

	public List<BusyBlock> getBusyBlocks(
		String accessToken,
		String calendarId,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		try {
			FreeBusyRequest request = new FreeBusyRequest()
				.setTimeMin(toGoogleDateTime(startAt))
				.setTimeMax(toGoogleDateTime(endAt))
				.setTimeZone(properties.timeZone())
				.setItems(List.of(new FreeBusyRequestItem().setId(calendarId)));

			FreeBusyResponse response = createCalendar(accessToken)
				.freebusy()
				.query(request)
				.execute();

			if (response == null || response.getCalendars() == null) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
			}

			FreeBusyCalendar freeBusyCalendar = response.getCalendars().get(calendarId);
			if (freeBusyCalendar == null || Data.isNull(freeBusyCalendar)
				|| (freeBusyCalendar.getErrors() != null && !freeBusyCalendar.getErrors().isEmpty())) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
			}

			// Java SDK 계약상 busy는 일정이 없으면 null일 수 있다.
			if (freeBusyCalendar.getBusy() == null) {
				return List.of();
			}

			return freeBusyCalendar.getBusy()
				.stream()
				.map(this::toBusyBlock)
				.toList();
		} catch (IOException | IllegalArgumentException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
		}
	}

	public String createEvent(
		String accessToken,
		String calendarId,
		String eventId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		if (!isValidGoogleEventId(eventId)) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED);
		}

		try {
			Event event = new Event()
				.setId(eventId)
				.setSummary(title)
				.setDescription(normalizeText(description))
				.setStart(toEventDateTime(startAt))
				.setEnd(toEventDateTime(endAt))
				.setExtendedProperties(new Event.ExtendedProperties()
					.setPrivate(Map.of(SYNC_ID_KEY, eventId)));

			Event created = createCalendar(accessToken)
				.events()
				.insert(calendarId, event)
				.execute();

			if (!matchesExpectedEvent(created, eventId, title, description, startAt, endAt)) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH);
			}

			return eventId;
		} catch (IOException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED);
		}
	}

	public boolean hasMatchingEvent(
		String accessToken,
		String calendarId,
		String eventId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		if (!isValidGoogleEventId(eventId)) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED);
		}

		try {
			Event event = createCalendar(accessToken)
				.events()
				.get(calendarId, eventId)
				.execute();

			if (event == null || Data.isNull(event)) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED);
			}
			if (!matchesExpectedEvent(event, eventId, title, description, startAt, endAt)) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH);
			}

			return true;
		} catch (GoogleJsonResponseException e) {
			if (e.getStatusCode() == 404) {
				return false;
			}
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED);
		} catch (IOException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED);
		}
	}

	private Calendar createCalendar(String accessToken) {
		GoogleCredential credential = new GoogleCredential().setAccessToken(accessToken);

		return new Calendar.Builder(
			httpTransport,
			GsonFactory.getDefaultInstance(),
			credential
		)
			.setApplicationName(properties.applicationName())
			.build();
	}

	private EventDateTime toEventDateTime(LocalDateTime dateTime) {
		return new EventDateTime()
			.setDateTime(toGoogleDateTime(dateTime))
			.setTimeZone(properties.timeZone());
	}

	private DateTime toGoogleDateTime(LocalDateTime dateTime) {
		return new DateTime(dateTime
			.atZone(zoneId())
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
	}

	private BusyBlock toBusyBlock(TimePeriod timePeriod) {
		if (timePeriod == null || timePeriod.getStart() == null || timePeriod.getEnd() == null
			|| Data.isNull(timePeriod.getStart()) || Data.isNull(timePeriod.getEnd())
			|| timePeriod.getStart().isDateOnly() || timePeriod.getEnd().isDateOnly()
			|| timePeriod.getStart().getValue() >= timePeriod.getEnd().getValue()) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
		}

		return new BusyBlock(
			toLocalDateTime(timePeriod.getStart()),
			toLocalDateTime(timePeriod.getEnd())
		);
	}

	private LocalDateTime toLocalDateTime(DateTime dateTime) {
		return Instant.ofEpochMilli(dateTime.getValue())
			.atZone(zoneId())
			.toLocalDateTime();
	}

	private ZoneId zoneId() {
		return ZoneId.of(properties.timeZone());
	}

	private boolean matchesExpectedEvent(
		Event event,
		String eventId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		return event != null
			&& !Data.isNull(event)
			&& eventId.equals(event.getId())
			&& (event.getStatus() == null || "confirmed".equals(event.getStatus()))
			&& matchesSyncId(event, eventId)
			&& Objects.equals(title, event.getSummary())
			&& Objects.equals(normalizeText(description), normalizeText(event.getDescription()))
			&& matchesDateTime(event.getStart(), startAt)
			&& matchesDateTime(event.getEnd(), endAt)
			&& (event.getRecurrence() == null || event.getRecurrence().isEmpty())
			&& event.getRecurringEventId() == null
			&& !Boolean.TRUE.equals(event.getEndTimeUnspecified())
			&& !"transparent".equals(event.getTransparency());
	}

	private boolean matchesSyncId(Event event, String eventId) {
		if (event.getExtendedProperties() == null || Data.isNull(event.getExtendedProperties())) {
			return false;
		}

		Map<String, String> privateProperties = event.getExtendedProperties().getPrivate();
		return privateProperties != null && eventId.equals(privateProperties.get(SYNC_ID_KEY));
	}

	private boolean matchesDateTime(EventDateTime eventDateTime, LocalDateTime expected) {
		return eventDateTime != null
			&& !Data.isNull(eventDateTime)
			&& eventDateTime.getDate() == null
			&& eventDateTime.getDateTime() != null
			&& !Data.isNull(eventDateTime.getDateTime())
			&& eventDateTime.getDateTime().getValue() == toGoogleDateTime(expected).getValue();
	}

	private boolean isValidGoogleEventId(String eventId) {
		return eventId != null && GOOGLE_EVENT_ID_PATTERN.matcher(eventId).matches();
	}

	private String normalizeText(String value) {
		return value == null ? "" : value;
	}
}
