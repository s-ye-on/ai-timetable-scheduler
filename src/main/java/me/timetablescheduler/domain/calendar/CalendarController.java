package me.timetablescheduler.domain.calendar;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.calendar.dto.CalendarStatusResponse;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.calendar.dto.GoogleAuthUrlResponse;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
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
	private final GoogleOAuthStateService oauthStateService;

	@GetMapping("/oauth/url")
	public GoogleAuthUrlResponse createAuthUrl(
		@AuthenticationPrincipal CustomUserDetails userDetails,
		HttpServletRequest request
	) {
		HttpSession session = request.getSession();
		request.changeSessionId();
		return calendarService.createAuthUrl(oauthStateService.issue(session, userDetails.getId()));
	}

	@GetMapping("/status")
	public CalendarStatusResponse status(
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return new CalendarStatusResponse(calendarService.isConnected(userDetails.getId()));
	}

	@GetMapping("/oauth/callback")
	public String callback(
		@RequestParam(required = false) String code,
		@RequestParam(required = false) String state,
		@RequestParam(required = false) String error,
		HttpServletRequest request
	) {
		Long userId = oauthStateService.consume(request.getSession(false), state);
		if (error != null || code == null || code.isBlank()) {
			throw new CalendarException(ExceptionCode.GOOGLE_OAUTH_AUTHORIZATION_FAILED);
		}
		calendarService.connect(code, userId);

		return "Google Calendar 연동이 완료되었습니다. 창을 닫아도 됩니다.";
	}
}
