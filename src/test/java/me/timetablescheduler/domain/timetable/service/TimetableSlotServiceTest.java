package me.timetablescheduler.domain.timetable.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotRequest;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TimetableSlotException;
import me.timetablescheduler.global.exception.UserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TimetableSlotServiceTest {

	private TimetableSlotRepository slotRepository;
	private UserRepository userRepository;
	private TimetableSlotService timetableSlotService;

	@BeforeEach
	void setUp() {
		slotRepository = org.mockito.Mockito.mock(TimetableSlotRepository.class);
		userRepository = org.mockito.Mockito.mock(UserRepository.class);
		timetableSlotService = new TimetableSlotService(slotRepository, userRepository);
	}

	@Test
	void 시간표를_생성하면_저장하고_응답을_반환한다() {
		User user = user();
		TimetableSlotRequest.Create request = createRequest();
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(slotRepository.existsOverlappingSlot(1L, request.dayOfWeek(), request.startTime(), request.endTime()))
			.thenReturn(false);
		when(slotRepository.save(any(TimetableSlot.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TimetableSlotResponse.Read response = timetableSlotService.create(request, 1L);

		ArgumentCaptor<TimetableSlot> captor = ArgumentCaptor.forClass(TimetableSlot.class);
		verify(slotRepository).save(captor.capture());
		assertEquals("자료구조", captor.getValue().getSubjectName());
		assertEquals("자료구조", response.subjectName());
		assertEquals(DayOfWeek.MONDAY, response.dayOfWeek());
		assertEquals(LocalTime.of(9, 0), response.startTime());
		assertEquals(LocalTime.of(10, 30), response.endTime());
	}

	@Test
	void 사용자가_없으면_시간표를_생성할_수_없다() {
		when(userRepository.findById(1L)).thenReturn(Optional.empty());

		UserException exception = assertThrows(
			UserException.class,
			() -> timetableSlotService.create(createRequest(), 1L)
		);

		assertEquals(ExceptionCode.NOT_FOUND_USER, exception.getExceptionCode());
		verify(slotRepository, never()).save(any());
	}

	@Test
	void 겹치는_시간표가_있으면_시간표를_생성할_수_없다() {
		User user = user();
		TimetableSlotRequest.Create request = createRequest();
		when(userRepository.findById(1L)).thenReturn(Optional.of(user));
		when(slotRepository.existsOverlappingSlot(1L, request.dayOfWeek(), request.startTime(), request.endTime()))
			.thenReturn(true);

		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> timetableSlotService.create(request, 1L)
		);

		assertEquals(ExceptionCode.CONFLICT_TIMETABLE_SLOT, exception.getExceptionCode());
		verify(slotRepository, never()).save(any());
	}

	@Test
	void 시간표를_단건_조회한다() {
		TimetableSlot slot = slot("자료구조", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30));
		when(slotRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(slot));

		TimetableSlotResponse.Read response = timetableSlotService.read(10L, 1L);

		assertEquals("자료구조", response.subjectName());
		assertEquals(DayOfWeek.MONDAY, response.dayOfWeek());
	}

	@Test
	void 소유한_시간표가_없으면_NOT_FOUND_TIMESLOT을_던진다() {
		when(slotRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.empty());

		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> timetableSlotService.read(10L, 1L)
		);

		assertEquals(ExceptionCode.NOT_FOUND_TIMESLOT, exception.getExceptionCode());
	}

	@Test
	void 시간표_목록을_조회한다() {
		when(slotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(1L))
			.thenReturn(List.of(
				slot("자료구조", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30)),
				slot("운영체제", DayOfWeek.TUESDAY, LocalTime.of(13, 0), LocalTime.of(14, 30))
			));

		List<TimetableSlotResponse.Read> responses = timetableSlotService.readAll(1L);

		assertEquals(2, responses.size());
		assertEquals("자료구조", responses.get(0).subjectName());
		assertEquals("운영체제", responses.get(1).subjectName());
	}

	@Test
	void 시간표를_수정하면_엔티티_상태가_변경된다() {
		TimetableSlot slot = slot("자료구조", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30));
		TimetableSlotRequest.Update request = updateRequest();
		when(slotRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(slot));
		when(slotRepository.existsOverlappingSlotExcept(1L, 10L, request.dayOfWeek(), request.startTime(), request.endTime()))
			.thenReturn(false);

		TimetableSlotResponse.Read response = timetableSlotService.update(10L, request, 1L);

		assertEquals("운영체제", slot.getSubjectName());
		assertEquals(DayOfWeek.TUESDAY, slot.getDayOfWeek());
		assertEquals("운영체제", response.subjectName());
		assertEquals(DayOfWeek.TUESDAY, response.dayOfWeek());
	}

	@Test
	void 겹치는_시간표가_있으면_시간표를_수정할_수_없다() {
		TimetableSlot slot = slot("자료구조", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30));
		TimetableSlotRequest.Update request = updateRequest();
		when(slotRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(slot));
		when(slotRepository.existsOverlappingSlotExcept(1L, 10L, request.dayOfWeek(), request.startTime(), request.endTime()))
			.thenReturn(true);

		TimetableSlotException exception = assertThrows(
			TimetableSlotException.class,
			() -> timetableSlotService.update(10L, request, 1L)
		);

		assertEquals(ExceptionCode.CONFLICT_TIMETABLE_SLOT, exception.getExceptionCode());
	}

	@Test
	void 시간표를_삭제한다() {
		TimetableSlot slot = slot("자료구조", DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(10, 30));
		when(slotRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(slot));

		timetableSlotService.delete(10L, 1L);

		verify(slotRepository).delete(slot);
	}

	private TimetableSlotRequest.Create createRequest() {
		return new TimetableSlotRequest.Create(
			"자료구조",
			DayOfWeek.MONDAY,
			"공학관 101",
			LocalTime.of(9, 0),
			LocalTime.of(10, 30)
		);
	}

	private TimetableSlotRequest.Update updateRequest() {
		return new TimetableSlotRequest.Update(
			"운영체제",
			DayOfWeek.TUESDAY,
			"공학관 202",
			LocalTime.of(13, 0),
			LocalTime.of(14, 30)
		);
	}

	private TimetableSlot slot(String subjectName, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
		return TimetableSlot.create(
			user(),
			subjectName,
			dayOfWeek,
			"공학관",
			startTime,
			endTime
		);
	}

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
