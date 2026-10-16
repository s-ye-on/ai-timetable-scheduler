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
	void rejectsMissingJwtSecretAtStartup() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(null);

		assertInvalidSecret(properties);
	}

	@Test
	void rejectsBlankAndTooShortJwtSecretsAtStartup() {
		JwtProperties blankSecret = new JwtProperties();
		blankSecret.setSecret("   ");

		JwtProperties shortSecret = new JwtProperties();
		shortSecret.setSecret("short-secret");

		assertInvalidSecret(blankSecret);
		assertInvalidSecret(shortSecret);
	}

	@Test
	void rejectsChangeMePlaceholderAtStartup() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret("change-me-" + "x".repeat(32));

		assertInvalidSecret(properties);
	}

	@Test
	void issuesAndParsesAccessAndRefreshTokensWithConfiguredSecret() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(randomAsciiSecret());
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
	void rejectsTamperedTokensDifferentSigningKeysAndWrongTokenTypes() {
		JwtProperties properties = propertiesWithRandomSecret();
		JwtTokenService tokenService = new JwtTokenService(properties, FIXED_CLOCK);
		String accessToken = tokenService.generateAccessToken("alice@example.com");

		assertTokenFailure(() -> tokenService.parseAccessToken(tamperSignature(accessToken)), ExceptionCode.TOKEN_INVALID);
		assertTokenFailure(
			() -> new JwtTokenService(propertiesWithRandomSecret(), FIXED_CLOCK).parseAccessToken(accessToken),
			ExceptionCode.TOKEN_INVALID
		);
		assertTokenFailure(() -> tokenService.parseRefreshToken(accessToken), ExceptionCode.TOKEN_INVALID);
	}

	@Test
	void rejectsExpiredAccessTokenUsingTheConfiguredClock() {
		JwtProperties properties = propertiesWithRandomSecret();
		properties.setAccessTokenTtlSeconds(60);
		JwtTokenService issuingService = new JwtTokenService(properties, FIXED_CLOCK);
		String accessToken = issuingService.generateAccessToken("alice@example.com");
		Clock expiredClock = Clock.fixed(FIXED_CLOCK.instant().plusSeconds(61), ZoneOffset.UTC);
		JwtTokenService validatingService = new JwtTokenService(properties, expiredClock);

		assertTokenFailure(() -> validatingService.parseAccessToken(accessToken), ExceptionCode.TOKEN_EXPIRED);
	}

	private void assertInvalidSecret(JwtProperties properties) {
		assertThatThrownBy(() -> new JwtTokenService(properties, FIXED_CLOCK))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("안전한 JWT signing secret 설정이 필요합니다.");
	}

	private JwtProperties propertiesWithRandomSecret() {
		JwtProperties properties = new JwtProperties();
		properties.setSecret(randomAsciiSecret());
		return properties;
	}

	private void assertTokenFailure(Runnable action, ExceptionCode expectedCode) {
		assertThatThrownBy(action::run)
			.isInstanceOf(AuthException.class)
			.satisfies(exception -> assertThat(((AuthException) exception).getExceptionCode()).isEqualTo(expectedCode));
	}

	private String tamperSignature(String token) {
		String[] parts = token.split("\\.");
		String signature = parts[2];
		char replacement = signature.charAt(0) == 'A' ? 'B' : 'A';
		return parts[0] + "." + parts[1] + "." + replacement + signature.substring(1);
	}

	private String randomAsciiSecret() {
		String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
		SecureRandom secureRandom = new SecureRandom();
		StringBuilder secret = new StringBuilder(32);
		for (int index = 0; index < 32; index++) {
			secret.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
		}
		return secret.toString();
	}
}
