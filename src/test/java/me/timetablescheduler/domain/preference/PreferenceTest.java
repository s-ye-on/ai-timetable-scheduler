package me.timetablescheduler.domain.preference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalTime;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.PreferenceException;
import org.junit.jupiter.api.Test;

class PreferenceTest {

	@Test
	void 기본_사용자_선호를_생성한다() {
		Preference preference = Preference.createDefault(user());

		assertEquals(PreferredTimeRange.ANYTIME, preference.getPreferredTimeRange());
		assertEquals(LocalTime.of(9, 0), preference.getScheduleStartTime());
		assertEquals(LocalTime.of(22, 0), preference.getScheduleEndTime());
		assertEquals(10, preference.getMinimumGapMinutes());
		assertEquals(ScheduleDensity.BALANCED, preference.getScheduleDensity());
		assertEquals(DeadlineTiming.BALANCED, preference.getDeadlineTiming());
	}

	@Test
	void 사용자_선호를_생성한다() {
		Preference preference = Preference.create(
			user(),
			PreferredTimeRange.EVENING,
			LocalTime.of(10, 0),
			LocalTime.of(21, 0),
			15,
			ScheduleDensity.RELAXED,
			DeadlineTiming.ASAP
		);

		assertEquals(PreferredTimeRange.EVENING, preference.getPreferredTimeRange());
		assertEquals(LocalTime.of(10, 0), preference.getScheduleStartTime());
		assertEquals(LocalTime.of(21, 0), preference.getScheduleEndTime());
		assertEquals(15, preference.getMinimumGapMinutes());
		assertEquals(ScheduleDensity.RELAXED, preference.getScheduleDensity());
		assertEquals(DeadlineTiming.ASAP, preference.getDeadlineTiming());
	}

	@Test
	void 사용자_선호를_수정한다() {
		Preference preference = Preference.createDefault(user());

		preference.update(
			PreferredTimeRange.MORNING,
			LocalTime.of(8, 0),
			LocalTime.of(20, 0),
			20,
			ScheduleDensity.COMPACT,
			DeadlineTiming.NEAR_DEADLINE
		);

		assertEquals(PreferredTimeRange.MORNING, preference.getPreferredTimeRange());
		assertEquals(LocalTime.of(8, 0), preference.getScheduleStartTime());
		assertEquals(LocalTime.of(20, 0), preference.getScheduleEndTime());
		assertEquals(20, preference.getMinimumGapMinutes());
		assertEquals(ScheduleDensity.COMPACT, preference.getScheduleDensity());
		assertEquals(DeadlineTiming.NEAR_DEADLINE, preference.getDeadlineTiming());
	}

	@Test
	void 필수값이_없으면_사용자_선호를_생성할_수_없다() {
		PreferenceException exception = assertThrows(PreferenceException.class, () -> Preference.create(
			null,
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(22, 0),
			10,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		));

		assertEquals(ExceptionCode.INVALID_PREFERENCE, exception.getExceptionCode());
	}

	@Test
	void 추천_가능_시작시간이_종료시간보다_빠르지_않으면_생성할_수_없다() {
		PreferenceException exception = assertThrows(PreferenceException.class, () -> Preference.create(
			user(),
			PreferredTimeRange.ANYTIME,
			LocalTime.of(22, 0),
			LocalTime.of(9, 0),
			10,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		));

		assertEquals(ExceptionCode.INVALID_PREFERENCE_TIME_RANGE, exception.getExceptionCode());
	}

	@Test
	void 최소_여유시간이_음수이면_생성할_수_없다() {
		PreferenceException exception = assertThrows(PreferenceException.class, () -> Preference.create(
			user(),
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(22, 0),
			-5,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		));

		assertEquals(ExceptionCode.INVALID_PREFERENCE_GAP, exception.getExceptionCode());
	}

	@Test
	void 최소_여유시간이_5분_단위가_아니면_생성할_수_없다() {
		PreferenceException exception = assertThrows(PreferenceException.class, () -> Preference.create(
			user(),
			PreferredTimeRange.ANYTIME,
			LocalTime.of(9, 0),
			LocalTime.of(22, 0),
			7,
			ScheduleDensity.BALANCED,
			DeadlineTiming.BALANCED
		));

		assertEquals(ExceptionCode.INVALID_PREFERENCE_GAP, exception.getExceptionCode());
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
