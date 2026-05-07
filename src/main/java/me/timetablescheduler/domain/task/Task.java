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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
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
	private OffsetDateTime createAt;

	@Column(nullable = false)
	private OffsetDateTime updateAt;

	private Task(
		User user,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		validateRequiredFields(user, title, category, durationMinutes, priority);
		validateDuration(durationMinutes);

		this.user = user;
		this.title = title;
		this.category = category;
		this.durationMinutes = durationMinutes;
		this.deadline = deadline;
		this.priority = priority;
		this.description = description;
		this.status = TaskStatus.UNSCHEDULED;
		this.createAt = OffsetDateTime.now();
		this.updateAt = OffsetDateTime.now();
	}

	public static Task create(
		User user,
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		return new Task(user, title, category, durationMinutes, deadline, priority, description);
	}

	public void updateDetails(
		String title,
		TaskCategory category,
		Integer durationMinutes,
		LocalDate deadline,
		TaskPriority priority,
		String description
	) {
		validateRequiredFields(this.user, title, category, durationMinutes, priority);
		validateDuration(durationMinutes);

		this.title = title;
		this.category = category;
		this.durationMinutes = durationMinutes;
		this.deadline = deadline;
		this.priority = priority;
		this.description = description;
		this.updateAt = OffsetDateTime.now();
	}

	public void schedule(LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt) {
		validateScheduleTime(scheduledStartAt, scheduledEndAt);

		this.scheduledStartAt = scheduledStartAt;
		this.scheduledEndAt = scheduledEndAt;
		this.status = TaskStatus.SCHEDULED;
		this.updateAt = OffsetDateTime.now();
	}

	public void unschedule() {
		this.scheduledStartAt = null;
		this.scheduledEndAt = null;
		this.status = TaskStatus.UNSCHEDULED;
		this.updateAt = OffsetDateTime.now();
	}

	public void complete() {
		this.status = TaskStatus.COMPLETED;
		this.updateAt = OffsetDateTime.now();
	}

	public void cancel() {
		this.status = TaskStatus.CANCELED;
		this.updateAt = OffsetDateTime.now();
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

	private void validateScheduleTime(LocalDateTime scheduledStartAt, LocalDateTime scheduledEndAt) {
		if (scheduledStartAt == null || scheduledEndAt == null || !scheduledStartAt.isBefore(scheduledEndAt)) {
			throw new TaskException(ExceptionCode.INVALID_TASK_SCHEDULE_TIME);
		}
	}
}
