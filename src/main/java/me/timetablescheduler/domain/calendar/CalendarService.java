package me.timetablescheduler.domain.calendar;

import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.calendar.dto.GoogleAuthUrlResponse;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
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
	private final RecommendationRepository recommendationRepository;

	public GoogleAuthUrlResponse createAuthUrl(String state) {
		return new GoogleAuthUrlResponse(googleOAuthClient.createAuthorizationUrl(state));
	}

	public boolean isConnected(Long userId) {
		return calendarTokenRepository.existsByUserId(userId);
	}

	@Transactional
	public void connect(String code, Long userId) {
		User user = userReader.readForUpdate(userId);
		// primary는 인증 계정에 따라 달라진다. 미완료 요청을 다른 계정에 재생성하지 않는다.
		if (recommendationRepository.existsByUserIdAndStatusIn(userId, List.of(
			RecommendationStatus.SELECTED,
			RecommendationStatus.SYNC_FAILED))) {
			throw new CalendarException(ExceptionCode.GOOGLE_CALENDAR_RECONNECT_BLOCKED);
		}
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

	@Transactional(noRollbackFor = CalendarException.class)
	public List<BusyBlock> getCalendarBusyBlocks(
		Long userId,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		CalendarToken token = getValidToken(userId);

		return getCalendarBusyBlocksWithToken(token, startAt, endAt);
	}

	@Transactional
	public List<BusyBlock> getCalendarBusyBlocksOrEmpty(
		Long userId,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		Optional<CalendarToken> tokenOptional = calendarTokenRepository.findByUserId(userId);

		if (tokenOptional.isEmpty()) {
			return List.of();
		}

		return getCalendarBusyBlocksWithToken(tokenOptional.get(), startAt, endAt);
	}

	private List<BusyBlock> getCalendarBusyBlocksWithToken(
		CalendarToken token,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		CalendarToken validToken = refreshIfExpired(token);

		return googleCalendarClient.getBusyBlocks(
			validToken.getAccessToken(),
			validToken.getCalendarId(),
			startAt,
			endAt
		);
	}

	@Transactional(noRollbackFor = CalendarException.class)
	public String createEvent(
		Long userId,
		String eventId,
		String title,
		String description,
		LocalDateTime startAt,
		LocalDateTime endAt
	) {
		CalendarToken token = getValidToken(userId);

		return googleCalendarClient.createEvent(
			token.getAccessToken(),
			token.getCalendarId(),
			eventId,
			title,
			description,
			startAt,
			endAt
		);
	}

	@Transactional(noRollbackFor = CalendarException.class)
	public boolean hasMatchingEvent(Long userId, String eventId, String title, String description,
		LocalDateTime startAt, LocalDateTime endAt) {
		CalendarToken token = getValidToken(userId);
		return googleCalendarClient.hasMatchingEvent(token.getAccessToken(), token.getCalendarId(),
			eventId, title, description, startAt, endAt);
	}

	private CalendarToken getValidToken(Long userId) {
		CalendarToken token = calendarTokenRepository.findByUserId(userId)
			.orElseThrow(() -> new CalendarException(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED));

		return refreshIfExpired(token);
	}

	private CalendarToken refreshIfExpired(CalendarToken token) {
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
