package me.timetablescheduler.domain.calendar.external;

import java.io.IOException;
import java.util.List;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleRefreshTokenRequest;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class GoogleOAuthClient {

	private static final String ACCESS_TYPE_OFFLINE = "offline";
	private static final String PROMPT_CONSENT = "consent";

	private final GoogleCalendarProperties properties;

	public String createAuthorizationUrl(Long userId) {
		AuthorizationCodeFlow flow = createFlow();

		AuthorizationCodeRequestUrl url = flow.newAuthorizationUrl()
			.setRedirectUri(properties.redirectUri())
			.setState(String.valueOf(userId));

		return url
			.set("access_type", ACCESS_TYPE_OFFLINE)
			.set("prompt", PROMPT_CONSENT)
			.build();
	}

	public GoogleTokenResponse exchangeCode(String code) {
		try {
			return createFlow()
				.newTokenRequest(code)
				.setRedirectUri(properties.redirectUri())
				.execute();
		} catch (IOException e) {
			throw new IllegalStateException("Google OAuth token 교환에 실패했습니다.", e);
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
			throw new IllegalStateException("Google access token 갱신에 실패했습니다.", e);
		}
	}

	private GoogleAuthorizationCodeFlow createFlow() {
		return new GoogleAuthorizationCodeFlow.Builder(
			new NetHttpTransport(),
			GsonFactory.getDefaultInstance(),
			properties.clientId(),
			properties.clientSecret(),
			List.of(properties.scope())
		).build();
	}
}
