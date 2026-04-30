package me.timetablescheduler.domain.llm.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;

public record ParsedTaskResponse(
	String title,
	String taskType,
	DayOfWeek preferredDayOfWeek,
	String preferredTimeLabel,
	LocalDate preferredDate,
	Integer durationMinutes,
	String description
) {
}
