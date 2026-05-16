package me.timetablescheduler.domain.recommendation;

import java.util.List;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, Long> {
	List<Recommendation> findAllByTaskIdAndUserIdAndStatus(
		Long taskId,
		Long userId,
		RecommendationStatus status
	);
}
