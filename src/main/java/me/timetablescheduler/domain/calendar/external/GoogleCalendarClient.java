package me.timetablescheduler.domain.calendar.external;

import java.time.LocalDateTime;
import java.util.List;

import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import org.springframework.stereotype.Component;

@Component
public class GoogleCalendarClient {

	public List<BusyBlock> getBusyBlocks(
		String accessToken,
		String calendarId,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		throw new UnsupportedOperationException("Google Calendar busy block 조회는 아직 구현되지 않았습니다.");
	}

	public String createEvent(
		String accessToken,
		String calendarId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		throw new UnsupportedOperationException("Google Calendar event 생성은 아직 구현되지 않았습니다.");
	}
}
