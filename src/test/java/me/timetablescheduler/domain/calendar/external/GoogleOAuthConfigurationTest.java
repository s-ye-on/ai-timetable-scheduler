package me.timetablescheduler.domain.calendar.external;

import java.util.stream.Stream;

import me.timetablescheduler.global.exception.CalendarException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GoogleOAuthConfigurationTest {

	@ParameterizedTest
	@MethodSource("invalidSettings")
	void invalidSettingsAreRejectedBeforeOpeningGoogleConsent(String clientId, String secret, String redirectUri) {
		var properties = new GoogleCalendarProperties(clientId, secret, redirectUri, "calendar-scope", "test-app", "Asia/Seoul");
		assertThatThrownBy(() -> new GoogleOAuthClient(properties).createAuthorizationUrl("test-state"))
			.isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode().name())
				.isEqualTo("GOOGLE_OAUTH_CONFIGURATION_INVALID"));
	}

	@Test
	void unresolvedSecretPlaceholderCanBindButMustNotReachGoogle() {
		new ApplicationContextRunner().withUserConfiguration(PropertiesOnly.class)
			.withPropertyValues("google.calendar.client-id=test-client",
				"google.calendar.client-secret=${MISSING_TEST_OAUTH_SECRET}",
				"google.calendar.redirect-uri=http://localhost/callback", "google.calendar.scope=calendar-scope")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var properties = context.getBean(GoogleCalendarProperties.class);
				assertThat(properties.clientSecret()).isEqualTo("${MISSING_TEST_OAUTH_SECRET}");
				assertThatThrownBy(() -> new GoogleOAuthClient(properties).createAuthorizationUrl("test-state"))
					.isInstanceOf(CalendarException.class);
			});
	}

	static Stream<Arguments> invalidSettings() {
		return Stream.of(null, " ", "${MISSING_TEST_OAUTH_SETTING}").flatMap(invalid -> Stream.of(
			Arguments.of(invalid, "test-only-secret", "http://localhost/callback"),
			Arguments.of("test-client", invalid, "http://localhost/callback"),
			Arguments.of("test-client", "test-only-secret", invalid)
		));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(GoogleCalendarProperties.class)
	static class PropertiesOnly { }
}
