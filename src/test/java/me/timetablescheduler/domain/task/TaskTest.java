package me.timetablescheduler.domain.task;

import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;

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

	@Test
	void 미배정_Task를_스케줄_확정하면_SCHEDULED가_된다() {
		Task task = task();
		LocalDateTime startAt = LocalDateTime.of(2026, 5, 14, 10, 0);
		LocalDateTime endAt = LocalDateTime.of(2026, 5, 14, 11, 0);

		task.schedule(startAt, endAt);

		assertEquals(TaskStatus.SCHEDULED, task.getStatus());
		assertEquals(startAt, task.getScheduledStartAt());
		assertEquals(endAt, task.getScheduledEndAt());
	}

	@Test
	void 스케줄_확정시_Task_소요시간과_배정시간이_다르면_실패한다() {
		Task task = task();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 10, 30)
		));

		assertEquals(ExceptionCode.INVALID_TASK_SCHEDULE_TIME, exception.getExceptionCode());
	}

	@Test
	void 이미_스케줄된_Task는_다시_스케줄_확정할_수_없다() {
		Task task = scheduledTask();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 12, 0),
			LocalDateTime.of(2026, 5, 14, 13, 0)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 스케줄된_Task는_미배정으로_되돌릴_수_있다() {
		Task task = scheduledTask();

		task.unschedule();

		assertEquals(TaskStatus.UNSCHEDULED, task.getStatus());
		assertNull(task.getScheduledStartAt());
		assertNull(task.getScheduledEndAt());
	}

	@Test
	void 미배정_Task는_미배정으로_되돌릴_수_없다() {
		Task task = task();

		TaskException exception = assertThrows(TaskException.class, task::unschedule);

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 미배정_Task는_취소할_수_있다() {
		Task task = task();

		task.cancel();

		assertEquals(TaskStatus.CANCELED, task.getStatus());
	}

	@Test
	void 스케줄된_Task는_취소할_수_있다() {
		Task task = scheduledTask();

		task.cancel();

		assertEquals(TaskStatus.CANCELED, task.getStatus());
	}

	@Test
	void 취소된_Task는_다른_상태로_전이할_수_없다() {
		Task task = task();
		task.cancel();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 11, 0)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 스케줄된_Task는_종료시간이_지났으면_완료할_수_있다() {
		Task task = scheduledTask();

		task.complete(LocalDateTime.of(2026, 5, 14, 11, 0));

		assertEquals(TaskStatus.COMPLETED, task.getStatus());
	}

	@Test
	void 스케줄된_Task는_종료시간이_지나기_전에는_완료할_수_없다() {
		Task task = scheduledTask();

		TaskException exception = assertThrows(TaskException.class, () -> task.complete(
			LocalDateTime.of(2026, 5, 14, 10, 59)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 완료된_Task는_취소하거나_미배정으로_되돌릴_수_없다() {
		Task task = scheduledTask();
		task.complete(LocalDateTime.of(2026, 5, 14, 11, 0));

		TaskException cancelException = assertThrows(TaskException.class, task::cancel);
		TaskException unscheduleException = assertThrows(TaskException.class, task::unschedule);

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, cancelException.getExceptionCode());
		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, unscheduleException.getExceptionCode());
	}

	private Task scheduledTask() {
		Task task = task();
		task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 11, 0)
		);

		return task;
	}

	private Task task() {
		return taskWithPreferredTimeRange(null);
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
