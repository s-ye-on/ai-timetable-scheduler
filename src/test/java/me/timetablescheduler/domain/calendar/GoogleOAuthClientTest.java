package me.timetablescheduler.domain.calendar;

import me.timetablescheduler.domain.calendar.external.GoogleCalendarProperties;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleOAuthClientTest {

	@Test
	void 인증_URL은_네트워크_호출_없이_불투명한_state와_기존_Google_옵션을_유지한다() {
		var properties = new GoogleCalendarProperties("test-client", "test-only-client-secret",
			"http://localhost/callback", "https://www.googleapis.com/auth/calendar", "test-app", "Asia/Seoul");
		String state = "opaque-test-request-state";
		String url = new GoogleOAuthClient(properties).createAuthorizationUrl(state);
		var query = UriComponentsBuilder.fromUriString(url).build().getQueryParams();
		assertThat(query.getFirst("state")).isEqualTo(state);
		assertThat(query.getFirst("access_type")).isEqualTo("offline");
		assertThat(query.getFirst("prompt")).isEqualTo("consent");
		assertThat(query.getFirst("response_type")).isEqualTo("code");
		assertThat(url).doesNotContain(properties.clientSecret());
	}
}
