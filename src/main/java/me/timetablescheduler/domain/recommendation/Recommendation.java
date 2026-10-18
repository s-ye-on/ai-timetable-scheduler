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
import me.timetablescheduler.domain.task.type.TaskStatus;
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

	@Column(length = 255)
	// SELECTED/SYNC_FAILED에서는 재시도에 사용할 예약 ID, SYNCED에서만 외부 확인이 완료된 ID다.
	private String googleEventId;

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
		validateSameUser(user, task);
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
		validateSelectable();

		this.status = RecommendationStatus.SELECTED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void validateSelectable() {
		validateStatus(RecommendationStatus.PROPOSED);
	}

	public void reserveCalendarSync(String eventId) {
		validateGoogleEventId(eventId);
		select();
		this.googleEventId = eventId;
	}

	public void resumeCalendarSync() {
		validateCalendarSync();
		this.status = RecommendationStatus.SELECTED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void validateCalendarSync() {
		if (!isCalendarSyncRetryable()) {
			throw new RecommendationException(ExceptionCode.GOOGLE_CALENDAR_SYNC_RECOVERY_REQUIRED);
		}
	}

	// 외부 등록의 성공 보장이 아니라, T08의 안전한 확인·복구를 시도할 수 있는 내부 상태다.
	public boolean isCalendarSyncRetryable() {
		return (status == RecommendationStatus.SELECTED || status == RecommendationStatus.SYNC_FAILED)
			&& googleEventId != null && !googleEventId.isBlank()
			&& task.getStatus() == TaskStatus.SCHEDULED
			&& recommendedStartAt.equals(task.getScheduledStartAt())
			&& recommendedEndAt.equals(task.getScheduledEndAt());
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

	public void sync(String googleEventId) {
		validateStatus(RecommendationStatus.SELECTED);
		validateGoogleEventId(googleEventId);
		if (this.googleEventId != null && !this.googleEventId.equals(googleEventId)) {
			throw new RecommendationException(ExceptionCode.GOOGLE_CALENDAR_EVENT_MISMATCH);
		}

		this.googleEventId = googleEventId;
		this.status = RecommendationStatus.SYNCED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void failSync() {
		validateStatus(RecommendationStatus.SELECTED);

		this.status = RecommendationStatus.SYNC_FAILED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void validateDeletable() {
		if (googleEventId != null || (status != RecommendationStatus.PROPOSED
			&& status != RecommendationStatus.REJECTED && status != RecommendationStatus.EXPIRED)) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION);
		}
	}

	private void validateSameUser(User user, Task task) {
		if (task.getUser().getId() != null && user.getId() != null) {
			if (!task.getUser().getId().equals(user.getId())) {
				throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION);
			}
			return;
		}

		if (task.getUser() != user) {
			throw new RecommendationException(ExceptionCode.INVALID_RECOMMENDATION);
		}
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

	private void validateGoogleEventId(String googleEventId) {
		if (googleEventId == null || googleEventId.isBlank()) {
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
