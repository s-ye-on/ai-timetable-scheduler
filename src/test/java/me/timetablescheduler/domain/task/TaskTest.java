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
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.*;

class TaskTest {

	@Test
	void 선호시간대가_null이어도_Task를_생성할_수_있다() {
		Task task = Task.create(
			사용자_생성(),
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
		Task task = 선호_시간대가_있는_Task_생성(PreferredTimeRange.AFTERNOON);

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
	void 스케줄된_Task는_상세정보를_수정할_수_없다() {
		Task task = 확정된_Task_생성();

		TaskException exception = assertThrows(TaskException.class, () -> task.updateDetails(
			"수정된 과제",
			TaskCategory.ASSIGNMENT,
			90,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"수정 요청"
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_없어도_Task를_생성할_수_있다() {
		Task task = assertDoesNotThrow(() -> Task.create(
			사용자_생성(),
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

		assertNull(task.getPreferredDate());
		assertNull(task.getPreferredDayOfWeek());
		assertNull(task.getPreferredStartDate());
		assertNull(task.getPreferredEndDate());
	}

	@Test
	void 선호요일과_허용기간을_함께_저장할_수_있다() {
		Task task = assertDoesNotThrow(() -> Task.create(
			사용자_생성(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			LocalDate.of(2026, 10, 19),
			LocalDate.of(2026, 10, 25),
			PreferredTimeRange.LUNCH,
			null,
			TaskPriority.NORMAL,
			"이번 주 안에 화요일 점심이면 좋겠어"
		));

		assertEquals(DayOfWeek.TUESDAY, task.getPreferredDayOfWeek());
		assertEquals(LocalDate.of(2026, 10, 19), task.getPreferredStartDate());
		assertEquals(LocalDate.of(2026, 10, 25), task.getPreferredEndDate());
	}

	@Test
	void 명시적인_30분_간격_밖_시작시각과_필수조건을_보존한다() {
		Task task = Task.create(
			사용자_생성(),
			"알고리즘 공부",
			TaskCategory.STUDY,
			120,
			null,
			DayOfWeek.THURSDAY,
			null,
			null,
			PreferredTimeRange.EVENING,
			null,
			TaskPriority.NORMAL,
			"목요일 저녁 7시 10분에 알고리즘 공부 2시간",
			true,
			true,
			LocalTime.of(19, 10)
		);

		assertEquals(Boolean.TRUE, task.getDateRequired());
		assertEquals(Boolean.TRUE, task.getTimeRequired());
		assertEquals(LocalTime.of(19, 10), task.getPreferredStartTime());
	}

	@Test
	void 초나_나노초가_있는_명시시각은_거부한다() {
		TaskException exception = assertThrows(TaskException.class, () -> Task.create(
			사용자_생성(),
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			PreferredTimeRange.EVENING,
			null,
			TaskPriority.NORMAL,
			"화요일 저녁 과제",
			true,
			true,
			LocalTime.of(19, 0, 1)
		));

		assertEquals(ExceptionCode.INVALID_TASK, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_둘_이상이면_Task를_생성할_수_없다() {
		TaskException exception = assertThrows(TaskException.class, () -> Task.create(
			사용자_생성(),
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
		Task task = Task_생성();
		LocalDateTime startAt = LocalDateTime.of(2026, 5, 14, 10, 0);
		LocalDateTime endAt = LocalDateTime.of(2026, 5, 14, 11, 0);

		task.schedule(startAt, endAt);

		assertEquals(TaskStatus.SCHEDULED, task.getStatus());
		assertEquals(startAt, task.getScheduledStartAt());
		assertEquals(endAt, task.getScheduledEndAt());
	}

	@Test
	void 스케줄_확정시_Task_소요시간과_배정시간이_다르면_실패한다() {
		Task task = Task_생성();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 10, 30)
		));

		assertEquals(ExceptionCode.INVALID_TASK_SCHEDULE_TIME, exception.getExceptionCode());
	}

	@Test
	void 이미_스케줄된_Task는_다시_스케줄_확정할_수_없다() {
		Task task = 확정된_Task_생성();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 12, 0),
			LocalDateTime.of(2026, 5, 14, 13, 0)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 스케줄된_Task는_미배정으로_되돌릴_수_있다() {
		Task task = 확정된_Task_생성();

		task.unschedule();

		assertEquals(TaskStatus.UNSCHEDULED, task.getStatus());
		assertNull(task.getScheduledStartAt());
		assertNull(task.getScheduledEndAt());
	}

	@Test
	void 미배정_Task는_미배정으로_되돌릴_수_없다() {
		Task task = Task_생성();

		TaskException exception = assertThrows(TaskException.class, task::unschedule);

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 미배정_Task는_취소할_수_있다() {
		Task task = Task_생성();

		task.cancel();

		assertEquals(TaskStatus.CANCELED, task.getStatus());
	}

	@Test
	void 스케줄된_Task는_취소할_수_있다() {
		Task task = 확정된_Task_생성();

		task.cancel();

		assertEquals(TaskStatus.CANCELED, task.getStatus());
	}

	@Test
	void 취소된_Task는_다른_상태로_전이할_수_없다() {
		Task task = Task_생성();
		task.cancel();

		TaskException exception = assertThrows(TaskException.class, () -> task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 11, 0)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 스케줄된_Task는_종료시간이_지났으면_완료할_수_있다() {
		Task task = 확정된_Task_생성();

		task.complete(LocalDateTime.of(2026, 5, 14, 11, 0));

		assertEquals(TaskStatus.COMPLETED, task.getStatus());
	}

	@Test
	void 스케줄된_Task는_종료시간이_지나기_전에는_완료할_수_없다() {
		Task task = 확정된_Task_생성();

		TaskException exception = assertThrows(TaskException.class, () -> task.complete(
			LocalDateTime.of(2026, 5, 14, 10, 59)
		));

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 완료된_Task는_취소하거나_미배정으로_되돌릴_수_없다() {
		Task task = 확정된_Task_생성();
		task.complete(LocalDateTime.of(2026, 5, 14, 11, 0));

		TaskException cancelException = assertThrows(TaskException.class, task::cancel);
		TaskException unscheduleException = assertThrows(TaskException.class, task::unschedule);

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, cancelException.getExceptionCode());
		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, unscheduleException.getExceptionCode());
	}

	private Task 확정된_Task_생성() {
		Task task = Task_생성();
		task.schedule(
			LocalDateTime.of(2026, 5, 14, 10, 0),
			LocalDateTime.of(2026, 5, 14, 11, 0)
		);

		return task;
	}

	private Task Task_생성() {
		return 선호_시간대가_있는_Task_생성(null);
	}

	private Task 선호_시간대가_있는_Task_생성(PreferredTimeRange preferredTimeRange) {
		return Task.create(
			사용자_생성(),
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

	private User 사용자_생성() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
