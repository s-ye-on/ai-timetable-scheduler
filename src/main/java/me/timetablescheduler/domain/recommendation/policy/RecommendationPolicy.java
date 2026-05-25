package me.timetablescheduler.domain.recommendation.policy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import org.springframework.stereotype.Component;

@Component
public class RecommendationPolicy {
	private static final int BASE_SCORE = 50;
	private static final int PREFERRED_TIME_RANGE_SCORE = 30;
	private static final int CANDIDATE_SEARCH_INTERVAL_MINUTES = 30;
	private static final int DENSITY_COMPACT_SCORE = 10;
	private static final int DENSITY_BALANCED_SCORE = 10;
	private static final int DENSITY_RELAXED_SCORE = 10;
	private static final int DENSITY_COMPACT_MAX_GAP_MINUTES = 30;
	private static final int DENSITY_BALANCED_MIN_GAP_MINUTES = 30;
	private static final int DENSITY_BALANCED_MAX_GAP_MINUTES = 90;
	private static final int DENSITY_RELAXED_MIN_GAP_MINUTES = 90;
	private static final int DEADLINE_ASAP_MAX_SCORE = 20;
	private static final int DEADLINE_BALANCED_MAX_SCORE = 15;
	private static final int DEADLINE_NEAR_MAX_SCORE = 20;
	private static final int NEAR_DEADLINE_TARGET_DAYS_BEFORE = 1;
	private static final int DEADLINE_SCORE_DAILY_PENALTY = 5;

	public List<CandidateSlot> generateCandidates(
		Task task,
		Preference preference,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks
	) {
		LocalDate baseDate = LocalDate.now();
		List<LocalDate> candidateDates = resolveCandidateDates(task, baseDate);
		List<CandidateSlot> candidates = new ArrayList<>();

		for (LocalDate date : candidateDates) {
			if (isAfterDeadline(task, date)) {
				continue;
			}

			List<BusyBlock> busyBlocks = buildBusyBlocks(
				date,
				timetableSlots,
				scheduledTasks,
				task
			);

			LocalDateTime start = LocalDateTime.of(date, preference.getScheduleStartTime());
			LocalDateTime dayEndLimit = LocalDateTime.of(date, preference.getScheduleEndTime());

			while (!start.plusMinutes(task.getDurationMinutes()).isAfter(dayEndLimit)) {
				LocalDateTime end = start.plusMinutes(task.getDurationMinutes());

				if (!conflictsWithBusyBlocks(
					start,
					end,
					busyBlocks,
					preference.getMinimumGapMinutes()
				)) {
					int score = calculateScore(
						task,
						preference,
						start,
						end,
						busyBlocks,
						baseDate
					);

					String reason = buildReason(task, preference, start, end, busyBlocks, score);

					candidates.add(new CandidateSlot(start, end, score, reason));
				}

				start = start.plusMinutes(CANDIDATE_SEARCH_INTERVAL_MINUTES);
			}
		}
		return candidates;
	}

