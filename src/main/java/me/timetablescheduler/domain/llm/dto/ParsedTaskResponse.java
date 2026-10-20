package me.timetablescheduler.domain.llm.dto;

import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

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
	String description,
	Boolean dateRequired,
	Boolean timeRequired,
	LocalTime preferredStartTime,
	String clarificationReason,
	RelativeStartMode relativeStartMode,
	Integer startOffsetMinutes
) {
	public ParsedTaskResponse(String title, TaskCategory category, Integer durationMinutes,
		LocalDate preferredDate, DayOfWeek preferredDayOfWeek, DateRange preferredDateRange,
		PreferredTimeRange preferredTimeRange, LocalDate deadline, TaskPriority priority, String description,
		Boolean dateRequired, Boolean timeRequired, LocalTime preferredStartTime, String clarificationReason) {
		this(title, category, durationMinutes, preferredDate, preferredDayOfWeek, preferredDateRange,
			preferredTimeRange, deadline, priority, description, dateRequired, timeRequired,
			preferredStartTime, clarificationReason, null, null);
	}

	public ParsedTaskResponse(
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
		this(title, category, durationMinutes, preferredDate, preferredDayOfWeek, preferredDateRange,
			preferredTimeRange, deadline, priority, description, false, false, null, null);
	}
}
