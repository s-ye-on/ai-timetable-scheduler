package me.timetablescheduler.domain.preference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;

import java.time.LocalTime;
import java.time.OffsetDateTime;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.PreferenceException;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Preference {
	private static final PreferredTimeRange DEFAULT_PREFERRED_TIME_RANGE = PreferredTimeRange.ANYTIME;
	private static final LocalTime DEFAULT_SCHEDULE_START_TIME = LocalTime.of(9, 0);
	private static final LocalTime DEFAULT_SCHEDULE_END_TIME = LocalTime.of(22, 0);
	private static final int DEFAULT_MINIMUM_GAP_MINUTES = 10;
	private static final ScheduleDensity DEFAULT_SCHEDULE_DENSITY = ScheduleDensity.BALANCED;
	private static final DeadlineTiming DEFAULT_DEADLINE_TIMING = DeadlineTiming.BALANCED;
	private static final int GAP_MINUTE_UNIT = 5;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private PreferredTimeRange preferredTimeRange;

	@Column(nullable = false)
	private LocalTime scheduleStartTime;

	@Column(nullable = false)
	private LocalTime scheduleEndTime;

	@Column(nullable = false)
	private Integer minimumGapMinutes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ScheduleDensity scheduleDensity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DeadlineTiming deadlineTiming;

	@Column(nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(nullable = false)
	private OffsetDateTime updatedAt;

	private Preference(
		User user,
		PreferredTimeRange preferredTimeRange,
		LocalTime scheduleStartTime,
		LocalTime scheduleEndTime,
		Integer minimumGapMinutes,
		ScheduleDensity scheduleDensity,
		DeadlineTiming deadlineTiming
	) {
		validateRequiredFields(
			user,
			preferredTimeRange,
			scheduleStartTime,
			scheduleEndTime,
			minimumGapMinutes,
			scheduleDensity,
			deadlineTiming
		);
		validateScheduleTimeRange(scheduleStartTime, scheduleEndTime);
		validateMinimumGapMinutes(minimumGapMinutes);

		this.user = user;
		this.preferredTimeRange = preferredTimeRange;
		this.scheduleStartTime = scheduleStartTime;
		this.scheduleEndTime = scheduleEndTime;
		this.minimumGapMinutes = minimumGapMinutes;
		this.scheduleDensity = scheduleDensity;
		this.deadlineTiming = deadlineTiming;
		this.createdAt = OffsetDateTime.now();
		this.updatedAt = OffsetDateTime.now();
	}

	public static Preference createDefault(User user) {
		return new Preference(
			user,
			DEFAULT_PREFERRED_TIME_RANGE,
			DEFAULT_SCHEDULE_START_TIME,
			DEFAULT_SCHEDULE_END_TIME,
			DEFAULT_MINIMUM_GAP_MINUTES,
			DEFAULT_SCHEDULE_DENSITY,
			DEFAULT_DEADLINE_TIMING
		);
	}

	public static Preference create(
		User user,
		PreferredTimeRange preferredTimeRange,
		LocalTime scheduleStartTime,
		LocalTime scheduleEndTime,
		Integer minimumGapMinutes,
		ScheduleDensity scheduleDensity,
		DeadlineTiming deadlineTiming
	) {
		return new Preference(
			user,
			preferredTimeRange,
			scheduleStartTime,
			scheduleEndTime,
			minimumGapMinutes,
			scheduleDensity,
			deadlineTiming
		);
	}

	public void update(
		PreferredTimeRange preferredTimeRange,
		LocalTime scheduleStartTime,
		LocalTime scheduleEndTime,
		Integer minimumGapMinutes,
		ScheduleDensity scheduleDensity,
		DeadlineTiming deadlineTiming
	) {
		validateRequiredFields(
			this.user,
			preferredTimeRange,
			scheduleStartTime,
			scheduleEndTime,
			minimumGapMinutes,
			scheduleDensity,
			deadlineTiming
		);
		validateScheduleTimeRange(scheduleStartTime, scheduleEndTime);
		validateMinimumGapMinutes(minimumGapMinutes);

		this.preferredTimeRange = preferredTimeRange;
		this.scheduleStartTime = scheduleStartTime;
		this.scheduleEndTime = scheduleEndTime;
		this.minimumGapMinutes = minimumGapMinutes;
		this.scheduleDensity = scheduleDensity;
		this.deadlineTiming = deadlineTiming;
		this.updatedAt = OffsetDateTime.now();
	}

	private void validateRequiredFields(
		User user,
		PreferredTimeRange preferredTimeRange,
		LocalTime scheduleStartTime,
		LocalTime scheduleEndTime,
		Integer minimumGapMinutes,
		ScheduleDensity scheduleDensity,
		DeadlineTiming deadlineTiming
	) {
		if (user == null || preferredTimeRange == null || scheduleStartTime == null || scheduleEndTime == null
			|| minimumGapMinutes == null || scheduleDensity == null || deadlineTiming == null) {
			throw new PreferenceException(ExceptionCode.INVALID_PREFERENCE);
		}
	}

	private void validateScheduleTimeRange(LocalTime scheduleStartTime, LocalTime scheduleEndTime) {
		if (!scheduleStartTime.isBefore(scheduleEndTime)) {
			throw new PreferenceException(ExceptionCode.INVALID_PREFERENCE_TIME_RANGE);
		}
	}

	/// todo : 현재 음수와 5분 단위인지만 검증함. 최소 시간 상한선 추가하기
	private void validateMinimumGapMinutes(Integer minimumGapMinutes) {
		if (minimumGapMinutes < 0 || minimumGapMinutes % GAP_MINUTE_UNIT != 0) {
			throw new PreferenceException(ExceptionCode.INVALID_PREFERENCE_GAP);
		}
	}
}
