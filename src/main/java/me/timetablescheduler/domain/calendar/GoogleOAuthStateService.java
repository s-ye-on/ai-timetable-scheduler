package me.timetablescheduler.domain.calendar;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GoogleOAuthStateService {

	private static final String SESSION_ATTRIBUTE = GoogleOAuthStateService.class.getName() + ".pending";
	private static final Duration VALIDITY = Duration.ofMinutes(5);
	private final Clock clock;
	private final SecureRandom random = new SecureRandom();

	public String issue(HttpSession session, Long userId) {
		byte[] bytes = new byte[32];
		random.nextBytes(bytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		session.setAttribute(SESSION_ATTRIBUTE, new PendingState(state, userId, clock.instant().plus(VALIDITY)));
		return state;
	}

	public Long consume(HttpSession session, String state) {
		if (session == null || state == null || state.length() != 43
			|| !(session.getAttribute(SESSION_ATTRIBUTE) instanceof PendingState pending)
			|| !MessageDigest.isEqual(pending.state.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))
			|| !clock.instant().isBefore(pending.expiresAt)
			|| !pending.consumed.compareAndSet(false, true)) {
			throw new CalendarException(ExceptionCode.INVALID_GOOGLE_OAUTH_STATE);
		}
		// 소비된 객체를 유지해, 이전 callback이 새로 발급한 state를 제거하지 않도록 한다.
		return pending.userId;
	}

	private static final class PendingState implements Serializable {
		private final String state;
		private final Long userId;
		private final Instant expiresAt;
		private final AtomicBoolean consumed = new AtomicBoolean();

		private PendingState(String state, Long userId, Instant expiresAt) {
			this.state = state;
			this.userId = userId;
			this.expiresAt = expiresAt;
		}
	}
}
