package me.timetablescheduler.domain.recommendation.dto;

import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;

import java.time.LocalDateTime;

public sealed interface RecommendationResponse permits RecommendationResponse.Read {
	record Read(
		Long id,
		Long taskId,
		LocalDateTime recommendedStartAt,
		LocalDateTime recommendedEndAt,
		Integer rank,
		Integer score,
		String reason,
		RecommendationStatus status
	) implements RecommendationResponse {
	}
}
