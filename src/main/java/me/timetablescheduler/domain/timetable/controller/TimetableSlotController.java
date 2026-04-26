package me.timetablescheduler.domain.timetable.controller;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotResponse;
import me.timetablescheduler.domain.timetable.service.TimetableSlotService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/timetable-slots")
public class TimetableSlotController {
	private final TimetableSlotService timetableSlotService;

	@GetMapping("/{timetableSlotId}")
	public TimetableSlotResponse.Read read(@PathVariable Long timetableSlotId,
	                                       @AuthenticationPrincipal CustomUserDetails userDetails) {
		return timetableSlotService.read(timetableSlotId, userDetails.getId());
	}
}
