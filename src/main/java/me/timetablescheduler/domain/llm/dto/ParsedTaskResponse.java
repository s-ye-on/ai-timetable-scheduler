package me.timetablescheduler.domain.llm.dto;

import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;

import java.time.DayOfWeek;
import java.time.LocalDate;

public record ParsedTaskResponse(
	String title,
	TaskCategory category,
	Integer durationMinutes,
	LocalDate preferredDate,
	DayOfWeek preferredDayOfWeek,
	DateRange preferredDateRange,
	PreferredTimeRange preferredTimeRange,
	LocalDate deadline,
	TaskPriority priority,
	String description
) {
}
