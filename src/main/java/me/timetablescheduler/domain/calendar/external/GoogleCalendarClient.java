package me.timetablescheduler.domain.calendar.external;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.FreeBusyCalendar;
import com.google.api.services.calendar.model.FreeBusyRequest;
import com.google.api.services.calendar.model.FreeBusyRequestItem;
import com.google.api.services.calendar.model.FreeBusyResponse;
import com.google.api.services.calendar.model.TimePeriod;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoogleCalendarClient {

	private final GoogleCalendarProperties properties;

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

			if (response.getCalendars() == null) {
				return List.of();
			}

			FreeBusyCalendar freeBusyCalendar = response.getCalendars().get(calendarId);
			if (freeBusyCalendar == null || freeBusyCalendar.getBusy() == null) {
				return List.of();
			}

			return freeBusyCalendar.getBusy()
				.stream()
				.filter(timePeriod -> timePeriod.getStart() != null && timePeriod.getEnd() != null)
				.map(this::toBusyBlock)
				.toList();
		} catch (IOException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
		}
	}

	public String createEvent(
		String accessToken,
		String calendarId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		try {
			Event event = new Event()
				.setSummary(title)
				.setDescription(description)
				.setStart(toEventDateTime(startAt))
				.setEnd(toEventDateTime(endAt));

			Event created = createCalendar(accessToken)
				.events()
				.insert(calendarId, event)
				.execute();

			if (created == null || created.getId() == null || created.getId().isBlank()) {
				throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED);
			}

			return created.getId();
		} catch (IOException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED);
		}
	}

	private Calendar createCalendar(String accessToken) {
		GoogleCredential credential = new GoogleCredential().setAccessToken(accessToken);

		return new Calendar.Builder(
			new NetHttpTransport(),
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
}
