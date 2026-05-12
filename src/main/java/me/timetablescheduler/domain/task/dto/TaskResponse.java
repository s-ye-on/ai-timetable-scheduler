package me.timetablescheduler.domain.task.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;

public sealed interface TaskResponse permits TaskResponse.Create, TaskResponse.Read {

	record Create(
		Long id,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate,
		PreferredTimeRange preferredTimeRange,
		LocalDate deadline,
		TaskPriority priority,
		String description,
		TaskStatus status
	) implements TaskResponse {
	}

	record Read(
		Long id,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate,
		PreferredTimeRange preferredTimeRange,
		LocalDate deadline,
		TaskPriority priority,
		String description,
		LocalDateTime scheduledStartAt,
		LocalDateTime scheduledEndAt,
		TaskStatus status
	) implements TaskResponse {
	}
}