	// Task의 preferredDate / preferredDayOfWeek / preferredStartDate~EndDate를 실제 날짜 목록으로 변환
	private List<LocalDate> resolveCandidateDates(Task task, LocalDate baseDate) {
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
			List<LocalDate> dates = new ArrayList<>();

			for (int i = 0; i < 14; i++) {
				LocalDate date = baseDate.plusDays(i);
				if (date.getDayOfWeek() == task.getPreferredDayOfWeek()) {
					dates.add(date);
				}
			}
			return dates;
		}
		return List.of();
	}

	private List<BusyBlock> buildBusyBlocks(
		LocalDate date,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks,
		Task currentTask
	) {
		List<BusyBlock> busyBlocks = new ArrayList<>();

		timetableSlots.stream()
			.filter(slot -> slot.getDayOfWeek() == date.getDayOfWeek())
			.map(slot -> new BusyBlock(
				LocalDateTime.of(date, slot.getStartTime()),
				LocalDateTime.of(date, slot.getEndTime())
			))
			.forEach(busyBlocks::add);

		scheduledTasks.stream()
			.filter(task -> !isSameTask(task, currentTask))
			.filter(task -> task.getScheduledStartAt() != null)
			.filter(task -> task.getScheduledEndAt() != null)
			.filter(task -> task.getScheduledStartAt().toLocalDate().equals(date))
			.map(task -> new BusyBlock(
				task.getScheduledStartAt(),
				task.getScheduledEndAt()
			))
			.forEach(busyBlocks::add);

		return busyBlocks;
	}

	private boolean conflictsWithBusyBlocks(
		LocalDateTime start,
		LocalDateTime end,
		List<BusyBlock> busyBlocks,
		int minimumGapMinutes
	) {
		return busyBlocks.stream()
			.anyMatch(busyBlock -> {
				LocalDateTime blockedStartAt = busyBlock.startAt().minusMinutes(minimumGapMinutes);
				LocalDateTime blockedEndAt = busyBlock.endAt().plusMinutes(minimumGapMinutes);

				return start.isBefore(blockedEndAt) && end.isAfter(blockedStartAt);
			});
	}

	private boolean isSameTask(Task task, Task currentTask) {
		if (task.getId() != null && currentTask.getId() != null) {
			return task.getId().equals(currentTask.getId());
		}

		return task == currentTask;
	}

	private int calculateScore(
		Task task,
		Preference preference,
		LocalDateTime start,
		LocalDateTime end,
		List<BusyBlock> busyBlocks,
		LocalDate baseDate
	) {
		int score = BASE_SCORE;

		PreferredTimeRange effectiveTimeRange = task.getPreferredTimeRange() != null
			? task.getPreferredTimeRange()
			: preference.getPreferredTimeRange();

		score += calculatePreferredTimeRangeScore(effectiveTimeRange, start, end);

		if (task.getDeadline() != null) {
			score += calculateDeadlineScore(task, preference, start.toLocalDate());
		}

		score += calculatePriorityScore(task, start.toLocalDate(), baseDate);
		score += calculateDensityScore(preference, start, end, busyBlocks);

		return score;
	}

	private int calculatePreferredTimeRangeScore(
		PreferredTimeRange preferredTimeRange,
		LocalDateTime start,
		LocalDateTime end
	) {
		if (preferredTimeRange == null || preferredTimeRange == PreferredTimeRange.ANYTIME) {
			return 0;
		}

		if (matchesPreferredTimeRange(preferredTimeRange, start, end)) {
			return PREFERRED_TIME_RANGE_SCORE;
		}

		return 0;
	}

	private boolean matchesPreferredTimeRange(
		PreferredTimeRange preferredTimeRange,
		LocalDateTime start,
		LocalDateTime end
	) {
		if (preferredTimeRange == null || preferredTimeRange == PreferredTimeRange.ANYTIME) {
			return false;
		}

		return !start.toLocalTime().isBefore(preferredTimeRange.getStartTime())
			&& !end.toLocalTime().isAfter(preferredTimeRange.getEndTime());
	}

	/// 마감 당일날 배치는 위험함 마감 하루전 또는 이틀전이 안전할 것이라 생각됨
	private int calculateDeadlineScore(Task task, Preference preference, LocalDate candidateDate) {
		if (candidateDate.isAfter(task.getDeadline())) {
			return 0;
		}

		long daysUntilDeadline = ChronoUnit.DAYS.between(candidateDate, task.getDeadline());

		if (preference.getDeadlineTiming() == DeadlineTiming.ASAP) {
			return Math.max(0, DEADLINE_ASAP_MAX_SCORE - (int) daysUntilDeadline);
		}

		if (preference.getDeadlineTiming() == DeadlineTiming.NEAR_DEADLINE) {
			return Math.max(
				0,
				DEADLINE_NEAR_MAX_SCORE
					- Math.abs((int) daysUntilDeadline - NEAR_DEADLINE_TARGET_DAYS_BEFORE)
					* DEADLINE_SCORE_DAILY_PENALTY
			);
		}

		return Math.max(0, DEADLINE_BALANCED_MAX_SCORE - Math.abs((int) daysUntilDeadline - 2));
	}

	private boolean isAfterDeadline(Task task, LocalDate candidateDate) {
		return task.getDeadline() != null && candidateDate.isAfter(task.getDeadline());
	}

	private int calculatePriorityScore(Task task, LocalDate candidateDate, LocalDate baseDate) {
		long daysFromBase = ChronoUnit.DAYS.between(baseDate, candidateDate);

		return task.getPriority().calculateDateScore(daysFromBase);
	}

	private int calculateDensityScore(
		Preference preference,
		LocalDateTime start,
		LocalDateTime end,
		List<BusyBlock> busyBlocks
	) {
		Long nearestGapMinutes = findNearestGapMinutes(start, end, busyBlocks);

		/// todo : 추후 ScheduleDensity 점수 정책을 enum으로 이동할지 고민
		/// 나중에 더 객체지향적으로 정리하려면 점수 계산 책임을 넘길 수 있음
		if (preference.getScheduleDensity() == ScheduleDensity.COMPACT
			&& nearestGapMinutes != null
			&& nearestGapMinutes <= DENSITY_COMPACT_MAX_GAP_MINUTES) {
			return DENSITY_COMPACT_SCORE;
		}

		if (preference.getScheduleDensity() == ScheduleDensity.BALANCED
			&& nearestGapMinutes != null
			&& nearestGapMinutes >= DENSITY_BALANCED_MIN_GAP_MINUTES
			&& nearestGapMinutes <= DENSITY_BALANCED_MAX_GAP_MINUTES) {
			return DENSITY_BALANCED_SCORE;
		}

		if (preference.getScheduleDensity() == ScheduleDensity.RELAXED
			&& (nearestGapMinutes == null || nearestGapMinutes >= DENSITY_RELAXED_MIN_GAP_MINUTES)) {
			return DENSITY_RELAXED_SCORE;
		}

		return 0;
	}

	private Long findNearestGapMinutes(LocalDateTime start, LocalDateTime end, List<BusyBlock> busyBlocks) {
		var nearestGapMinutes = busyBlocks.stream()
			.mapToLong(busyBlock -> calculateGapMinutes(start, end, busyBlock))
			.min();

		if (nearestGapMinutes.isEmpty()) {
			return null;
		}

		return nearestGapMinutes.getAsLong();
	}

	private long calculateGapMinutes(LocalDateTime start, LocalDateTime end, BusyBlock busyBlock) {
		if (!end.isAfter(busyBlock.startAt())) {
			return ChronoUnit.MINUTES.between(end, busyBlock.startAt());
		}

		if (!start.isBefore(busyBlock.endAt())) {
			return ChronoUnit.MINUTES.between(busyBlock.endAt(), start);
		}

		return 0;
	}

	private String buildReason(
		Task task,
		Preference preference,
		LocalDateTime start,
		LocalDateTime end,
		List<BusyBlock> busyBlocks,
		int score
	) {
		List<String> reasons = new ArrayList<>();

		PreferredTimeRange effectiveTimeRange = task.getPreferredTimeRange() != null
			? task.getPreferredTimeRange()
			: preference.getPreferredTimeRange();

		if (effectiveTimeRange != null
			&& effectiveTimeRange != PreferredTimeRange.ANYTIME
			&& matchesPreferredTimeRange(effectiveTimeRange, start, end)) {
			reasons.add("선호 시간대와 일치합니다");
		}

		if (task.getDeadline() != null && !start.toLocalDate().isAfter(task.getDeadline())) {
			reasons.add("마감일 이전에 배치할 수 있습니다");
		}

		if (preference.getMinimumGapMinutes() > 0) {
			reasons.add("일정 전후 최소 여유 시간을 반영했습니다");
		}

		Long nearestGapMinutes = findNearestGapMinutes(start, end, busyBlocks);
		if (preference.getScheduleDensity() == ScheduleDensity.COMPACT
			&& nearestGapMinutes != null
			&& nearestGapMinutes <= DENSITY_COMPACT_MAX_GAP_MINUTES) {
			reasons.add("기존 일정과 가까운 시간대를 우선 반영했습니다");
		}

		if (preference.getScheduleDensity() == ScheduleDensity.BALANCED
			&& nearestGapMinutes != null
			&& nearestGapMinutes >= DENSITY_BALANCED_MIN_GAP_MINUTES
			&& nearestGapMinutes <= DENSITY_BALANCED_MAX_GAP_MINUTES) {
			reasons.add("기존 일정과 적당한 간격이 있는 시간대를 반영했습니다");
		}

		if (preference.getScheduleDensity() == ScheduleDensity.RELAXED
			&& (nearestGapMinutes == null || nearestGapMinutes >= DENSITY_RELAXED_MIN_GAP_MINUTES)) {
			reasons.add("기존 일정과 충분히 떨어진 시간대를 우선 반영했습니다");
		}

		reasons.add("점수 " + score + "점");

		return String.join(", ", reasons);
	}
}
