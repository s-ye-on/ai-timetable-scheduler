package me.timetablescheduler.domain.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.Test;

class CalendarServiceTest {

	private final CalendarTokenRepository calendarTokenRepository = mock(CalendarTokenRepository.class);
	private final UserReader userReader = mock(UserReader.class);
	private final GoogleOAuthClient googleOAuthClient = mock(GoogleOAuthClient.class);
	private final GoogleCalendarClient googleCalendarClient = mock(GoogleCalendarClient.class);
	private final CalendarService calendarService = new CalendarService(
		calendarTokenRepository,
		userReader,
		googleOAuthClient,
		googleCalendarClient,
		Clock.systemDefaultZone()
	);

	@Test
	void 추천_생성용_busy_block_조회는_Google_Calendar가_연동되지_않으면_빈_목록을_반환한다() {
		LocalDateTime startAt = LocalDateTime.of(2026, 6, 8, 0, 0);
		LocalDateTime endAt = LocalDateTime.of(2026, 6, 21, 23, 59);

		when(calendarTokenRepository.findByUserId(1L)).thenReturn(Optional.empty());

		List<BusyBlock> busyBlocks = calendarService.getCalendarBusyBlocksOrEmpty(1L, startAt, endAt);

		assertEquals(List.of(), busyBlocks);
		verifyNoInteractions(googleCalendarClient);
	}

	@Test
	void Google_Calendar_연결_여부를_예외_없이_확인한다() {
		when(calendarTokenRepository.existsByUserId(1L)).thenReturn(false);
		when(calendarTokenRepository.existsByUserId(2L)).thenReturn(true);

		assertEquals(false, calendarService.isConnected(1L));
		assertEquals(true, calendarService.isConnected(2L));
	}

	@Test
	void 기존_busy_block_조회는_Google_Calendar가_연동되지_않으면_예외를_던진다() {
		LocalDateTime startAt = LocalDateTime.of(2026, 6, 8, 0, 0);
		LocalDateTime endAt = LocalDateTime.of(2026, 6, 21, 23, 59);

		when(calendarTokenRepository.findByUserId(1L)).thenReturn(Optional.empty());

		CalendarException exception = assertThrows(
			CalendarException.class,
			() -> calendarService.getCalendarBusyBlocks(1L, startAt, endAt)
		);

		assertEquals(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED, exception.getExceptionCode());
	}
}
