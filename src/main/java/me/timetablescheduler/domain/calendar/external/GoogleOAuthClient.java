package me.timetablescheduler.domain.calendar.external;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.auth.oauth2.TokenResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.extern.slf4j.Slf4j;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
@Slf4j
public class GoogleOAuthClient {

	private static final String ACCESS_TYPE_OFFLINE = "offline";
	private static final String PROMPT_CONSENT = "consent";
	private static final Set<String> SAFE_OAUTH_ERRORS = Set.of(
		"invalid_client", "invalid_grant", "invalid_request", "unauthorized_client",
		"unsupported_grant_type", "invalid_scope", "redirect_uri_mismatch", "access_denied",
		"server_error", "temporarily_unavailable", "invalid_rapt"
	);

	private final GoogleCalendarProperties properties;
	private final HttpTransport transport;

	@Autowired
	public GoogleOAuthClient(GoogleCalendarProperties properties) {
		this(properties, new NetHttpTransport());
	}

	GoogleOAuthClient(GoogleCalendarProperties properties, HttpTransport transport) {
		this.properties = properties;
		this.transport = transport;
	}

	public String createAuthorizationUrl(String state) {
		AuthorizationCodeFlow flow = createFlow();

		AuthorizationCodeRequestUrl url = flow.newAuthorizationUrl()
			.setRedirectUri(properties.redirectUri())
			.setState(state);

		return url
			.set("access_type", ACCESS_TYPE_OFFLINE)
			.set("prompt", PROMPT_CONSENT)
			.build();
	}

	public GoogleTokenResponse exchangeCode(String code) {
		try {
			var request = createFlow()
				.newTokenRequest(code)
				.setRedirectUri(properties.redirectUri());
			request.setRequestInitializer(http -> {
				http.setLoggingEnabled(false);
				http.setCurlLoggingEnabled(false);
			});
			return request.execute();
		} catch (TokenResponseException e) {
			String error = e.getDetails() == null ? null : e.getDetails().getError();
			String safeError = error != null && SAFE_OAUTH_ERRORS.contains(error) ? error : "unknown";
			log.warn("Google OAuth 토큰 교환 실패: httpStatus={}, error={}", e.getStatusCode(), safeError);
			throw new CalendarException(ExceptionCode.GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED);
		} catch (IOException e) {
			log.warn("Google OAuth 토큰 교환 실패: httpStatus=none, error=transport_error");
			throw new CalendarException(ExceptionCode.GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED);
		}
	}

	public TokenResponse refreshAccessToken(String refreshToken) {
		try {
			return new GoogleRefreshTokenRequest(
				new NetHttpTransport(),
				GsonFactory.getDefaultInstance(),
				refreshToken,
				properties.clientId(),
				properties.clientSecret()
			).execute();
		} catch (IOException e) {
			throw new CalendarException(ExceptionCode.GOOGLE_OAUTH_TOKEN_REFRESH_FAILED);
		}
	}

	private GoogleAuthorizationCodeFlow createFlow() {
		validateSetting("google.calendar.client-id", properties.clientId());
		validateSetting("google.calendar.client-secret", properties.clientSecret());
		validateSetting("google.calendar.redirect-uri", properties.redirectUri());
		return new GoogleAuthorizationCodeFlow.Builder(
			transport,
			GsonFactory.getDefaultInstance(),
			properties.clientId(),
			properties.clientSecret(),
			List.of(properties.scope())
		).build();
	}

	private void validateSetting(String name, String value) {
		if (value == null || value.isBlank() || value.contains("${")) {
			log.warn("Google OAuth 설정 오류: field={}", name);
			throw new CalendarException(ExceptionCode.GOOGLE_OAUTH_CONFIGURATION_INVALID);
		}
	}
}
