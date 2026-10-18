package me.timetablescheduler.domain.recommendation.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationPolicyTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 5, 18);
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-17T15:00:00Z"), ZoneOffset.UTC);
	private final RecommendationPolicy recommendationPolicy = new RecommendationPolicy(CLOCK);

	@Test
	void 과거_날짜의_후보는_생성하지_않는다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2000, 1, 1));
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED);

		assertTrue(recommendationPolicy.generateCandidates(task, preference, List.of(), List.of()).isEmpty());
	}

	@Test
	void ASAP은_마감에_가까운_날보다_빠른_날에_높은_가점을_준다() {
		User user = 사용자_생성();
		LocalDate date = LocalDate.of(2026, 5, 18);
		Task task = Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60,
			null, null, date, date.plusDays(2), null, date.plusDays(2), TaskPriority.LOW, "가능한 빨리");
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertTrue(점수_조회(candidates, date, LocalTime.of(9, 0))
			> 점수_조회(candidates, date.plusDays(2), LocalTime.of(9, 0)));
		assertEquals(75, 점수_조회(candidates, date, LocalTime.of(9, 0)));
		assertEquals(74, 점수_조회(candidates, date.plusDays(1), LocalTime.of(9, 0)));
		assertEquals(73, 점수_조회(candidates, date.plusDays(2), LocalTime.of(9, 0)));
	}

	@ParameterizedTest
	@CsvSource({
		"2026-05-18T00:15:00Z, 09:30, 3",
		"2026-05-18T00:29:59Z, 09:30, 3",
		"2026-05-18T00:30:00Z, 10:00, 2",
		"2026-05-18T00:30:00.000000001Z, 10:00, 2"
	})
	void 오늘은_현재_시각보다_뒤인_30분_격자_후보만_생성한다(
		Instant instant, LocalTime expectedFirstStart, int expectedCount
	) {
		List<CandidateSlot> candidates = 기준_시각의_후보_생성(instant, TODAY, LocalTime.of(9, 0), LocalTime.of(11, 0));

		assertEquals(expectedCount, candidates.size());
		assertEquals(expectedFirstStart, candidates.getFirst().startAt().toLocalTime());
		LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul"));
		assertTrue(candidates.stream().allMatch(candidate -> candidate.startAt().isAfter(now)));
	}

	@ParameterizedTest
	@CsvSource({
		"2026-05-17T14:59:59Z, 00:00, 2",
		"2026-05-17T15:00:00Z, 00:30, 1",
		"2026-05-17T15:00:01Z, 00:30, 1"
	})
	void UTC_날짜가_달라도_서울_자정_경계로_후보를_판단한다(
		Instant instant, LocalTime expectedFirstStart, int expectedCount
	) {
		List<CandidateSlot> candidates = 기준_시각의_후보_생성(instant, TODAY, LocalTime.MIDNIGHT, LocalTime.of(1, 0));

		assertEquals(expectedCount, candidates.size());
		assertEquals(TODAY.atTime(expectedFirstStart), candidates.getFirst().startAt());
	}

	@Test
	void UTC로는_어제여도_서울에서_지난_날짜와_오늘의_끝난_시간은_제외한다() {
		Instant seoulMidnight = Instant.parse("2026-05-17T15:00:01Z");
		assertTrue(기준_시각의_후보_생성(seoulMidnight, TODAY.minusDays(1), LocalTime.of(9, 0), LocalTime.of(11, 0)).isEmpty());
		assertTrue(기준_시각의_후보_생성(Instant.parse("2026-05-18T14:59:59Z"), TODAY,
			LocalTime.of(9, 0), LocalTime.of(11, 0)).isEmpty());
	}

	@Test
	void 요청_기간의_과거_부분만_제외하고_허용_기간과_마감일은_유지한다() {
		User user = 사용자_생성();
		Task task = Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60,
			null, null, TODAY.minusDays(2), TODAY.plusDays(2), null, TODAY.plusDays(1), TaskPriority.LOW, "기간 내 추천");
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertEquals(List.of(TODAY, TODAY.plusDays(1)), candidates.stream().map(slot -> slot.startAt().toLocalDate()).toList());
	}

	@Test
	void ASAP_가점은_20점을_넘거나_음수가_되지_않는다() {
		User user = 사용자_생성();
		Task task = Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60,
			null, null, TODAY, TODAY.plusDays(25), null, TODAY.plusDays(25), TaskPriority.LOW, "가능한 빨리");
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertEquals(75, 점수_조회(candidates, TODAY, LocalTime.of(9, 0)));
		assertEquals(56, 점수_조회(candidates, TODAY.plusDays(19), LocalTime.of(9, 0)));
		assertEquals(55, 점수_조회(candidates, TODAY.plusDays(20), LocalTime.of(9, 0)));
		assertEquals(55, 점수_조회(candidates, TODAY.plusDays(25), LocalTime.of(9, 0)));
	}

	@ParameterizedTest
	@CsvSource({"NEAR_DEADLINE, 1, 140", "BALANCED, 2, 135"})
	void 다른_마감_성향과_기본_선호_중요도_밀도_가중치를_유지한다(
		DeadlineTiming timing, int deadlineOffset, int expectedScore
	) {
		User user = 사용자_생성();
		Task task = Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60,
			TODAY, null, null, null, null, TODAY.plusDays(deadlineOffset), TaskPriority.HIGH, "원점수 검증");
		Preference preference = Preference.create(user, PreferredTimeRange.MORNING,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.RELAXED, timing);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertEquals(expectedScore, candidates.getFirst().score());
	}

	@Test
	void 마감일이_없으면_ASAP이어도_기존처럼_마감_가점을_주지_않는다() {
		User user = 사용자_생성();
		Task task = Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60,
			null, null, TODAY, TODAY.plusDays(1), null, null, TaskPriority.LOW, "마감 없음");
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.ASAP);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertEquals(55, 점수_조회(candidates, TODAY, LocalTime.of(9, 0)));
		assertEquals(55, 점수_조회(candidates, TODAY.plusDays(1), LocalTime.of(9, 0)));
	}

	private List<CandidateSlot> 기준_시각의_후보_생성(Instant instant, LocalDate date, LocalTime startTime, LocalTime endTime) {
		User user = 사용자_생성();
		Task task = Task.create(user, "약속", TaskCategory.APPOINTMENT, 30,
			date, null, null, null, null, null, TaskPriority.NORMAL, "시간 경계 검증");
		Preference preference = Preference.create(user, PreferredTimeRange.ANYTIME,
			startTime, endTime, 0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED);
		RecommendationPolicy policy = new RecommendationPolicy(Clock.fixed(instant, ZoneOffset.UTC));
		return policy.generateCandidates(task, preference, List.of(), List.of());
	}

	@Test
	void 특정_날짜의_추천_후보를_30분_단위로_생성한다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(11, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertEquals(3, candidates.size());
		assertEquals(LocalTime.of(9, 0), candidates.get(0).startAt().toLocalTime());
		assertEquals(LocalTime.of(9, 30), candidates.get(1).startAt().toLocalTime());
		assertEquals(LocalTime.of(10, 0), candidates.get(2).startAt().toLocalTime());
	}

	@Test
	void 시간표와_겹치는_추천_후보는_제외한다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
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

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(timetableSlot),
			List.of()
		);

		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 30))));
	}

	@Test
	void 선호시간대와_일치하는_후보가_더_높은_점수를_받는다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.MORNING,
			LocalTime.of(8, 0),
			LocalTime.of(14, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

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
	void 우선순위가_높으면_기준일에_가까운_후보가_더_높은_점수를_받는다() {
		User user = 사용자_생성();
		LocalDate today = TODAY;
		Task task = Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			null,
			today,
			today.plusDays(2),
			null,
			null,
			TaskPriority.HIGH,
			"이번 주 안에 과제할 시간 잡아줘"
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(10, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		int todayScore = candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalDate().equals(today))
			.findFirst()
			.orElseThrow()
			.score();
		int laterScore = candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalDate().equals(today.plusDays(2)))
			.findFirst()
			.orElseThrow()
			.score();

		assertTrue(todayScore > laterScore);
	}

	@Test
	void 특정_요일_후보는_기준일부터_14일_범위에서_생성한다() {
		User user = 사용자_생성();
		LocalDate today = TODAY;
		Task task = Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			today.getDayOfWeek(),
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"오늘과 같은 요일에 과제할 시간 잡아줘"
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(10, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertTrue(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalDate().equals(today)));
		assertTrue(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalDate().equals(today.plusDays(7))));
	}

	@Test
	void deadline_이후_날짜의_추천_후보는_생성하지_않는다() {
		User user = 사용자_생성();
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

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertTrue(candidates.isEmpty());
	}

	@Test
	void near_deadline은_마감_당일보다_마감_하루_전_후보에_더_높은_점수를_준다() {
		User user = 사용자_생성();
		LocalDate deadline = LocalDate.of(2026, 5, 20);
		Task task = Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			null,
			deadline.minusDays(1),
			deadline,
			null,
			deadline,
			TaskPriority.LOW,
			"마감 근처에 과제할 시간 잡아줘"
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(10, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.NEAR_DEADLINE
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		int dayBeforeScore = 점수_조회(candidates, deadline.minusDays(1), LocalTime.of(9, 0));
		int deadlineDayScore = 점수_조회(candidates, deadline, LocalTime.of(9, 0));

		assertTrue(dayBeforeScore > deadlineDayScore);
	}

	@Test
	void 이미_확정된_Task와_겹치는_추천_후보는_제외한다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		Task scheduledTask = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		scheduledTask.schedule(
			LocalDate.of(2026, 5, 18).atTime(10, 0),
			LocalDate.of(2026, 5, 18).atTime(11, 0)
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(),
			List.of(scheduledTask)
		);

		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 30))));
	}

	@Test
	void Google_Calendar_BusyBlock과_겹치는_추천_후보는_제외한다() {
		User user = 사용자_생성();
		LocalDate date = LocalDate.of(2026, 5, 18);
		Task task = 선호_날짜가_있는_Task_생성(user, date);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);
		BusyBlock calendarBusyBlock = new BusyBlock(
			date.atTime(10, 0),
			date.atTime(11, 0)
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(),
			List.of(),
			List.of(calendarBusyBlock)
		);

		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 30))));
	}

	@Test
	void 시간표와_이미_확정된_Task를_모두_고려해서_추천_후보를_생성한다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			"운영체제",
			DayOfWeek.MONDAY,
			"공학관",
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);
		Task scheduledTask = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		scheduledTask.schedule(
			LocalDate.of(2026, 5, 18).atTime(12, 0),
			LocalDate.of(2026, 5, 18).atTime(13, 0)
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(14, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(timetableSlot),
			List.of(scheduledTask)
		);

		assertTrue(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(9, 0))));
		assertTrue(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(11, 0))));
		assertTrue(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(13, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(10, 30))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(12, 0))));
		assertFalse(candidates.stream().anyMatch(candidate -> candidate.startAt().toLocalTime().equals(LocalTime.of(12, 30))));
	}

	@Test
	void compact_밀도는_BusyBlock과_가까운_후보에_더_높은_점수를_준다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			"운영체제",
			DayOfWeek.MONDAY,
			"공학관",
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(15, 0),
			0,
			ScheduleDensity.COMPACT,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(timetableSlot),
			List.of()
		);

		int nearScore = 점수_조회(candidates, LocalTime.of(11, 0));
		int farScore = 점수_조회(candidates, LocalTime.of(13, 0));

		assertTrue(nearScore > farScore);
	}

	@Test
	void relaxed_밀도는_BusyBlock과_충분히_떨어진_후보에_더_높은_점수를_준다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			"운영체제",
			DayOfWeek.MONDAY,
			"공학관",
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(15, 0),
			0,
			ScheduleDensity.RELAXED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(timetableSlot),
			List.of()
		);

		int nearScore = 점수_조회(candidates, LocalTime.of(11, 0));
		int farScore = 점수_조회(candidates, LocalTime.of(13, 0));

		assertTrue(farScore > nearScore);
	}

	@Test
	void balanced_밀도는_BusyBlock과_적당한_간격의_후보에_더_높은_점수를_준다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			"운영체제",
			DayOfWeek.MONDAY,
			"공학관",
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(15, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(timetableSlot),
			List.of()
		);

		int balancedScore = 점수_조회(candidates, LocalTime.of(11, 30));
		int farScore = 점수_조회(candidates, LocalTime.of(13, 0));

		assertTrue(balancedScore > farScore);
	}

	@Test
	void anytime은_선호_시간대_reason을_만들지_않는다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(10, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(task, preference, List.of(), List.of());

		assertFalse(candidates.get(0).reason().contains("선호 시간대와 일치합니다"));
	}

	@Test
	void scheduledTasks에_현재_Task가_포함되어도_자기_자신은_충돌_대상에서_제외한다() {
		User user = 사용자_생성();
		Task task = 선호_날짜가_있는_Task_생성(user, LocalDate.of(2026, 5, 18));
		ReflectionTestUtils.setField(task, "id", 1L);
		ReflectionTestUtils.setField(task, "scheduledStartAt", LocalDate.of(2026, 5, 18).atTime(10, 0));
		ReflectionTestUtils.setField(task, "scheduledEndAt", LocalDate.of(2026, 5, 18).atTime(11, 0));
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(12, 0),
			0,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		);

		List<CandidateSlot> candidates = recommendationPolicy.generateCandidates(
			task,
			preference,
			List.of(),
			List.of(task)
		);

		assertEquals(5, candidates.size());
	}

	private int 점수_조회(List<CandidateSlot> candidates, LocalTime startTime) {
		return candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalTime().equals(startTime))
			.findFirst()
			.orElseThrow()
			.score();
	}

	private int 점수_조회(List<CandidateSlot> candidates, LocalDate date, LocalTime startTime) {
		return candidates.stream()
			.filter(candidate -> candidate.startAt().toLocalDate().equals(date))
			.filter(candidate -> candidate.startAt().toLocalTime().equals(startTime))
			.findFirst()
			.orElseThrow()
			.score();
	}

	private Task 선호_날짜가_있는_Task_생성(User user, LocalDate preferredDate) {
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

	private User 사용자_생성() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
