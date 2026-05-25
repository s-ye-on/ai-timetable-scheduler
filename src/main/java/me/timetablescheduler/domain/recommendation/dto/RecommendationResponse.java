package me.timetablescheduler.domain.recommendation.dto;

import java.time.LocalDateTime;
import java.util.List;

import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;

public sealed interface RecommendationResponse
	permits RecommendationResponse.Generate, RecommendationResponse.Read {
	record Generate(
		String message,
		List<Read> recommendations
	) implements RecommendationResponse {
	}

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
