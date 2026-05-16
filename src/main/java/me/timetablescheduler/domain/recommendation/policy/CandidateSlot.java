package me.timetablescheduler.domain.recommendation.policy;

import java.time.LocalDateTime;

public record CandidateSlot(
	LocalDateTime startAt,
	LocalDateTime endAt,
	int score,
	String reason
) {
}
