package me.timetablescheduler.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import me.timetablescheduler.config.JwtProperties;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;

class JwtTokenServiceTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

	@Test
	void 시작할_때_JWT_비밀키가_없으면_거절한다() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(null);

		유효하지_않은_비밀키_검증(properties);
	}

	@Test
	void 시작할_때_JWT_비밀키가_공백이거나_너무_짧으면_거절한다() {
		JwtProperties blankSecret = new JwtProperties();
		blankSecret.setSecret("   ");

		JwtProperties shortSecret = new JwtProperties();
		shortSecret.setSecret("short-secret");

		유효하지_않은_비밀키_검증(blankSecret);
		유효하지_않은_비밀키_검증(shortSecret);
	}

	@Test
	void 시작할_때_기본_자리표시자_비밀키를_거절한다() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret("change-me-" + "x".repeat(32));

		유효하지_않은_비밀키_검증(properties);
	}

	@Test
	void 설정된_비밀키로_액세스_토큰과_리프레시_토큰을_발급하고_해석한다() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(무작위_ASCII_비밀키_생성());
		JwtTokenService tokenService = new JwtTokenService(properties, FIXED_CLOCK);

		String accessToken = tokenService.generateAccessToken("alice@example.com");
		String refreshToken = tokenService.generateRefreshToken("alice@example.com");

		assertThat(tokenService.parseAccessToken(accessToken))
			.extracting(JwtTokenService.TokenClaims::subject, JwtTokenService.TokenClaims::tokenType)
			.containsExactly("alice@example.com", "access");
		assertThat(tokenService.parseRefreshToken(refreshToken))
			.extracting(JwtTokenService.TokenClaims::subject, JwtTokenService.TokenClaims::tokenType)
			.containsExactly("alice@example.com", "refresh");
	}

	@Test
	void 변조된_토큰과_다른_서명키_및_잘못된_토큰_유형을_거절한다() {
		JwtProperties properties = 무작위_비밀키_설정_생성();
		JwtTokenService tokenService = new JwtTokenService(properties, FIXED_CLOCK);
		String accessToken = tokenService.generateAccessToken("alice@example.com");

		토큰_처리_실패_검증(() -> tokenService.parseAccessToken(서명_변조(accessToken)), ExceptionCode.TOKEN_INVALID);
		토큰_처리_실패_검증(
			() -> new JwtTokenService(무작위_비밀키_설정_생성(), FIXED_CLOCK).parseAccessToken(accessToken),
			ExceptionCode.TOKEN_INVALID
		);
		토큰_처리_실패_검증(() -> tokenService.parseRefreshToken(accessToken), ExceptionCode.TOKEN_INVALID);
	}

	@Test
	void 설정된_시계를_기준으로_만료된_액세스_토큰을_거절한다() {
		JwtProperties properties = 무작위_비밀키_설정_생성();
		properties.setAccessTokenTtlSeconds(60);
		JwtTokenService issuingService = new JwtTokenService(properties, FIXED_CLOCK);
		String accessToken = issuingService.generateAccessToken("alice@example.com");
		Clock expiredClock = Clock.fixed(FIXED_CLOCK.instant().plusSeconds(61), ZoneOffset.UTC);
		JwtTokenService validatingService = new JwtTokenService(properties, expiredClock);

		토큰_처리_실패_검증(() -> validatingService.parseAccessToken(accessToken), ExceptionCode.TOKEN_EXPIRED);
	}

	private void 유효하지_않은_비밀키_검증(JwtProperties properties) {
		assertThatThrownBy(() -> new JwtTokenService(properties, FIXED_CLOCK))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("안전한 JWT signing secret 설정이 필요합니다.");
	}

	private JwtProperties 무작위_비밀키_설정_생성() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(무작위_ASCII_비밀키_생성());
		return properties;
	}

	private void 토큰_처리_실패_검증(Runnable action, ExceptionCode expectedCode) {
		assertThatThrownBy(action::run)
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> assertThat(((AuthException) exception).getExceptionCode()).isEqualTo(expectedCode));
	}

	private String 서명_변조(String token) {
		String[] parts = token.split("\\.");
		String signature = parts[2];
		char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
		return parts[0] + "." + parts[1] + "." + replacement + signature.substring(1);
	}

	private String 무작위_ASCII_비밀키_생성() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		SecureRandom secureRandom = new SecureRandom();
		StringBuilder secret = new StringBuilder(32);
		for (int index = 0; index < 32; index++) {
			secret.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
		}
		return secret.toString();
	}
}
