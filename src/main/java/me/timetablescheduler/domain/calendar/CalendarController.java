package me.timetablescheduler.domain.calendar;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.calendar.dto.GoogleAuthUrlResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/calendar")
public class CalendarController {

	private final CalendarService calendarService;

	@GetMapping("/oauth/url")
	public GoogleAuthUrlResponse createAuthUrl(
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return calendarService.createAuthUrl(userDetails.getId());
	}

	@GetMapping("/oauth/callback")
	public String callback(
		@RequestParam String code,
		@RequestParam String state
	) {
		Long userId = Long.valueOf(state);
		calendarService.connect(code, userId);

		return "Google Calendar 연동이 완료되었습니다. 창을 닫아도 됩니다.";
	}
}
