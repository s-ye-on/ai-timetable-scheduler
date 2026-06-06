package me.timetablescheduler.domain.calendar;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.calendar.dto.GoogleAuthUrlResponse;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CalendarService {

	private final CalendarTokenRepository calendarTokenRepository;
	private final UserReader userReader;
	private final GoogleOAuthClient googleOAuthClient;
	private final GoogleCalendarClient googleCalendarClient;
	private final Clock clock;

	public GoogleAuthUrlResponse createAuthUrl(Long userId) {
		return new GoogleAuthUrlResponse(googleOAuthClient.createAuthorizationUrl(userId));
	}

	@Transactional
	public void connect(String code, Long userId) {
		User user = userReader.read(userId);
		GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCode(code);
		LocalDateTime expiresAt = now().plusSeconds(tokenResponse.getExpiresInSeconds());

		CalendarToken token = calendarTokenRepository.findByUserId(userId)
			.orElseGet(() -> CalendarToken.create(
				user,
				tokenResponse.getAccessToken(),
				tokenResponse.getRefreshToken(),
				expiresAt
			));

		token.updateToken(
			tokenResponse.getAccessToken(),
			tokenResponse.getRefreshToken(),
			expiresAt
		);

		calendarTokenRepository.save(token);
	}

	@Transactional
	public List<BusyBlock> getCalendarBusyBlocks(
		Long userId,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		CalendarToken token = getValidToken(userId);

		return googleCalendarClient.getBusyBlocks(
			token.getAccessToken(),
			token.getCalendarId(),
			startAt,
			endAt
		);
	}

	@Transactional
	public String createEvent(
		Long userId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		CalendarToken token = getValidToken(userId);

		return googleCalendarClient.createEvent(
			token.getAccessToken(),
			token.getCalendarId(),
			title,
			description,
			startAt,
			endAt
		);
	}

	private CalendarToken getValidToken(Long userId) {
		CalendarToken token = calendarTokenRepository.findByUserId(userId)
			.orElseThrow(() -> new CalendarException(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED));

		if (!token.isExpired(now())) {
			return token;
		}

		TokenResponse tokenResponse = googleOAuthClient.refreshAccessToken(token.getRefreshToken());

		token.updateToken(
			tokenResponse.getAccessToken(),
			null,
			now().plusSeconds(tokenResponse.getExpiresInSeconds())
		);

		return token;
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
