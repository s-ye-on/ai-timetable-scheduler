package me.timetablescheduler.domain.recommendation.service;

import me.timetablescheduler.domain.recommendation.type.RecommendationMatchType;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.recommendation.policy.CandidateSlot;
import me.timetablescheduler.domain.recommendation.policy.RecommendationPolicy;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.RecommendationException;
import me.timetablescheduler.global.exception.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {
	private static final String RECOMMENDATION_CREATED_MESSAGE = "추천 가능한 시간을 찾았습니다.";
	private static final String NO_RECOMMENDATION_MESSAGE = "추천 가능한 시간이 없습니다. 시간표 또는 선호 시간 범위를 조정해 주세요.";
	private static final int MAX_RECOMMENDATION_COUNT = 3;
	private static final ZoneId SEOUL_ZONE_ID = ZoneId.of("Asia/Seoul");

	private final RecommendationRepository recommendationRepository;
	private final TaskRepository taskRepository;
	private final PreferenceRepository preferenceRepository;
	private final TimetableSlotRepository timetableSlotRepository;
	private final UserReader userReader;
	private final RecommendationPolicy recommendationPolicy;
	private final CalendarService calendarService;
	private final Clock clock;
	private final PlatformTransactionManager transactionManager;
	private final EntityManager entityManager;

	@Transactional
	public RecommendationResponse.Generate recommend(Long userId, Long taskId) {
		User user = userReader.readForUpdate(userId);
		Task task = findTask(taskId, userId);
		task.validateRecommendable();
		Preference preference = findPreference(userId);
		List<TimetableSlot> timetableSlots = timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(userId);
		List<Task> scheduledTasks = taskRepository.findAllByUserIdAndStatus(userId, TaskStatus.SCHEDULED);
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SEOUL_ZONE_ID);
		List<BusyBlock> calendarBusyBlocks = findCalendarBusyBlocks(userId, task, now.toLocalDate(), preference);

		expireExistingProposedRecommendations(taskId, userId);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			timetableSlots,
			scheduledTasks,
			calendarBusyBlocks,
			now
		);

		LocalDateTime selectionTime = LocalDateTime.ofInstant(clock.instant(), SEOUL_ZONE_ID);
		List<CandidateSlot> topCandidates = selectDiverseCandidates(candidates.stream()
			.filter(candidate -> candidate.startAt().isAfter(selectionTime))
			.sorted(Comparator.comparingInt(CandidateSlot::score).reversed())
			.toList());

		if (topCandidates.isEmpty()) {
			return new RecommendationResponse.Generate(NO_RECOMMENDATION_MESSAGE, List.of());
		}

		List<Recommendation> recommendations = new ArrayList<>();

		for (int i = 0; i < topCandidates.size(); i++) {
			CandidateSlot candidate = topCandidates.get(i);

			recommendations.add(Recommendation.create(
				user,
				task,
				candidate.startAt(),
				candidate.endAt(),
				i + 1,
				candidate.score(),
				candidate.reason(),
				candidate.matchType()
			));
		}

		List<RecommendationResponse.Read> recommendationResponses = recommendationRepository.saveAll(recommendations)
			.stream()
			.map(this::toReadResponse)
			.toList();

		return new RecommendationResponse.Generate(RECOMMENDATION_CREATED_MESSAGE, recommendationResponses);
	}

	public List<RecommendationResponse.Read> readProposedByTask(Long userId, Long taskId) {
		findTask(taskId, userId);

		return recommendationRepository.findAllByTaskIdAndUserIdAndStatusOrderByRankAsc(
				taskId,
				userId,
				RecommendationStatus.PROPOSED
			)
			.stream()
			.map(this::toReadResponse)
			.toList();
	}

	public List<RecommendationResponse.Read> readByTask(Long userId, Long taskId, boolean includeHistory) {
		if (!includeHistory) {
			return readProposedByTask(userId, taskId);
		}
		findTask(taskId, userId);
		return recommendationRepository.findAllByTaskIdAndUserId(taskId, userId).stream()
			.sorted(Comparator.comparing(Recommendation::getId).reversed())
			.map(this::toReadResponse)
			.toList();
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public RecommendationResponse.Read select(Long userId, Long recommendationId) {
		var transaction = new TransactionTemplate(transactionManager);
		// 외부 등록 전에 예약 ID를 커밋한다. 이후 DB 저장이 실패해도 같은 이벤트를 조회할 수 있다.
		transaction.executeWithoutResult(status -> reserveSelection(userId, recommendationId));
		return transaction.execute(status -> synchronizeSelection(userId, recommendationId));
	}

	private void reserveSelection(Long userId, Long recommendationId) {
		// OSIV가 같은 PersistenceContext를 재사용해도 이전 요청 상태를 신뢰하지 않는다.
		entityManager.clear();
		userReader.readForUpdate(userId);
		Recommendation recommendation = findRecommendation(recommendationId, userId);
		Task task = recommendation.getTask();

		validateCalendarConnected(userId);
		if (recommendation.getStatus() == RecommendationStatus.SYNCED) {
			return;
		}
		if (recommendation.getStatus() == RecommendationStatus.SELECTED
			|| recommendation.getStatus() == RecommendationStatus.SYNC_FAILED) {
			recommendation.validateCalendarSync();
			return;
		}
		recommendation.validateSelectable();
		task.validateRecommendable();
		validateCurrentConditions(userId, recommendation, task);
		recommendation.reserveCalendarSync(UUID.randomUUID().toString().replace("-", ""));
		task.schedule(recommendation.getRecommendedStartAt(), recommendation.getRecommendedEndAt());
		rejectOtherProposedRecommendations(task.getId(), userId, recommendation);
	}

	private RecommendationResponse.Read synchronizeSelection(Long userId, Long recommendationId) {
		entityManager.clear();
		userReader.readForUpdate(userId);
		Recommendation recommendation = findRecommendation(recommendationId, userId);
		if (recommendation.getStatus() != RecommendationStatus.SYNCED) {
			recommendation.resumeCalendarSync();
			syncCalendarEvent(userId, recommendation, recommendation.getTask());
		}
		return toReadResponse(recommendation);
	}

	private void validateCurrentConditions(Long userId, Recommendation recommendation, Task task) {
		Preference preference = findPreference(userId);
		List<TimetableSlot> timetableSlots = timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(userId);
		List<Task> scheduledTasks = taskRepository.findAllByUserIdAndStatus(userId, TaskStatus.SCHEDULED);
		LocalDateTime start = recommendation.getRecommendedStartAt();
		LocalDateTime end = recommendation.getRecommendedEndAt();
		int gap = preference.getMinimumGapMinutes();
		List<BusyBlock> busyBlocks = calendarService.getCalendarBusyBlocks(userId, start.minusMinutes(gap), end.plusMinutes(gap));
		if (busyBlocks == null) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
		}

		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), SEOUL_ZONE_ID);
		if (!recommendationPolicy.isSelectable(task, preference, timetableSlots, scheduledTasks, busyBlocks, start, end, now)) {
			throw new RecommendationException(ExceptionCode.RECOMMENDATION_NO_LONGER_AVAILABLE);
		}
	}

	private void validateCalendarConnected(Long userId) {
		if (!calendarService.isConnected(userId)) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED);
		}
	}

	// 새 추천 요청 시 기존 PROPOSED 후보는 최신 추천 결과가 아니므로 만료.
	private void expireExistingProposedRecommendations(Long taskId, Long userId) {
		recommendationRepository.findAllByTaskIdAndUserIdAndStatus(taskId, userId, RecommendationStatus.PROPOSED)
			.forEach(Recommendation::expire);
	}

	private void rejectOtherProposedRecommendations(
		Long taskId,
		Long userId,
		Recommendation selectedRecommendation
	) {
		recommendationRepository.findAllByTaskIdAndUserIdAndStatus(taskId, userId, RecommendationStatus.PROPOSED)
			.stream()
			.filter(recommendation -> !isSameRecommendation(recommendation, selectedRecommendation))
			.forEach(Recommendation::reject);
	}

	private boolean isSameRecommendation(Recommendation recommendation, Recommendation selectedRecommendation) {
		if (recommendation.getId() != null && selectedRecommendation.getId() != null) {
			return recommendation.getId().equals(selectedRecommendation.getId());
		}

		return recommendation == selectedRecommendation;
	}

	private void syncCalendarEvent(Long userId, Recommendation recommendation, Task task) {
		try {
			if (hasMatchingEvent(userId, recommendation, task)) {
				recommendation.sync(recommendation.getGoogleEventId());
				return;
			}
			// 기존 외부 이벤트의 복구와 달리 신규 등록에는 최신 충돌 검증이 필요하다.
			validateCurrentConditions(userId, recommendation, task);
			try {
				String eventId = calendarService.createEvent(userId, recommendation.getGoogleEventId(),
					task.getTitle(), task.getDescription(), recommendation.getRecommendedStartAt(), recommendation.getRecommendedEndAt());
				recommendation.sync(eventId);
			} catch (CalendarException createFailure) {
				// 응답 유실·409도 같은 ID의 조회로만 복구한다. 여기서 재생성하지 않는다.
				if (hasMatchingEvent(userId, recommendation, task)) {
					recommendation.sync(recommendation.getGoogleEventId());
				} else {
					recommendation.failSync();
				}
			}
		} catch (CalendarException exception) {
			recommendation.failSync();
		}
	}

	private boolean hasMatchingEvent(Long userId, Recommendation recommendation, Task task) {
		return calendarService.hasMatchingEvent(userId, recommendation.getGoogleEventId(), task.getTitle(),
			task.getDescription(), recommendation.getRecommendedStartAt(), recommendation.getRecommendedEndAt());
	}

	private List<BusyBlock> findCalendarBusyBlocks(Long userId, Task task, LocalDate baseDate, Preference preference) {
		List<LocalDate> dates = recommendationPolicy.resolveCandidateDates(task, baseDate);
		if (dates.isEmpty()) return List.of();
		int gap = preference.getMinimumGapMinutes();
		List<BusyBlock> blocks = calendarService.getCalendarBusyBlocksOrEmpty(userId,
			dates.getFirst().atStartOfDay().minusMinutes(gap),
			dates.getLast().plusDays(1).atStartOfDay().plusMinutes(gap));
		if (blocks == null) throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED);
		return blocks;
	}

	/** 원점수순 후보에서 원조건 대표와 변경 내용이 다른 대안을 고른다. */
	private List<CandidateSlot> selectDiverseCandidates(List<CandidateSlot> sorted) {
		// 분류 정보가 없는 기존 CandidateSlot 호출은 종전 점수순을 유지한다.
		if (sorted.stream().allMatch(candidate -> candidate.matchType() == null)) {
			return sorted.stream().limit(MAX_RECOMMENDATION_COUNT).toList();
		}
		List<CandidateSlot> selected = new ArrayList<>();
		sorted.stream().filter(candidate -> candidate.matchType() == RecommendationMatchType.EXACT)
			.findFirst().ifPresent(selected::add);
		// 같은 요일의 다음 주 후보를 '같은 날짜' 대안으로 오인하지 않는다.
		List<CandidateSlot> sameDate = selected.isEmpty() ? sorted : sorted.stream()
			.filter(candidate -> candidate.startAt().toLocalDate().equals(selected.getFirst().startAt().toLocalDate())).toList();
		if (!selected.isEmpty() && !addRepresentative(sameDate, selected, RecommendationMatchType.TIME_CHANGED)) {
			if (!addRepresentative(sorted, selected, RecommendationMatchType.DATE_CHANGED)) {
				addRepresentative(sorted, selected, RecommendationMatchType.DATE_AND_TIME_CHANGED);
			}
		}
		while (selected.size() < MAX_RECOMMENDATION_COUNT) {
			// 남은 자리는 고정 유형 할당 없이 새 날짜와 실제 시간 차이를 고려한다.
			var next = sorted.stream().filter(candidate -> isDistinctChoice(candidate, selected))
				.min(Comparator.<CandidateSlot>comparingInt(candidate -> selected.stream().anyMatch(existing ->
					existing.startAt().toLocalDate().equals(candidate.startAt().toLocalDate())) ? 1 : 0)
					.thenComparing(Comparator.comparingInt(CandidateSlot::score).reversed())
					.thenComparingInt(candidate -> candidate.matchType() == RecommendationMatchType.DATE_AND_TIME_CHANGED ? 1 : 0)
					.thenComparing(candidate -> candidate.startAt().toLocalDate())
					.thenComparingLong(candidate -> -selected.stream().mapToLong(existing ->
						Math.abs(java.time.Duration.between(existing.startAt(), candidate.startAt()).toMinutes())).min().orElse(0)));
			if (next.isEmpty()) break;
			selected.add(next.get());
		}
		// 최종 표시 순위와 동점 순서는 원래의 점수 안정 정렬로 복원한다.
		return sorted.stream().filter(selected::contains).toList();
	}

	private boolean addRepresentative(List<CandidateSlot> sorted, List<CandidateSlot> selected,
		RecommendationMatchType type) {
		// 같은 원점수의 대안에서는 원조건에서 가까운 시각을 대표로 고른다. 최종 정렬은 바꾸지 않는다.
		return sorted.stream().filter(candidate -> candidate.matchType() == type)
			.filter(candidate -> isDistinctChoice(candidate, selected))
			.min(Comparator.comparingInt(CandidateSlot::score).reversed().thenComparingLong(candidate ->
				selected.stream().mapToLong(existing -> Math.abs(java.time.Duration.between(
					existing.startAt(), candidate.startAt()).toMinutes())).min().orElse(0)))
			.map(candidate -> {
				selected.add(candidate);
				return true;
			}).orElse(false);
	}

	private boolean isDistinctChoice(CandidateSlot candidate, List<CandidateSlot> selected) {
		return selected.stream().noneMatch(existing -> candidate.equals(existing)
			|| (candidate.startAt().isBefore(existing.endAt()) && candidate.endAt().isAfter(existing.startAt())));
	}

	private Task findTask(Long taskId, Long userId) {
		return taskRepository.findByIdAndUserId(taskId, userId)
			.orElseThrow(() -> new TaskException(ExceptionCode.NOT_FOUND_TASK));
	}

	private Recommendation findRecommendation(Long recommendationId, Long userId) {
		return recommendationRepository.findByIdAndUserId(recommendationId, userId)
			.orElseThrow(() -> new RecommendationException(ExceptionCode.NOT_FOUND_RECOMMENDATION));
	}

	/// todo : preference가 없을 경우 같은 user를 두 번 조회하게 됨
	/// 이미 recommend()에서 User를 읽었으므로 매개 변수로 바로 user를 넣어줘도 됨
	/// todo : PreferenceService에도 같은 코드가 있음 이 부분을 provider로 빼던가 service걸 갖다 쓰는 것도
	private Preference findPreference(Long userId) {
		return preferenceRepository.findByUserId(userId)
			.orElseGet(() -> {
				User user = userReader.read(userId);
				return preferenceRepository.save(Preference.createDefault(user));
			});
	}

	private RecommendationResponse.Read toReadResponse(Recommendation recommendation) {
		return new RecommendationResponse.Read(
			recommendation.getId(),
			recommendation.getTask().getId(),
			recommendation.getRecommendedStartAt(),
			recommendation.getRecommendedEndAt(),
			recommendation.getRank(),
			recommendation.getScore(),
			recommendation.getReason(),
			recommendation.getStatus(),
			recommendation.isCalendarSyncRetryable(),
			recommendation.getMatchType()
		);
	}

}
