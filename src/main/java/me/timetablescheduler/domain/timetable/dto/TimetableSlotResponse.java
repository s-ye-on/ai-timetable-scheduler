package me.timetablescheduler.domain.timetable.dto;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

public sealed interface TimetableSlotResponse permits TimetableSlotResponse.Read {
	record Read(
		String subjectName,
		DayOfWeek dayOfWeek,
		String location,
		LocalDateTime startTime,
		LocalDateTime endTime
	) implements TimetableSlotResponse {
	}
}
