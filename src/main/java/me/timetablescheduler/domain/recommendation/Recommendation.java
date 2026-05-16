package me.timetablescheduler.domain.recommendation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.RecommendationException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Recommendation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@Column(nullable = false)
	private LocalDateTime recommendedStartAt;

	@Column(nullable = false)
	private LocalDateTime recommendedEndAt;

	@Column(nullable = false)
	private Integer rank;

	@Column(nullable = false)
	private Integer score;

	@Column(nullable = false, length = 500)
	private String reason;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RecommendationStatus status;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	private Recommendation(
		User user,
		Task task,
		LocalDateTime recommendedStartAt,
		LocalDateTime recommendedEndAt,
		Integer rank,
		Integer score,
		String reason
	) {
		validateRequiredFields(user, task, recommendedStartAt, recommendedEndAt, rank, score, reason);
		validateRank(rank);
		validateScore(score);
		validateRecommendedTime(task, recommendedStartAt, recommendedEndAt);

		this.user = user;
		this.task = task;
		this.recommendedStartAt = recommendedStartAt;
		this.recommendedEndAt = recommendedEndAt;
		this.rank = rank;
		this.score = score;
		this.reason = reason;
		this.status = RecommendationStatus.PROPOSED;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = OffsetDateTime.now();
	}

	public static Recommendation create(
		User user,
		Task task,
		LocalDateTime recommendedStartAt,
		LocalDateTime recommendedEndAt,
		Integer rank,
		Integer score,
		String reason
	) {
		return new Recommendation(user, task, recommendedStartAt, recommendedEndAt, rank, score, reason);
	}

	public void select() {
		validateStatus(RecommendationStatus.PROPOSED);

		this.status = RecommendationStatus.SELECTED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void reject() {
		validateStatus(RecommendationStatus.PROPOSED);

		this.status = RecommendationStatus.REJECTED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void expire() {
		validateStatus(RecommendationStatus.PROPOSED);

		this.status = RecommendationStatus.EXPIRED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void sync() {
		validateStatus(RecommendationStatus.SELECTED);

		this.status = RecommendationStatus.SYNCED;
		this.updatedAt = OffsetDateTime.now();
	}

	private void validateRequiredFields(
		User user,
		Task task,
		LocalDateTime recommendedStartAt,
		LocalDateTime recommendedEndAt,
		Integer rank,
		Integer score,
		String reason
	) {
		if (user == null || task == null || recommendedStartAt == null || recommendedEndAt == null
			|| rank == null || score == null || reason == null || reason.isBlank()) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION);
		}
	}

	private void validateRank(Integer rank) {
		if (rank < 1) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION);
		}
	}

	private void validateScore(Integer score) {
		if (score < 0) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION);
		}
	}

	private void validateRecommendedTime(
		Task task,
		LocalDateTime recommendedStartAt,
		LocalDateTime recommendedEndAt
	) {
		if (!recommendedStartAt.isBefore(recommendedEndAt)) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION_TIME);
		}

		if (Duration.between(recommendedStartAt, recommendedEndAt).toMinutes() != task.getDurationMinutes()) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION_TIME);
		}
	}

	private void validateStatus(RecommendationStatus expectedStatus) {
		if (status != expectedStatus) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION);
		}
	}
}
