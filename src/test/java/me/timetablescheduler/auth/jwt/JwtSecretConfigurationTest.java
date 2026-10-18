package me.timetablescheduler.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import me.timetablescheduler.config.JwtProperties;

class JwtSecretConfigurationTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneOffset.UTC);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(JwtTokenServiceConfiguration.class);

	@Test
	void JWT_비밀키가_없으면_컨텍스트_시작에_실패한다() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
				.hasRootCauseInstanceOf(IllegalStateException.class)
				.hasRootCauseMessage("안전한 JWT signing secret 설정이 필요합니다.");
		});
	}

	@Test
	void JWT_비밀키가_설정되면_컨텍스트가_시작된다() {
		contextRunner
			.withPropertyValues("app.jwt.secret=" + 무작위_ASCII_비밀키_생성())
			.run(context -> assertThat(context).hasNotFailed());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(JwtProperties.class)
	static class JwtTokenServiceConfiguration {

		@Bean
		Clock clock() {
			return FIXED_CLOCK;
		}

		@Bean
		JwtTokenService jwtTokenService(JwtProperties jwtProperties, Clock clock) {
			return new JwtTokenService(jwtProperties, clock);
		}
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
