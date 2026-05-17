package me.timetablescheduler.domain.recommendation.controller;

import java.util.List;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
import me.timetablescheduler.domain.recommendation.service.RecommendationService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecommendationController {
	private final RecommendationService recommendationService;

	@PostMapping("/tasks/{taskId}/recommendations")
	public List<RecommendationResponse.Read> recommend(
		@PathVariable Long taskId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return recommendationService.recommend(userDetails.getId(), taskId);
	}

	@GetMapping("/tasks/{taskId}/recommendation")
	public List<RecommendationResponse.Read> readProposedByTask(
		@PathVariable Long taskId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return recommendationService.readProposedByTask(userDetails.getId(), taskId);
	}

	@PostMapping("/recommendations/{recommendationId}/select")
	public RecommendationResponse.Read select(
		@PathVariable Long recommendationId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return recommendationService.select(userDetails.getId(), recommendationId);
	}
}
