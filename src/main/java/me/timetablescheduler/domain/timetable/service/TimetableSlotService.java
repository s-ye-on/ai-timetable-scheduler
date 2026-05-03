package me.timetablescheduler.domain.timetable.service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.timetable.TimeTableSlotRepository;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotResponse;
import me.timetablescheduler.global.dto.TimetableSlotRequest;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TimetableSlotException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableSlotService {
	private final TimeTableSlotRepository slotRepository;

	public void create(TimetableSlotRequest.Create request, Long userId) {

	}

	public TimetableSlotResponse.Read read(Long slotId, Long userId) {

		TimetableSlot timetableSlot = slotRepository.findByIdAndUserId(slotId, userId)
			.orElseThrow(() -> new TimetableSlotException(ExceptionCode.NOT_FOUND_TIMESLOT));

		return new TimetableSlotResponse.Read(
			timetableSlot.getSubjectName(),
			timetableSlot.getDayOfWeek(),
			timetableSlot.getLocation(),
			timetableSlot.getStartTime(),
			timetableSlot.getEndTime()
		);
	}

}
