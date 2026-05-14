package me.timetablescheduler.domain.preference.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;

public sealed interface PreferenceRequest
	permits PreferenceRequest.Update {

	record Update(
		@NotNull(message = "기본 선호 시간대는 필수입니다.")
		PreferredTimeRange preferredTimeRange,

		@NotNull(message = "추천 가능 시작 시간은 필수입니다.")
		LocalTime scheduleStartTime,

		@NotNull(message = "추천 가능 종료 시간은 필수입니다.")
		LocalTime scheduleEndTime,

		@NotNull(message = "최소 여유 시간은 필수입니다.")
		Integer minimumGapMinutes,

		@NotNull(message = "일정 밀도 선호는 필수입니다.")
		ScheduleDensity scheduleDensity,

		@NotNull(message = "마감 일정 배치 선호는 필수입니다.")
		DeadlineTiming deadlineTiming
	) implements PreferenceRequest {
	}
}
