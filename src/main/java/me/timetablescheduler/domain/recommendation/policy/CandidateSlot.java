package me.timetablescheduler.domain.recommendation.policy;

import java.time.LocalDateTime;
import me.timetablescheduler.domain.recommendation.type.RecommendationMatchType;

public record CandidateSlot(
	LocalDateTime startAt,
	LocalDateTime endAt,
	int score,
	String reason,
	RecommendationMatchType matchType
) {
	public CandidateSlot(LocalDateTime startAt, LocalDateTime endAt, int score, String reason) {
		this(startAt, endAt, score, reason, null);
	}
}
