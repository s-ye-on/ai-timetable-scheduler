package me.timetablescheduler.domain.recommendation.type;

import lombok.Getter;

import java.time.LocalTime;

@Getter
public enum PreferredTimeRange {
	MORNING(LocalTime.of(9, 0), LocalTime.of(12, 0)),
	LUNCH(LocalTime.of(12, 0), LocalTime.of(14, 0)),
	AFTERNOON(LocalTime.of(14, 0), LocalTime.of(18, 0)),
	EVENING(LocalTime.of(18, 0), LocalTime.of(22, 0)),
	ANYTIME(LocalTime.of(9, 0), LocalTime.of(22, 0)),
	;

	private final LocalTime startTime;
	private final LocalTime endTime;

	PreferredTimeRange(LocalTime startTime, LocalTime endTime) {
		this.startTime = startTime;
		this.endTime = endTime;
	}
}
