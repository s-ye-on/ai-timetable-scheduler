package me.timetablescheduler.domain.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;

public sealed interface TaskRequest
	permits TaskRequest.NaturalLanguage,
	TaskRequest.Update {

	record NaturalLanguage(
		@NotBlank
		String message
	) implements TaskRequest {
	}

	record Update(
		@NotBlank(message = "할 일 제목은 필수입니다.")
		String title,

		@NotNull(message = "할 일 카테고리는 필수입니다.")
		TaskCategory category,

		@NotNull(message = "할 일 소요 시간은 필수입니다.")
		Integer durationMinutes,

		LocalDate preferredDate,

		DayOfWeek preferredDayOfWeek,

		LocalDate preferredStartDate,

		LocalDate preferredEndDate,

		PreferredTimeRange preferredTimeRange,

		LocalDate deadline,

		@NotNull(message = "할 일 우선순위는 필수입니다.")
		TaskPriority priority,

		String description
	) implements TaskRequest {
	}
}
