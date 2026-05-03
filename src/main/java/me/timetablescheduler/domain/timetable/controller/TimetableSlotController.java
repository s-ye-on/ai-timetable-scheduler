package me.timetablescheduler.domain.timetable.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotResponse;
import me.timetablescheduler.domain.timetable.service.TimetableSlotService;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/timetable-slots")
public class TimetableSlotController {
	private final TimetableSlotService timetableSlotService;

	@GetMapping
	public List<TimetableSlotResponse.Read> readAll(@AuthenticationPrincipal CustomUserDetails userDetails) {
		return timetableSlotService.readAll(userDetails.getId());
	}

	@GetMapping("/{timetableSlotId}")
	public TimetableSlotResponse.Read read(@PathVariable Long timetableSlotId,
	                                       @AuthenticationPrincipal CustomUserDetails userDetails) {
		return timetableSlotService.read(timetableSlotId, userDetails.getId());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TimetableSlotResponse.Read create(
		@Valid @RequestBody TimetableSlotRequest.Create request,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return timetableSlotService.create(request, userDetails.getId());
	}

	@PutMapping("/{timetableSlotId}")
	public TimetableSlotResponse.Read update(
		@PathVariable Long timetableSlotId,
		@Valid @RequestBody TimetableSlotRequest.Update request,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return timetableSlotService.update(timetableSlotId, request, userDetails.getId());
	}

	@DeleteMapping("/{timetableSlotId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
		@PathVariable Long timetableSlotId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		timetableSlotService.delete(timetableSlotId, userDetails.getId());
	}
}
