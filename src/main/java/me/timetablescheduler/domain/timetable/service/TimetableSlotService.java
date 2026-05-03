package me.timetablescheduler.domain.timetable.service;

import java.util.List;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotRequest;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TimetableSlotException;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotService {
	private final TimetableSlotRepository slotRepository;
	private final UserRepository userRepository;

	@Transactional
	public TimetableSlotResponse.Read create(TimetableSlotRequest.Create request, Long userId) {
		User user = findUser(userId);
		validateNoOverlap(userId, request.dayOfWeek(), request.startTime(), request.endTime());

		TimetableSlot timetableSlot = TimetableSlot.create(
			user,
			request.subjectName(),
			request.dayOfWeek(),
			request.location(),
			request.startTime(),
			request.endTime()
		);

		return toReadResponse(slotRepository.save(timetableSlot));
	}

	public TimetableSlotResponse.Read read(Long slotId, Long userId) {

		TimetableSlot timetableSlot = findSlot(slotId, userId);

		return toReadResponse(timetableSlot);
	}

	public List<TimetableSlotResponse.Read> readAll(Long userId) {
		return slotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(userId)
			.stream()
			.map(this::toReadResponse)
			.toList();
	}

	@Transactional
	public TimetableSlotResponse.Read update(Long slotId, TimetableSlotRequest.Update request, Long userId) {
		TimetableSlot timetableSlot = findSlot(slotId, userId);
		validateNoOverlapExcept(slotId, userId, request.dayOfWeek(), request.startTime(), request.endTime());

		timetableSlot.update(
			request.subjectName(),
			request.dayOfWeek(),
			request.location(),
			request.startTime(),
			request.endTime()
		);

		return toReadResponse(timetableSlot);
	}

	@Transactional
	public void delete(Long slotId, Long userId) {
		TimetableSlot timetableSlot = findSlot(slotId, userId);
		slotRepository.delete(timetableSlot);
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}

	private TimetableSlot findSlot(Long slotId, Long userId) {
		return slotRepository.findByIdAndUserId(slotId, userId)
			.orElseThrow(() -> new TimetableSlotException(ExceptionCode.NOT_FOUND_TIMESLOT));
	}

	private void validateNoOverlap(
		Long userId,
		java.time.DayOfWeek dayOfWeek,
		java.time.LocalTime startTime,
		java.time.LocalTime endTime
	) {
		if (slotRepository.existsOverlappingSlot(userId, dayOfWeek, startTime, endTime)) {
			throw new TimetableSlotException(ExceptionCode.CONFLICT_TIMETABLE_SLOT);
		}
	}

	private void validateNoOverlapExcept(
		Long slotId,
		Long userId,
		java.time.DayOfWeek dayOfWeek,
		java.time.LocalTime startTime,
		java.time.LocalTime endTime
	) {
		if (slotRepository.existsOverlappingSlotExcept(userId, slotId, dayOfWeek, startTime, endTime)) {
			throw new TimetableSlotException(ExceptionCode.CONFLICT_TIMETABLE_SLOT);
		}
	}

	private TimetableSlotResponse.Read toReadResponse(TimetableSlot timetableSlot) {
		return new TimetableSlotResponse.Read(
			timetableSlot.getId(),
			timetableSlot.getSubjectName(),
			timetableSlot.getDayOfWeek(),
			timetableSlot.getLocation(),
			timetableSlot.getStartTime(),
			timetableSlot.getEndTime()
		);
	}
}
