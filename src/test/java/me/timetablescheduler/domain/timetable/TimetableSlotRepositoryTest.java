package me.timetablescheduler.domain.timetable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

@DataJpaTest
class TimetableSlotRepositoryTest {

	@Autowired
	private TimetableSlotRepository timetableSlotRepository;

	@Autowired
	private UserRepository userRepository;

	@Test
	void 사용자_ID와_시간표_ID로_본인_시간표만_조회한다() {
		User owner = userRepository.save(user("owner@example.com"));
		User other = userRepository.save(user("other@example.com"));
		TimetableSlot slot = timetableSlotRepository.save(slot(owner, "자료구조", DayOfWeek.MONDAY, 9, 10));

		assertTrue(timetableSlotRepository.findByIdAndUserId(slot.getId(), owner.getId()).isPresent());
		assertFalse(timetableSlotRepository.findByIdAndUserId(slot.getId(), other.getId()).isPresent());
	}

	@Test
	void 사용자별_시간표_목록을_요일과_시작시간_순서로_조회한다() {
		User user = userRepository.save(user("owner@example.com"));
		User other = userRepository.save(user("other@example.com"));
		timetableSlotRepository.save(slot(user, "화요일 오후 수업", DayOfWeek.TUESDAY, 13, 14));
		timetableSlotRepository.save(slot(user, "월요일 오전 수업", DayOfWeek.MONDAY, 9, 10));
		timetableSlotRepository.save(slot(user, "월요일 오후 수업", DayOfWeek.MONDAY, 13, 14));
		timetableSlotRepository.save(slot(other, "다른 사용자 수업", DayOfWeek.MONDAY, 8, 9));

		List<TimetableSlot> slots = timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(user.getId());

		assertEquals(3, slots.size());
		assertEquals("월요일 오전 수업", slots.get(0).getSubjectName());
		assertEquals("월요일 오후 수업", slots.get(1).getSubjectName());
		assertEquals("화요일 오후 수업", slots.get(2).getSubjectName());
	}

	@Test
	void 같은_요일의_시간이_겹치면_true를_반환한다() {
		User user = userRepository.save(user("owner@example.com"));
		timetableSlotRepository.save(slot(user, "자료구조", DayOfWeek.MONDAY, 9, 11));

		boolean exists = timetableSlotRepository.existsOverlappingSlot(
			user.getId(),
			DayOfWeek.MONDAY,
			LocalTime.of(10, 0),
			LocalTime.of(12, 0)
		);

		assertTrue(exists);
	}

	@Test
	void 같은_요일이어도_시간이_맞닿기만_하면_false를_반환한다() {
		User user = userRepository.save(user("owner@example.com"));
		timetableSlotRepository.save(slot(user, "자료구조", DayOfWeek.MONDAY, 9, 10));

		boolean exists = timetableSlotRepository.existsOverlappingSlot(
			user.getId(),
			DayOfWeek.MONDAY,
			LocalTime.of(10, 0),
			LocalTime.of(11, 0)
		);

		assertFalse(exists);
	}

	@Test
	void 다른_요일의_시간이_같으면_false를_반환한다() {
		User user = userRepository.save(user("owner@example.com"));
		timetableSlotRepository.save(slot(user, "자료구조", DayOfWeek.MONDAY, 9, 11));

		boolean exists = timetableSlotRepository.existsOverlappingSlot(
			user.getId(),
			DayOfWeek.TUESDAY,
			LocalTime.of(10, 0),
			LocalTime.of(12, 0)
		);

		assertFalse(exists);
	}

	@Test
	void 제외할_시간표는_겹침_검사에서_무시한다() {
		User user = userRepository.save(user("owner@example.com"));
		TimetableSlot slot = timetableSlotRepository.save(slot(user, "자료구조", DayOfWeek.MONDAY, 9, 11));

		boolean exists = timetableSlotRepository.existsOverlappingSlotExcept(
			user.getId(),
			slot.getId(),
			DayOfWeek.MONDAY,
			LocalTime.of(9, 0),
			LocalTime.of(11, 0)
		);

		assertFalse(exists);
	}

	@Test
	void 제외한_시간표_외에_겹치는_시간표가_있으면_true를_반환한다() {
		User user = userRepository.save(user("owner@example.com"));
		TimetableSlot excluded = timetableSlotRepository.save(slot(user, "자료구조", DayOfWeek.MONDAY, 9, 10));
		timetableSlotRepository.save(slot(user, "알고리즘", DayOfWeek.MONDAY, 10, 12));

		boolean exists = timetableSlotRepository.existsOverlappingSlotExcept(
			user.getId(),
			excluded.getId(),
			DayOfWeek.MONDAY,
			LocalTime.of(10, 30),
			LocalTime.of(11, 30)
		);

		assertTrue(exists);
	}

	private TimetableSlot slot(User user, String subjectName, DayOfWeek dayOfWeek, int startHour, int endHour) {
		return TimetableSlot.create(
			user,
			subjectName,
			dayOfWeek,
			"공학관",
			LocalTime.of(startHour, 0),
			LocalTime.of(endHour, 0)
		);
	}

	private User user(String email) {
		return new User("Tester", email, "encodedPassword");
	}
}
