package me.timetablescheduler.domain.timetable.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

public sealed interface TimetableSlotResponse permits TimetableSlotResponse.Read {
	record Read(
		Long id,
		String subjectName,
		DayOfWeek dayOfWeek,
		String location,
		LocalTime startTime,
		LocalTime endTime
	) implements TimetableSlotResponse {
	}
}
