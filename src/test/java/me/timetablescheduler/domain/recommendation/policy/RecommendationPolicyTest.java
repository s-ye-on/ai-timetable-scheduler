package me.timetablescheduler.domain.recommendation.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.user.User;
import org.junit.jupiter.api.Test;

class RecommendationPolicyTest {

	private final RecommendationPolicy recommendationPolicy = new RecommendationPolicy();

	@Test
	void 특정_날짜의_추천_후보를_30분_단위로_생성한다() {
		User user = user();
		Task task = taskWithPreferredDate(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(11, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of());

		assertEquals(3, candidates.size());
		assertEquals(LocalTime.of(9, 0), candidates.get(0).startAt().toLocalTime());
		assertEquals(LocalTime.of(9, 30), candidates.get(1).startAt().toLocalTime());
		assertEquals(LocalTime.of(10, 0), candidates.get(2).startAt().toLocalTime());
	}

	@Test
	void 시간표와_겹치는_추천_후보는_제외한다() {
		User user = user();
		Task task = taskWithPreferredDate(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);
		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			"자료구조",
			DayOfWeek.MONDAY,
			"공학관",
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(timetableSlot));

		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 30))));
	}

	@Test
	void 선호시간대와_일치하는_후보가_더_높은_점수를_받는다() {
		User user = user();
		Task task = taskWithPreferredDate(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.MORNING,
			LocalTime.of(8, 0),
			LocalTime.of(14, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of());

		int morningScore = candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(9, 0)))
			.findFirst()
			.orElseThrow()
			.score();
		int lunchScore = candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(12, 0)))
			.findFirst()
			.orElseThrow()
			.score();

		assertTrue(morningScore > lunchScore);
	}

	@Test
	void deadline_이후_날짜의_추천_후보는_생성하지_않는다() {
		User user = user();
		Task task = Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			LocalDate.of(2026, 5, 19),
			null,
			null,
			null,
			null,
			LocalDate.of(2026, 5, 18),
			TaskPriority.NORMAL,
			"마감 이후 후보는 제외"
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(11, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of());

		assertTrue(candidates.isEmpty());
	}

	private Task taskWithPreferredDate(User user, LocalDate preferredDate) {
		return Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			preferredDate,
			null,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"과제할 시간 잡아줘"
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
