package me.timetablescheduler.domain.recommendation.policy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
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

class RecommendationSelectionPolicyTest {

	private static final LocalDate DATE = LocalDate.of(2026, 5, 18);
	private static final LocalDateTime NOW = DATE.atTime(9, 0);
	private final RecommendationPolicy recommendationPolicy = new RecommendationPolicy(
		Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC)
	);

	@Test
	void 미래_요청_조건과_가용시간을_만족하는_후보는_선호시간대와_달라도_선택할수있다() {
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), 0);

		boolean selectable = recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(), List.of(),
			DATE.atTime(14, 0), DATE.atTime(15, 0), NOW
		);

		assertTrue(selectable);
	}

	@Test
	void 과거_후보와_잘못된_지속시간_요청기간_밖_마감일_이후_후보는_선택할수없다() {
		User user = user();
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), 0);
		Task requestedDateTask = task(user, DATE, null);
		Task deadlineTask = task(user, DATE.plusDays(1), DATE);

		assertFalse(recommendationPolicy.isSelectable(
			requestedDateTask, preference, List.of(), List.of(), List.of(),
			DATE.atTime(8, 0), DATE.atTime(9, 0), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			requestedDateTask, preference, List.of(), List.of(), List.of(),
			DATE.atTime(10, 0), DATE.atTime(10, 30), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			requestedDateTask, preference, List.of(), List.of(), List.of(),
			DATE.atTime(10, 0), DATE.atTime(10, 0).plus(Duration.ofMinutes(60)).plusSeconds(59), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			requestedDateTask, preference, List.of(), List.of(), List.of(),
			DATE.atTime(10, 0), DATE.atTime(10, 0).plus(Duration.ofMinutes(60)).plusNanos(1), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			requestedDateTask, preference, List.of(), List.of(), List.of(),
			DATE.plusDays(1).atTime(10, 0), DATE.plusDays(1).atTime(11, 0), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			deadlineTask, preference, List.of(), List.of(), List.of(),
			DATE.plusDays(1).atTime(10, 0), DATE.plusDays(1).atTime(11, 0), NOW
		));
	}

	@Test
	void 최소여유시간에_걸친_전날반복수업과_확정일정은_선택을_막는다() {
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.MIDNIGHT, LocalTime.of(2, 0), 30);
		TimetableSlot previousDayClass = TimetableSlot.create(
			user, "야간 수업", DayOfWeek.SUNDAY, "강의실", LocalTime.of(23, 0), LocalTime.of(23, 59)
		);
		Task scheduledTask = task(user, DATE.minusDays(1), null);
		scheduledTask.schedule(DATE.minusDays(1).atTime(23, 45), DATE.atTime(0, 45));

		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(previousDayClass), List.of(), List.of(),
			DATE.atTime(0, 15), DATE.atTime(1, 15), DATE.minusDays(1).atTime(22, 0)
		));
		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(scheduledTask), List.of(),
			DATE.atTime(0, 15), DATE.atTime(1, 15), DATE.minusDays(1).atTime(22, 0)
		));
	}

	@Test
	void Calendar_충돌과_조회결과_누락은_선택을_막는다() {
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), 0);

		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(),
			List.of(new BusyBlock(DATE.atTime(10, 30), DATE.atTime(11, 30))),
			DATE.atTime(10, 0), DATE.atTime(11, 0), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(), null,
			DATE.atTime(10, 0), DATE.atTime(11, 0), NOW
		));
	}

	@Test
	void 최소여유시간과_정확히_맞닿는_후보는_기존충돌정책대로_허용한다() {
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), 30);
		BusyBlock busyBlock = new BusyBlock(DATE.atTime(9, 0), DATE.atTime(10, 0));

		assertTrue(recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(), List.of(busyBlock),
			DATE.atTime(10, 30), DATE.atTime(11, 30), NOW
		));
	}

	@Test
	void 매우큰_최소여유시간도_반복수업_충돌을검사하고_수업이없으면_선택할수있다() {
		int largeGapMinutes = 2_147_483_645;
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), largeGapMinutes);
		TimetableSlot mondayClass = TimetableSlot.create(
			user, "반복 수업", DayOfWeek.MONDAY, "강의실", LocalTime.of(10, 0), LocalTime.of(11, 0)
		);

		assertTrue(recommendationPolicy.isSelectable(
			task, preference, List.of(), List.of(), List.of(),
			DATE.atTime(12, 0), DATE.atTime(13, 0), NOW
		));
		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(mondayClass), List.of(), List.of(),
			DATE.atTime(12, 0), DATE.atTime(13, 0), NOW
		));
	}

	@Test
	void 확장시작시각에_끝난_수업도_다음주_반복수업이_범위에_있으면_선택을_막는다() {
		User user = user();
		Task task = task(user, DATE, null);
		Preference preference = preference(user, LocalTime.of(9, 0), LocalTime.of(18, 0), 10_080);
		TimetableSlot mondayClass = TimetableSlot.create(
			user, "주간 수업", DayOfWeek.MONDAY, "강의실", LocalTime.of(9, 0), LocalTime.of(10, 0)
		);

		assertFalse(recommendationPolicy.isSelectable(
			task, preference, List.of(mondayClass), List.of(), List.of(),
			DATE.atTime(10, 0), DATE.atTime(11, 0), NOW
		));
	}

	private Task task(User user, LocalDate preferredDate, LocalDate deadline) {
		return Task.create(
			user, "추천 확정", TaskCategory.ASSIGNMENT, 60,
			preferredDate, null, null, null, null, deadline,
			TaskPriority.NORMAL, "최신 조건 검증"
		);
	}

	private Preference preference(User user, LocalTime start, LocalTime end, int minimumGapMinutes) {
		return Preference.create(
			user, PreferredTimeRange.MORNING, start, end, minimumGapMinutes,
			ScheduleDensity.BALANCED, DeadlineTiming.BALANCED
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
