package me.timetablescheduler.domain.recommendation.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.TreeSet;
import me.timetablescheduler.domain.recommendation.type.RecommendationMatchType;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import lombok.RequiredArgsConstructor;

import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecommendationPolicy {
	private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");
	private final Clock clock;
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
		return generateCandidates(task, preference, timetableSlots, scheduledTasks, List.of());
	}

	public List<CandidateSlot> generateCandidates(
		Task task,
		Preference preference,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks,
		List<BusyBlock> calendarBusyBlocks
	) {
		return generateCandidates(task, preference, timetableSlots, scheduledTasks, calendarBusyBlocks,
			LocalDateTime.ofInstant(clock.instant(), SEOUL_ZONE_ID));
	}

	public List<CandidateSlot> generateCandidates(
		Task task, Preference preference, List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks, List<BusyBlock> calendarBusyBlocks, LocalDateTime now
	) {
		List<CandidateSlot> candidates = new ArrayList<>();
		List<BusyBlock> safeCalendar = calendarBusyBlocks == null ? List.of() : calendarBusyBlocks;
		for (LocalDate date : resolveCandidateDates(task, now.toLocalDate())) {
			// 충돌 검사는 여유 시간이 닿는 인접 날짜까지, 밀도 점수는 기존 당일 입력을 사용한다.
			List<BusyBlock> scoringBusy = buildBusyBlocks(date, timetableSlots, scheduledTasks, safeCalendar, task);
			for (LocalTime time : candidateStartTimes(task, preference, date)) {
				LocalDateTime start = date.atTime(time);
				LocalDateTime end = start.plusMinutes(task.getDurationMinutes());
				if (!meetsRequestConditions(task, preference, start, end, now)) {
					continue;
				}
				List<BusyBlock> collisionBusy = buildSelectionBusyBlocks(start, end,
					preference.getMinimumGapMinutes(), timetableSlots, scheduledTasks, safeCalendar, task);
				if (conflictsWithBusyBlocks(start, end, collisionBusy, preference.getMinimumGapMinutes())) {
					continue;
				}
				int score = calculateScore(task, preference, start, end, scoringBusy, now.toLocalDate());
				RecommendationMatchType type = classify(task, start, end);
				String detail = buildReason(task, preference, start, end, scoringBusy, score);
				String reason = describeRequest(task, type) + (detail.isBlank() ? "" : ", " + detail);
				candidates.add(new CandidateSlot(start, end, score, reason, type));
			}
		}
		return candidates;
	}

	private Set<LocalTime> candidateStartTimes(Task task, Preference preference, LocalDate date) {
		TreeSet<LocalTime> starts = new TreeSet<>();
		for (LocalDateTime start = date.atTime(preference.getScheduleStartTime());
			!start.plusMinutes(task.getDurationMinutes()).isAfter(date.atTime(preference.getScheduleEndTime()));
			start = start.plusMinutes(CANDIDATE_SEARCH_INTERVAL_MINUTES)) {
			starts.add(start.toLocalTime());
		}
		if (task.getPreferredStartTime() != null) starts.add(task.getPreferredStartTime());
		return starts;
	}

	/** 현재 추천 요청의 조회 결과만 사용한다. 기존 추천 이력의 사유를 재구성하지 않는다. */
	public String explainOriginalConditionExclusion(Task task, Preference preference,
		List<TimetableSlot> timetableSlots, List<Task> scheduledTasks,
		List<BusyBlock> calendarBusyBlocks, LocalDateTime now) {
		boolean hasRequest = task.getPreferredDate() != null || task.getPreferredDayOfWeek() != null
			|| task.getPreferredStartTime() != null || (task.getPreferredTimeRange() != null
				&& task.getPreferredTimeRange() != PreferredTimeRange.ANYTIME);
		if (!hasRequest) return "";
		// 조회 실패를 빈 일정이나 충돌로 설명하지 않는다.
		if (calendarBusyBlocks == null) return "";
		List<LocalDate> allowedDates = resolveCandidateDates(task, now.toLocalDate());
		List<LocalDate> requestedDates = task.getPreferredDate() != null
			? List.of(task.getPreferredDate()) : allowedDates.stream()
				.filter(date -> matchesRequestedDate(task, date)).toList();
		Set<String> reasons = new LinkedHashSet<>();
		for (LocalDate date : requestedDates) {
			for (LocalTime time : candidateStartTimes(task, preference, date)) {
				LocalDateTime start = date.atTime(time);
				LocalDateTime end = start.plusMinutes(task.getDurationMinutes());
				if (classify(task, start, end) != RecommendationMatchType.EXACT) continue;
				if (!start.isAfter(now)) {
					reasons.add("요청하신 시작 시각이 이미 지났어요.");
					continue;
				}
				if (!allowedDates.contains(date)) {
					reasons.add("요청하신 날짜가 현재 탐색 범위·허용 기간·마감일 조건에 맞지 않아요.");
					continue;
				}
				if (!isWithinAvailableTimeRange(preference, start, end)) {
					reasons.add("요청하신 시작 시각과 소요 시간이 설정된 가용 시간에 맞지 않아요.");
					continue;
				}
				Set<String> conflicts = new LinkedHashSet<>();
				int gap = preference.getMinimumGapMinutes();
				addConflictReason(conflicts, "학교 시간표", start, end, gap,
					buildSelectionBusyBlocks(start, end, gap, timetableSlots, List.of(), List.of(), task));
				addConflictReason(conflicts, "이미 확정된 Task", start, end, gap,
					buildSelectionBusyBlocks(start, end, gap, List.of(), scheduledTasks, List.of(), task));
				addConflictReason(conflicts, "Google Calendar 일정", start, end, gap,
					buildSelectionBusyBlocks(start, end, gap, List.of(), List.of(), calendarBusyBlocks, task));
				// 원조건 중 하나라도 유효하면 일부 후보의 충돌을 전체 미추천 사유로 오인하지 않는다.
				if (conflicts.isEmpty()) return "";
				reasons.addAll(conflicts);
			}
		}
		if (reasons.isEmpty()) {
			return "요청하신 조건에서 탐색 범위·허용 기간·소요 시간·가용 시간을 모두 만족하는 후보를 찾지 못했어요.";
		}
		return "추천 생성 시 원조건 후보에서 확인한 제외 사유: " + String.join(" ", reasons);
	}

	private void addConflictReason(Set<String> reasons, String source, LocalDateTime start,
		LocalDateTime end, int gap, List<BusyBlock> blocks) {
		if (conflictsWithBusyBlocks(start, end, blocks, 0)) {
			reasons.add(source + (source.equals("Google Calendar 일정") ? "과" : "와") + " 겹쳐요.");
		} else if (gap > 0 && conflictsWithBusyBlocks(start, end, blocks, gap)) {
			reasons.add(source + " 전후의 최소 여유 시간이 부족해요.");
		}
	}

	/**
	 * 추천 생성 이후 변경된 현재 조건으로 후보를 확정해도 되는지 판단한다.
	 * Calendar 검증 결과가 없으면 확정을 차단한다.
	 */
	public boolean isSelectable(
		Task task,
		Preference preference,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks,
		List<BusyBlock> calendarBusyBlocks,
		LocalDateTime start,
		LocalDateTime end,
		LocalDateTime now
	) {
		if (task == null || preference == null || start == null || end == null || now == null
			|| calendarBusyBlocks == null) {
			return false;
		}

		if (!meetsRequestConditions(task, preference, start, end, now)) {
			return false;
		}

		List<BusyBlock> busyBlocks = buildSelectionBusyBlocks(
			start,
			end,
			preference.getMinimumGapMinutes(),
			timetableSlots == null ? List.of() : timetableSlots,
			scheduledTasks == null ? List.of() : scheduledTasks,
			calendarBusyBlocks,
			task
		);

		return !conflictsWithBusyBlocks(start, end, busyBlocks, preference.getMinimumGapMinutes());
	}

	/** 생성·선택·Calendar 조회에 함께 사용하는 실제 탐색 날짜다. */
	public List<LocalDate> resolveCandidateDates(Task task, LocalDate today) {
		boolean dateHard = task.getDateRequired() == null || task.getDateRequired();
		LocalDate requested = task.getPreferredDate();
		if (requested != null && dateHard && requested.isBefore(today)) {
			return List.of();
		}
		LocalDate first;
		LocalDate last;
		if (requested != null && dateHard) {
			first = requested;
			last = requested;
		} else if (task.getPreferredStartDate() != null && task.getPreferredEndDate() != null) {
			// 명시한 허용 기간은 기존처럼 보존하며 기본 14일로 재해석하지 않는다.
			first = task.getPreferredStartDate();
			last = task.getPreferredEndDate();
			if (requested != null) {
				first = first.isAfter(requested) ? first : requested;
				LocalDate limit = requested.plusDays(13);
				last = last.isBefore(limit) ? last : limit;
			}
		} else {
			first = requested != null ? requested : today;
			last = first.plusDays(13);
		}
		if (first.isBefore(today)) first = today;
		if (task.getPreferredStartDate() != null && first.isBefore(task.getPreferredStartDate())) first = task.getPreferredStartDate();
		if (task.getPreferredEndDate() != null && last.isAfter(task.getPreferredEndDate())) last = task.getPreferredEndDate();
		if (task.getDeadline() != null && last.isAfter(task.getDeadline())) last = task.getDeadline();
		List<LocalDate> dates = new ArrayList<>();
		for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
			if (!dateHard || task.getPreferredDayOfWeek() == null || date.getDayOfWeek() == task.getPreferredDayOfWeek()) dates.add(date);
		}
		return dates;
	}

	private boolean meetsRequestConditions(Task task, Preference preference, LocalDateTime start,
		LocalDateTime end, LocalDateTime now) {
		return start.isAfter(now) && end.isAfter(start)
			&& Duration.between(start, end).equals(Duration.ofMinutes(task.getDurationMinutes()))
			&& resolveCandidateDates(task, now.toLocalDate()).contains(start.toLocalDate())
			&& !isAfterDeadline(task, start.toLocalDate())
			&& isWithinAvailableTimeRange(preference, start, end)
			&& (!Boolean.TRUE.equals(task.getTimeRequired()) || matchesRequestedTime(task, start, end));
	}

	private boolean matchesRequestedDate(Task task, LocalDate date) {
		if (task.getPreferredDate() != null) return date.equals(task.getPreferredDate());
		return task.getPreferredDayOfWeek() == null || date.getDayOfWeek() == task.getPreferredDayOfWeek();
	}

	private boolean matchesRequestedTime(Task task, LocalDateTime start, LocalDateTime end) {
		if (task.getPreferredStartTime() != null) return start.toLocalTime().equals(task.getPreferredStartTime());
		if (task.getPreferredTimeRange() == null || task.getPreferredTimeRange() == PreferredTimeRange.ANYTIME) return true;
		return matchesPreferredTimeRange(task.getPreferredTimeRange(), start, end);
	}

	private RecommendationMatchType classify(Task task, LocalDateTime start, LocalDateTime end) {
		boolean hasDate = task.getPreferredDate() != null || task.getPreferredDayOfWeek() != null;
		boolean hasTime = task.getPreferredStartTime() != null || (task.getPreferredTimeRange() != null
			&& task.getPreferredTimeRange() != PreferredTimeRange.ANYTIME);
		if (!hasDate && !hasTime) return RecommendationMatchType.GENERAL;
		boolean dateMatches = matchesRequestedDate(task, start.toLocalDate());
		boolean timeMatches = matchesRequestedTime(task, start, end);
		if (dateMatches && timeMatches) return RecommendationMatchType.EXACT;
		if (dateMatches) return RecommendationMatchType.TIME_CHANGED;
		if (timeMatches) return RecommendationMatchType.DATE_CHANGED;
		return RecommendationMatchType.DATE_AND_TIME_CHANGED;
	}

	private String describeRequest(Task task, RecommendationMatchType type) {
		return switch (type) {
			case EXACT -> "요청한 날짜·요일·시작 시각·시간대 중 지정한 조건에 일치합니다";
			case TIME_CHANGED -> "요청 날짜 조건은 유지하고 시작 시각 또는 시간대를 변경한 대안입니다";
			case DATE_CHANGED -> "요청 시간 조건은 유지하고 날짜를 변경한 대안입니다";
			case DATE_AND_TIME_CHANGED -> "요청 날짜와 시간 조건을 모두 변경한 대안입니다";
			case GENERAL -> "요청한 소요 시간과 허용 조건 안에서 찾은 후보입니다";
		};
	}

	private boolean isWithinAvailableTimeRange(
		Preference preference,
		LocalDateTime start,
		LocalDateTime end
	) {
		return start.toLocalDate().equals(end.toLocalDate())
			&& !start.toLocalTime().isBefore(preference.getScheduleStartTime())
			&& !end.toLocalTime().isAfter(preference.getScheduleEndTime());
	}

	private List<BusyBlock> buildSelectionBusyBlocks(
		LocalDateTime start,
		LocalDateTime end,
		int minimumGapMinutes,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks,
		List<BusyBlock> calendarBusyBlocks,
		Task currentTask
	) {
		List<BusyBlock> busyBlocks = new ArrayList<>();
		LocalDate relevantStartDate = start.minusMinutes(minimumGapMinutes).toLocalDate();
		LocalDate relevantEndDate = end.plusMinutes(minimumGapMinutes).toLocalDate();
		LocalDate timetableEndDate = relevantEndDate.isBefore(relevantStartDate.plusDays(7))
			? relevantEndDate
			: relevantStartDate.plusDays(7);

		for (LocalDate date = relevantStartDate; !date.isAfter(timetableEndDate); date = date.plusDays(1)) {
			LocalDate timetableDate = date;
			timetableSlots.stream()
				.filter(slot -> slot.getDayOfWeek() == timetableDate.getDayOfWeek())
				.map(slot -> new BusyBlock(
					LocalDateTime.of(timetableDate, slot.getStartTime()),
					LocalDateTime.of(timetableDate, slot.getEndTime())
				))
				.forEach(busyBlocks::add);
		}

		scheduledTasks.stream()
			.filter(task -> !isSameTask(task, currentTask))
			.filter(task -> task.getScheduledStartAt() != null)
			.filter(task -> task.getScheduledEndAt() != null)
			.map(task -> new BusyBlock(task.getScheduledStartAt(), task.getScheduledEndAt()))
			.forEach(busyBlocks::add);

		busyBlocks.addAll(calendarBusyBlocks);
		return busyBlocks;
	}

	private List<BusyBlock> buildBusyBlocks(
		LocalDate date,
		List<TimetableSlot> timetableSlots,
		List<Task> scheduledTasks,
		List<BusyBlock> calendarBusyBlocks,
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

		calendarBusyBlocks.stream()
			.filter(busyBlock -> overlapsDate(busyBlock, date))
			.forEach(busyBlocks::add);

		return busyBlocks;
	}

	private boolean overlapsDate(BusyBlock busyBlock, LocalDate date) {
		return !busyBlock.startAt().toLocalDate().isAfter(date)
			&& !busyBlock.endAt().toLocalDate().isBefore(date);
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
			score += calculateDeadlineScore(task, preference, start.toLocalDate(), baseDate);
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
	private int calculateDeadlineScore(Task task, Preference preference, LocalDate candidateDate, LocalDate baseDate) {
		if (candidateDate.isAfter(task.getDeadline())) {
			return 0;
		}

		long daysUntilDeadline = ChronoUnit.DAYS.between(candidateDate, task.getDeadline());

		if (preference.getDeadlineTiming() == DeadlineTiming.ASAP) {
			long daysFromBase = ChronoUnit.DAYS.between(baseDate, candidateDate);
			return (int) Math.max(0L, DEADLINE_ASAP_MAX_SCORE - daysFromBase);
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


		return String.join(", ", reasons);
	}
}
