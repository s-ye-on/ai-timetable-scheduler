package me.timetablescheduler.domain.task.dto;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
		Boolean dateRequired,
		Boolean timeRequired,
		LocalTime preferredStartTime,
		TaskStatus status
	) implements TaskResponse {
		public Create(
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
		) {
			this(id, title, category, durationMinutes, preferredDate, preferredDayOfWeek, preferredStartDate,
				preferredEndDate, preferredTimeRange, deadline, priority, description, null, null, null, status);
		}
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
		Boolean dateRequired,
		Boolean timeRequired,
		LocalTime preferredStartTime,
		LocalDateTime scheduledStartAt,
		LocalDateTime scheduledEndAt,
		TaskStatus status
	) implements TaskResponse {
		public Read(
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
		) {
			this(id, title, category, durationMinutes, preferredDate, preferredDayOfWeek, preferredStartDate,
				preferredEndDate, preferredTimeRange, deadline, priority, description, null, null, null,
				scheduledStartAt, scheduledEndAt, status);
		}
	}
}
