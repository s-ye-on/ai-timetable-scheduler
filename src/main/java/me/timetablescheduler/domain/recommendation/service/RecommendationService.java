package me.timetablescheduler.domain.recommendation.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.PreferenceException;
import me.timetablescheduler.global.exception.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationService {
	private final RecommendationRepository recommendationRepository;
	private final TaskRepository taskRepository;
	private final PreferenceRepository preferenceRepository;
	private final TimetableSlotRepository timetableSlotRepository;
	private final UserReader userReader;

	@Transactional
	public List<Recommendation> recommend(Long userId, Long taskId) {
		User user = userReader.read(userId);
		Task task = findTask(taskId, userId);
		Preference preference = findPreference(userId);
		List<TimetableSlot> timetableSlots = timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(userId);

		List<CandidateSlot> candidates = generateCandidateSlots(task, preference, timetableSlots);

		List<CandidateSlot> topCandidates = candidates.stream()
			.sorted(Comparator.comparingInt(CandidateSlot::score).reversed())
			.limit(3)
			.toList();

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

		return recommendationRepository.saveAll(recommendations);
	}

	private List<CandidateSlot> generateCandidateSlots(
		Task task,
		Preference preference,
		List<TimetableSlot> timetableSlots
	) {
		return List.of();
	}

	private Task findTask(Long taskId, Long userId) {
		return taskRepository.findByIdAndUserId(taskId, userId)
			.orElseThrow(() -> new TaskException(ExceptionCode.NOT_FOUND_TASK));
	}

	private Preference findPreference(Long userId) {
		return preferenceRepository.findByUserId(userId)
			.orElseThrow(() -> new PreferenceException(ExceptionCode.NOT_FOUND_PREFERENCE));
	}

	private record CandidateSlot(
		LocalDateTime startAt,
		LocalDateTime endAt,
		int score,
		String reason
	) {
	}
}
