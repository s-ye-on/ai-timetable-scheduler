package me.timetablescheduler.domain.timetable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalTime;

public sealed interface TimetableSlotRequest permits TimetableSlotRequest.Create, TimetableSlotRequest.Update {
	record Create(
		@NotBlank(message = "과목명은 필수입니다.")
		String subjectName,

		@NotNull(message = "요일은 필수입니다.")
		DayOfWeek dayOfWeek,

		String location,

		@NotNull(message = "시작 시간은 필수입니다.")
		LocalTime startTime,

		@NotNull(message = "종료 시간은 필수입니다.")
		LocalTime endTime
	) implements TimetableSlotRequest {
	}

	record Update(
		@NotBlank(message = "과목명은 필수입니다.")
		String subjectName,

		@NotNull(message = "요일은 필수입니다.")
		DayOfWeek dayOfWeek,

		String location,

		@NotNull(message = "시작 시간은 필수입니다.")
		LocalTime startTime,

		@NotNull(message = "종료 시간은 필수입니다.")
		LocalTime endTime
	) implements TimetableSlotRequest {
	}
}
