package me.timetablescheduler.domain.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class GoogleOAuthStateServiceTest {

	@Test
	void 만료_직전의_state는_소비할_수_있다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
		GoogleOAuthStateService service = new GoogleOAuthStateService(clock);
		MockHttpSession session = new MockHttpSession();
		String state = service.issue(session, 1L);

		clock.기준_시각_설정(Instant.parse("2026-10-16T00:04:59.999Z"));

		assertThat(service.consume(session, state)).isEqualTo(1L);
	}

	@Test
	void 만료_정각과_그_이후의_state는_거부한다() {
		MutableClock clock = new MutableClock(Instant.parse("2026-10-16T00:00:00Z"));
		GoogleOAuthStateService service = new GoogleOAuthStateService(clock);
		MockHttpSession expiresAtSession = new MockHttpSession();
		String expiresAtState = service.issue(expiresAtSession, 1L);

		clock.기준_시각_설정(Instant.parse("2026-10-16T00:05:00Z"));
		유효하지_않은_state_검증(() -> service.consume(expiresAtSession, expiresAtState));

		MockHttpSession afterExpirySession = new MockHttpSession();
		String afterExpiryState = service.issue(afterExpirySession, 2L);
		clock.기준_시각_설정(Instant.parse("2026-10-16T00:10:01Z"));
		유효하지_않은_state_검증(() -> service.consume(afterExpirySession, afterExpiryState));
	}

	@Test
	void 누락되거나_변조되었거나_다른_브라우저_session의_state는_거부한다() {
		GoogleOAuthStateService service = new GoogleOAuthStateService(
			Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
		);
		MockHttpSession issuedSession = new MockHttpSession();
		String state = service.issue(issuedSession, 1L);
		String tamperedState = state.substring(0, state.length() - 1)
			+ (state.endsWith("A") ? "B" : "A");

		유효하지_않은_state_검증(() -> service.consume(issuedSession, null));
		유효하지_않은_state_검증(() -> service.consume(issuedSession, tamperedState));
		유효하지_않은_state_검증(() -> service.consume(new MockHttpSession(), state));
		유효하지_않은_state_검증(() -> service.consume(null, state));
	}

	@Test
	void 새_연결_요청은_이전_state를_무효화하고_새_state만_허용한다() {
		GoogleOAuthStateService service = new GoogleOAuthStateService(
			Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
		);
		MockHttpSession session = new MockHttpSession();
		String firstState = service.issue(session, 1L);
		String replacementState = service.issue(session, 1L);

		유효하지_않은_state_검증(() -> service.consume(session, firstState));
		assertThat(service.consume(session, replacementState)).isEqualTo(1L);
	}

	@Test
	void 성공적으로_소비한_state는_재사용할_수_없다() {
		GoogleOAuthStateService service = new GoogleOAuthStateService(
			Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
		);
		MockHttpSession session = new MockHttpSession();
		String state = service.issue(session, 1L);

		assertThat(service.consume(session, state)).isEqualTo(1L);
		유효하지_않은_state_검증(() -> service.consume(session, state));
	}

	@Test
	void 동시에_소비를_시도해도_단_하나만_성공한다() throws Exception {
		GoogleOAuthStateService service = new GoogleOAuthStateService(
			Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC)
		);
		MockHttpSession session = new MockHttpSession();
		String state = service.issue(session, 1L);
		int attempts = 8;
		CountDownLatch ready = new CountDownLatch(attempts);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(attempts);

		try {
			List<Future<Boolean>> results = new ArrayList<>();
			for (int index = 0; index < attempts; index++) {
				results.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return service.consume(session, state).equals(1L);
					} catch (CalendarException exception) {
						return false;
					}
				}));
			}

			assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			long successfulConsumes = 0;
			for (Future<Boolean> result : results) {
				if (result.get(1, TimeUnit.SECONDS)) {
					successfulConsumes++;
				}
			}

			assertThat(successfulConsumes).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private void 유효하지_않은_state_검증(ThrowingRunnable action) {
		assertThatThrownBy(action::실행)
			.isInstanceOf(CalendarException.class)
			.extracting(exception -> ((CalendarException) exception).getExceptionCode())
			.isEqualTo(ExceptionCode.INVALID_GOOGLE_OAUTH_STATE);
	}

	@FunctionalInterface
	private interface ThrowingRunnable {
		void 실행();
	}

	private static final class MutableClock extends Clock {
		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		void 기준_시각_설정(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
