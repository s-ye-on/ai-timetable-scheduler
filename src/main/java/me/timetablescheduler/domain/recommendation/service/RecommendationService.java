package me.timetablescheduler.domain.recommendation.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
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
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.RecommendationException;
import me.timetablescheduler.global.exception.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {
	private static final String RECOMMENDATION_CREATED_MESSAGE = "추천 가능한 시간을 찾았습니다.";
	private static final String NO_RECOMMENDATION_MESSAGE = "추천 가능한 시간이 없습니다. 시간표 또는 선호 시간 범위를 조정해 주세요.";
	private static final int MAX_RECOMMENDATION_COUNT = 3;

	private final RecommendationRepository recommendationRepository;
	private final TaskRepository taskRepository;
	private final PreferenceRepository preferenceRepository;
	private final TimetableSlotRepository timetableSlotRepository;
	private final UserReader userReader;
	private final RecommendationPolicy recommendationPolicy;

	@Transactional
	public RecommendationResponse.Generate recommend(Long userId, Long taskId) {
		User user = userReader.read(userId);
		Task task = findTask(taskId, userId);
		task.validateRecommendable();
		Preference preference = findPreference(userId);
		List<TimetableSlot> timetableSlots = timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(userId);
		List<Task> scheduledTasks = taskRepository.findAllByUserIdAndStatus(userId, TaskStatus.SCHEDULED);

		expireExistingProposedRecommendations(taskId, userId);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			timetableSlots,
			scheduledTasks
		);

		List<CandidateSlot> topCandidates = candidates.stream()
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

	@Transactional
	public RecommendationResponse.Read select(Long userId, Long recommendationId) {
		Recommendation recommendation = findRecommendation(recommendationId, userId);
		Task task = recommendation.getTask();

		recommendation.select();
		task.schedule(recommendation.getRecommendedStartAt(), recommendation.getRecommendedEndAt());
		rejectOtherProposedRecommendations(task.getId(), userId, recommendation);

		return toReadResponse(recommendation);
	}

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
			recommendation.getStatus()
		);
	}
}
