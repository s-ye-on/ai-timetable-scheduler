package me.timetablescheduler.domain.recommendation.service;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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
	private static final int DAY_OF_WEEK_SEARCH_DAYS = 14;
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
		List<BusyBlock> calendarBusyBlocks = findCalendarBusyBlocks(userId, task, now.toLocalDate());

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
		List<CandidateSlot> topCandidates = candidates.stream()
			.filter(candidate -> candidate.startAt().isAfter(selectionTime))
			.sorted(Comparator.comparingInt(CandidateSlot::score).reversed())
			.limit(MAX_RECOMMENDATION_COUNT)
			.toList();

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
				candidate.reason()
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

	private List<BusyBlock> findCalendarBusyBlocks(Long userId, Task task, LocalDate baseDate) {
		SearchDateTimeRange searchDateTimeRange = resolveSearchDateTimeRange(task, baseDate);

		List<BusyBlock> calendarBusyBlocks = calendarService.getCalendarBusyBlocksOrEmpty(
			userId,
			searchDateTimeRange.startAt(),
			searchDateTimeRange.endAt()
		);
		return calendarBusyBlocks == null ? List.of() : calendarBusyBlocks;
	}

	private SearchDateTimeRange resolveSearchDateTimeRange(Task task, LocalDate today) {
		if (task.getPreferredDate() != null) {
			return SearchDateTimeRange.of(task.getPreferredDate(), task.getPreferredDate());
		}

		if (task.getPreferredStartDate() != null && task.getPreferredEndDate() != null) {
			return SearchDateTimeRange.of(task.getPreferredStartDate(), task.getPreferredEndDate());
		}

		return SearchDateTimeRange.of(today, today.plusDays(DAY_OF_WEEK_SEARCH_DAYS - 1L));
	}

	/// todo : reader로 만들면 좋을듯
	/// taskId로 찾되, 현재 userId 소유여야하고, 없으면 예외를 던짐
	/// 현재 TaskService에서도 똑같은 코드가 반복중
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
			recommendation.isCalendarSyncRetryable()
		);
	}

	private record SearchDateTimeRange(
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		private static SearchDateTimeRange of(LocalDate startDate, LocalDate endDate) {
			return new SearchDateTimeRange(
				startDate.atStartOfDay(),
				endDate.atTime(LocalTime.MAX)
			);
		}
	}
}
