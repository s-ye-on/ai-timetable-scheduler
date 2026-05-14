package me.timetablescheduler.domain.preference.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.preference.dto.PreferenceRequest;
import me.timetablescheduler.domain.preference.dto.PreferenceResponse;
import me.timetablescheduler.domain.preference.service.PreferenceService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/preferences")
public class PreferenceController {
	private final PreferenceService preferenceService;

	@GetMapping
	public PreferenceResponse.Read read(@AuthenticationPrincipal CustomUserDetails userDetails) {
		return preferenceService.read(userDetails.getId());
	}

	@PutMapping
	public PreferenceResponse.Read update(
		@AuthenticationPrincipal CustomUserDetails userDetails,
		@Valid @RequestBody PreferenceRequest.Update request
	) {
		return preferenceService.update(userDetails.getId(), request);
	}

	@DeleteMapping
	public PreferenceResponse.Read resetToDefault(@AuthenticationPrincipal CustomUserDetails userDetails) {
		return preferenceService.resetToDefault(userDetails.getId());
	}
}
