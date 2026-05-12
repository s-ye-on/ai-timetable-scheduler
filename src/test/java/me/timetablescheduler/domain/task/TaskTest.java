package me.timetablescheduler.domain.task;

import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

	@Test
	void 선호시간대가_null이어도_Task를_생성할_수_있다() {
		Task task = Task.create(
			user(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"화요일에 과제할 시간 잡아줘"
		);

		assertNull(task.getPreferredTimeRange());
		assertEquals(DayOfWeek.TUESDAY, task.getPreferredDayOfWeek());
	}

	@Test
	void 선호시간대를_null로_수정할_수_있다() {
		Task task = taskWithPreferredTimeRange(PreferredTimeRange.AFTERNOON);

		task.updateDetails(
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"화요일에 과제할 시간 잡아줘"
		);

		assertNull(task.getPreferredTimeRange());
	}

	@Test
	void 날짜조건이_없으면_Task를_생성할_수_없다() {
		TaskException exception = assertThrows(TaskException.class, () -> Task.create(
			user(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			null,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"과제할 시간 잡아줘"
		));

		assertEquals(ExceptionCode.INVALID_TASK, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_둘_이상이면_Task를_생성할_수_없다() {
		TaskException exception = assertThrows(TaskException.class, () -> Task.create(
			user(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			LocalDate.of(2026, 5, 12),
			DayOfWeek.TUESDAY,
			null,
			null,
			PreferredTimeRange.AFTERNOON,
			null,
			TaskPriority.NORMAL,
			"5월 12일 화요일에 과제"
		));

		assertEquals(ExceptionCode.INVALID_TASK, exception.getExceptionCode());
	}

	private Task taskWithPreferredTimeRange(PreferredTimeRange preferredTimeRange) {
		return Task.create(
			user(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			preferredTimeRange,
			null,
			TaskPriority.NORMAL,
			"화요일 오후에 과제할 시간 잡아줘"
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
