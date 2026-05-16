package me.timetablescheduler.domain.recommendation.policy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import org.springframework.stereotype.Component;

@Component
public class RecommendationPolicy {
	private static final int BASE_SCORE = 50;
	private static final int PREFERRED_TIME_RANGE_SCORE = 30;
	private static final int PRIORITY_HIGH_SCORE = 15;
	private static final int PRIORITY_NORMAL_SCORE = 5;
	private static final int DENSITY_COMPACT_SCORE = 10;
	private static final int DENSITY_RELAXED_SCORE = 10;
	private static final int DEADLINE_ASAP_MAX_SCORE = 20;
	private static final int DEADLINE_BALANCED_MAX_SCORE = 15;
	private static final int DEADLINE_NEAR_MAX_SCORE = 20;

	public List<CandidateSlot> generateCandidates(
		Task task,
		Preference preference,
		List<TimetableSlot> timetableSlots
	) {
		List<LocalDate> dates = resolveCandidateDates(task);

		List<CandidateSlot> candidates = new ArrayList<>();

		for (LocalDate date : dates) {
			LocalDateTime start = LocalDateTime.of(date, preference.getScheduleStartTime());
			LocalDateTime endLimit = LocalDateTime.of(date, preference.getScheduleEndTime());

			while (!start.plusMinutes(task.getDurationMinutes()).isAfter(endLimit)) {
				LocalDateTime end = start.plusMinutes(task.getDurationMinutes());

				if (!conflictsWithTimetable(start, end, timetableSlots, preference.getMinimumGapMinutes())) {
					int score = calculateScore(task, preference, start, end);
					String reason = buildReason(task, preference, start, end, score);

					candidates.add(new CandidateSlot(start, end, score, reason));
				}
				start = start.plusMinutes(30);
			}
		}
		return candidates;
	}

	// Task의 preferredDate / preferredDayOfWeek / preferredStartDate~EndDate를 실제 날짜 목록으로 변환
	private List<LocalDate> resolveCandidateDates(Task task) {
		if (task.getPreferredDate() != null) {
			return List.of(task.getPreferredDate());
		}

		if (task.getPreferredStartDate() != null && task.getPreferredEndDate() != null) {
			List<LocalDate> dates = new ArrayList<>();
			LocalDate date = task.getPreferredStartDate();

			while (!date.isAfter(task.getPreferredEndDate())) {
				dates.add(date);
				date = date.plusDays(1);
			}
			return dates;
		}

		if (task.getPreferredDayOfWeek() != null) {
			LocalDate today = LocalDate.now();
			List<LocalDate> dates = new ArrayList<>();

			for (int i = 0; i < 14; i++) {
				LocalDate date = today.plusDays(i);
				if (date.getDayOfWeek() == task.getPreferredDayOfWeek()) {
					dates.add(date);
				}
			}
			return dates;
		}
		return List.of();
	}

	// 수업 시간과 겹치는지 검사
	private boolean conflictsWithTimetable(
		LocalDateTime start,
		LocalDateTime end,
		List<TimetableSlot> timetableSlots,
		int minimumGapMinutes
	) {
		return timetableSlots.stream()
			.filter(slot -> slot.getDayOfWeek() == start.getDayOfWeek())
			.anyMatch(slot -> {
				LocalDateTime blockedStartAt = LocalDateTime.of(start.toLocalDate(), slot.getStartTime())
					.minusMinutes(minimumGapMinutes);
				LocalDateTime blockedEndAt = LocalDateTime.of(start.toLocalDate(), slot.getEndTime())
					.plusMinutes(minimumGapMinutes);

				return start.isBefore(blockedEndAt) && end.isAfter(blockedStartAt);
			});
	}

	private int calculateScore(
		Task task,
		Preference preference,
		LocalDateTime start,
		LocalDateTime end
	) {
		int score = BASE_SCORE;

		PreferredTimeRange effectiveTimeRange = task.getPreferredTimeRange() != null
			? task.getPreferredTimeRange()
			: preference.getPreferredTimeRange();

		if (matchesPreferredTimeRange(effectiveTimeRange, start, end)) {
			score += PREFERRED_TIME_RANGE_SCORE;
		}

		if (task.getDeadline() != null) {
			score += calculateDeadlineScore(task, preference, start.toLocalDate());
		}

		score += calculatePriorityScore(task);
		score += calculateDensityScore(preference, start, end);

		return score;
	}

	private boolean matchesPreferredTimeRange(
		PreferredTimeRange preferredTimeRange,
		LocalDateTime start,
		LocalDateTime end
	) {
		if (preferredTimeRange == null || preferredTimeRange == PreferredTimeRange.ANYTIME) {
			return true;
		}

		return !start.toLocalTime().isBefore(preferredTimeRange.getStartTime())
			&& !end.toLocalTime().isAfter(preferredTimeRange.getEndTime());
	}

	private int calculateDeadlineScore(Task task, Preference preference, LocalDate candidateDate) {
		if (candidateDate.isAfter(task.getDeadline())) {
			return 0;
		}

		long daysUntilDeadline = java.time.temporal.ChronoUnit.DAYS.between(candidateDate, task.getDeadline());

		if (preference.getDeadlineTiming() == DeadlineTiming.ASAP) {
			return Math.max(0, DEADLINE_ASAP_MAX_SCORE - (int) daysUntilDeadline);
		}

		if (preference.getDeadlineTiming() == DeadlineTiming.NEAR_DEADLINE) {
			return Math.max(0, DEADLINE_NEAR_MAX_SCORE - Math.abs((int) daysUntilDeadline));
		}

		return Math.max(0, DEADLINE_BALANCED_MAX_SCORE - Math.abs((int) daysUntilDeadline - 2));
	}

	private int calculatePriorityScore(Task task) {
		if (task.getPriority() == TaskPriority.HIGH) {
			return PRIORITY_HIGH_SCORE;
		}

		if (task.getPriority() == TaskPriority.NORMAL) {
			return PRIORITY_NORMAL_SCORE;
		}

		return 0;
	}

	private int calculateDensityScore(Preference preference, LocalDateTime start, LocalDateTime end) {
		int durationMinutes = (int) java.time.Duration.between(start, end).toMinutes();

		if (preference.getScheduleDensity() == ScheduleDensity.COMPACT && durationMinutes <= 60) {
			return DENSITY_COMPACT_SCORE;
		}

		if (preference.getScheduleDensity() == ScheduleDensity.RELAXED && durationMinutes >= 90) {
			return DENSITY_RELAXED_SCORE;
		}

		return 0;
	}

	private String buildReason(
		Task task,
		Preference preference,
		LocalDateTime start,
		LocalDateTime end,
		int score
	) {
		List<String> reasons = new ArrayList<>();

		PreferredTimeRange effectiveTimeRange = task.getPreferredTimeRange() != null
			? task.getPreferredTimeRange()
			: preference.getPreferredTimeRange();

		if (matchesPreferredTimeRange(effectiveTimeRange, start, end)) {
			reasons.add("선호 시간대와 일치합니다");
		}

		if (task.getDeadline() != null && !start.toLocalDate().isAfter(task.getDeadline())) {
			reasons.add("마감일 이전에 배치할 수 있습니다");
		}

		if (preference.getMinimumGapMinutes() > 0) {
			reasons.add("일정 전후 최소 여유 시간을 반영했습니다");
		}

		reasons.add("점수 " + score + "점");

		return String.join(", ", reasons);
	}
}
