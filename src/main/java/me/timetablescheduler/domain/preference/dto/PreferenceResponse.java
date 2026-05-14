package me.timetablescheduler.domain.preference.dto;

import java.time.LocalTime;

import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;

public sealed interface PreferenceResponse
	permits PreferenceResponse.Read {

	record Read(
		Long id,
		PreferredTimeRange preferredTimeRange,
		LocalTime scheduleStartTime,
		LocalTime scheduleEndTime,
		Integer minimumGapMinutes,
		ScheduleDensity scheduleDensity,
		DeadlineTiming deadlineTiming,
		boolean customized
	) implements PreferenceResponse {
	}
}
