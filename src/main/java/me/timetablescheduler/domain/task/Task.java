package me.timetablescheduler.domain.task;

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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Task {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false, length = 100)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TaskCategory category;

	@Column(nullable = false)
	private Integer durationMinutes;

	private LocalDate preferredDate;

	@Enumerated(EnumType.STRING)
	@Column(length = 10)
	private DayOfWeek preferredDayOfWeek;

	private LocalDate preferredStartDate;

	private LocalDate preferredEndDate;

	@Enumerated(EnumType.STRING)
	@Column(length = 20)
	// preferredTimeRange가 Task에 있을 때 의미:
	// - 이번 Task에서 사용자가 명시한 선호 시간대
	// "이번 주 안에 과제할 시간을 추천해줘" : 이 문장에서는 특정 시간대 선호가 없음
	// Task.preferredTimeRange는 비어 있을 수 있음
	private PreferredTimeRange preferredTimeRange;

	private LocalDate deadline;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TaskPriority priority;

	@Column(length = 500)
	private String description;

	private LocalDateTime scheduledStartAt;

	private LocalDateTime scheduledEndAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private TaskStatus status;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	private Task(
		User user,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate,
		PreferredTimeRange preferredTimeRange,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		validateRequiredFields(user, title, category, durationMinutes, priority);
		validateDuration(durationMinutes);
		validateDateCondition(preferredDate, preferredDayOfWeek, preferredStartDate, preferredEndDate);

		this.user = user;
		this.title = title;
		this.category = category;
		this.durationMinutes = durationMinutes;
		this.preferredDate = preferredDate;
		this.preferredDayOfWeek = preferredDayOfWeek;
		this.preferredStartDate = preferredStartDate;
		this.preferredEndDate = preferredEndDate;
		this.preferredTimeRange = preferredTimeRange;
		this.deadline = deadline;
		this.priority = priority;
		this.description = description;
		this.status = TaskStatus.UNSCHEDULED;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = OffsetDateTime.now();
	}

	public static Task create(
		User user,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate,
		PreferredTimeRange preferredTimeRange,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		return new Task(
			user,
			title,
			category,
			durationMinutes,
			preferredDate,
			preferredDayOfWeek,
			preferredStartDate,
			preferredEndDate,
			preferredTimeRange,
			deadline,
			priority,
			description
		);
	}

	public void updateDetails(
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate,
		PreferredTimeRange preferredTimeRange,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		validateRequiredFields(this.user, title, category, durationMinutes, priority);
		validateDuration(durationMinutes);
		validateDateCondition(preferredDate, preferredDayOfWeek, preferredStartDate, preferredEndDate);

		this.title = title;
		this.category = category;
		this.durationMinutes = durationMinutes;
		this.preferredDate = preferredDate;
		this.preferredDayOfWeek = preferredDayOfWeek;
		this.preferredStartDate = preferredStartDate;
		this.preferredEndDate = preferredEndDate;
		this.preferredTimeRange = preferredTimeRange;
		this.deadline = deadline;
		this.priority = priority;
		this.description = description;
		this.updatedAt = OffsetDateTime.now();
	}

	public void schedule(LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt) {
		validateStatus(TaskStatus.UNSCHEDULED);
		validateScheduleTime(scheduledStartAt, scheduledEndAt);

		this.scheduledStartAt = scheduledStartAt;
		this.scheduledEndAt = scheduledEndAt;
		this.status = TaskStatus.SCHEDULED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void unschedule() {
		validateStatus(TaskStatus.SCHEDULED);

		this.scheduledStartAt = null;
		this.scheduledEndAt = null;
		this.status = TaskStatus.UNSCHEDULED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void complete(LocalDateTime now) {
		validateStatus(TaskStatus.SCHEDULED);
		validateCompleteTime(now);

		this.status = TaskStatus.COMPLETED;
		this.updatedAt = OffsetDateTime.now();
	}

	public void cancel() {
		if (status != TaskStatus.UNSCHEDULED && status != TaskStatus.SCHEDULED) {
			throw new TaskException(ExceptionCode.INVALID_TASK_STATUS_TRANSITION);
		}

		this.status = TaskStatus.CANCELED;
		this.updatedAt = OffsetDateTime.now();
	}

	private void validateRequiredFields(
		User user,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		TaskPriority priority
	) {
		if (user == null || title == null || title.isBlank()
			|| category == null || durationMinutes == null || priority == null) {
			throw new TaskException(ExceptionCode.INVALID_TASK);
		}
	}

	private void validateDuration(Integer durationMinutes) {
		if (durationMinutes <= 0 || durationMinutes % 30 != 0) {
			throw new TaskException(ExceptionCode.INVALID_TASK_DURATION);
		}
	}

	private void validateDateCondition(
		LocalDate preferredDate,
		DayOfWeek preferredDayOfWeek,
		LocalDate preferredStartDate,
		LocalDate preferredEndDate
	) {
		boolean hasPreferredDate = preferredDate != null;
		boolean hasPreferredDayOfWeek = preferredDayOfWeek != null;
		boolean hasPreferredDateRange = preferredStartDate != null || preferredEndDate != null;

		int dateConditionCount = 0;
		if (hasPreferredDate) {
			dateConditionCount++;
		}
		if (hasPreferredDayOfWeek) {
			dateConditionCount++;
		}
		if (hasPreferredDateRange) {
			dateConditionCount++;
		}

		if (dateConditionCount != 1) {
			throw new TaskException(ExceptionCode.INVALID_TASK);
		}

		if (hasPreferredDateRange && (preferredStartDate == null || preferredEndDate == null
			|| preferredStartDate.isAfter(preferredEndDate))) {
			throw new TaskException(ExceptionCode.INVALID_TASK);
		}
	}

	private void validateScheduleTime(LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt) {
		if (scheduledStartAt == null || scheduledEndAt == null || !scheduledStartAt.isBefore(scheduledEndAt)) {
			throw new TaskException(ExceptionCode.INVALID_TASK_SCHEDULE_TIME);
		}

		if (Duration.between(scheduledStartAt, scheduledEndAt).toMinutes() != durationMinutes) {
			throw new TaskException(ExceptionCode.INVALID_TASK_SCHEDULE_TIME);
		}
	}

	private void validateCompleteTime(LocalDateTime now) {
		if (now == null || now.isBefore(scheduledEndAt)) {
			throw new TaskException(ExceptionCode.INVALID_TASK_STATUS_TRANSITION);
		}
	}

	private void validateStatus(TaskStatus expectedStatus) {
		if (status != expectedStatus) {
			throw new TaskException(ExceptionCode.INVALID_TASK_STATUS_TRANSITION);
		}
	}
}
