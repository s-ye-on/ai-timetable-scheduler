package me.timetablescheduler.global.dto;

import java.time.DayOfWeek;
import java.time.LocalDateTime;

public sealed interface TimetableSlotRequest permits TimetableSlotRequest.Create {
	record Create(
		DayOfWeek dayOfWeek,
		String location,
		LocalDateTime startTime,
		LocalDateTime endTime
	) implements TimetableSlotRequest {
	}
}
