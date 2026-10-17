package me.timetablescheduler.domain.recommendation;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
	boolean existsByUserIdAndStatusIn(Long userId, Collection<RecommendationStatus> statuses);

	Optional<Recommendation> findByIdAndUserId(Long id, Long userId);

	List<Recommendation> findAllByTaskIdAndUserId(Long taskId, Long userId);

	List<Recommendation> findAllByTaskIdAndUserIdAndStatusOrderByRankAsc(
		Long taskId,
		Long userId,
		RecommendationStatus status
	);

	List<Recommendation> findAllByTaskIdAndUserIdAndStatus(
		Long taskId,
		Long userId,
		RecommendationStatus status
	);
}
