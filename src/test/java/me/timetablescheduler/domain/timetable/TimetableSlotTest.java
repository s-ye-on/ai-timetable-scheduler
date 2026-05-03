package me.timetablescheduler.domain.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalTime;

import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TimetableSlotException;
import org.junit.jupiter.api.Test;

class TimetableSlotTest {

	@Test
	void 시간표를_생성하면_필드와_생성시간_수정시간이_설정된다() {
		User user = user();

		TimetableSlot slot = TimetableSlot.create(
			user,
			"자료구조",
			DayOfWeek.MONDAY,
			"공학관 101",
			LocalTime.of(9, 0),
			LocalTime.of(10, 30)
		);

		assertEquals(user, slot.getUser());
		assertEquals("자료구조", slot.getSubjectName());
		assertEquals(DayOfWeek.MONDAY, slot.getDayOfWeek());
		assertEquals("공학관 101", slot.getLocation());
		assertEquals(LocalTime.of(9, 0), slot.getStartTime());
		assertEquals(LocalTime.of(10, 30), slot.getEndTime());
		assertNotNull(slot.getCreateAt());
		assertNotNull(slot.getUpdateAt());
	}

	@Test
	void 시간표를_수정하면_자신의_상태를_변경한다() {
		TimetableSlot slot = validSlot();

		slot.update(
			"운영체제",
			DayOfWeek.TUESDAY,
			"공학관 202",
			LocalTime.of(13, 0),
			LocalTime.of(14, 30)
		);

		assertEquals("운영체제", slot.getSubjectName());
		assertEquals(DayOfWeek.TUESDAY, slot.getDayOfWeek());
		assertEquals("공학관 202", slot.getLocation());
		assertEquals(LocalTime.of(13, 0), slot.getStartTime());
		assertEquals(LocalTime.of(14, 30), slot.getEndTime());
	}

	@Test
	void 과목명이_비어있으면_INVALID_TIMETABLE_SLOT을_던진다() {
		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> TimetableSlot.create(
				user(),
				" ",
				DayOfWeek.MONDAY,
				"공학관 101",
				LocalTime.of(9, 0),
				LocalTime.of(10, 30)
			)
		);

		assertEquals(ExceptionCode.INVALID_TIMETABLE_SLOT, exception.getExceptionCode());
	}

	@Test
	void 요일이_없으면_INVALID_TIMETABLE_SLOT을_던진다() {
		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> TimetableSlot.create(
				user(),
				"자료구조",
				null,
				"공학관 101",
				LocalTime.of(9, 0),
				LocalTime.of(10, 30)
			)
		);

		assertEquals(ExceptionCode.INVALID_TIMETABLE_SLOT, exception.getExceptionCode());
	}

	@Test
	void 시작시간이_종료시간과_같으면_INVALID_TIMETABLE_SLOT_TIME을_던진다() {
		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> TimetableSlot.create(
				user(),
				"자료구조",
				DayOfWeek.MONDAY,
				"공학관 101",
				LocalTime.of(9, 0),
				LocalTime.of(9, 0)
			)
		);

		assertEquals(ExceptionCode.INVALID_TIMETABLE_SLOT_TIME, exception.getExceptionCode());
	}

	@Test
	void 시작시간이_종료시간보다_늦으면_INVALID_TIMETABLE_SLOT_TIME을_던진다() {
		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> TimetableSlot.create(
				user(),
				"자료구조",
				DayOfWeek.MONDAY,
				"공학관 101",
				LocalTime.of(10, 30),
				LocalTime.of(9, 0)
			)
		);

		assertEquals(ExceptionCode.INVALID_TIMETABLE_SLOT_TIME, exception.getExceptionCode());
	}

	private TimetableSlot validSlot() {
		return TimetableSlot.create(
			user(),
			"자료구조",
			DayOfWeek.MONDAY,
			"공학관 101",
			LocalTime.of(9, 0),
			LocalTime.of(10, 30)
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
