package me.timetablescheduler.domain.llm.dto;

import me.timetablescheduler.domain.task.Task;

import java.time.DayOfWeek;
import java.time.LocalDate;

public record ParsedTaskResponse(
	String title,
	Task.Category taskType,
	DayOfWeek preferredDayOfWeek,
	String preferredTimeLabel,
	LocalDate preferredDate,
	Integer durationMinutes,
	String description
) {
}
