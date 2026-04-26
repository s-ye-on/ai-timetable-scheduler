package me.timetablescheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

	private String issuer = "timetable-scheduler";
	private String secret = "change-me-in-production-change-me-in-production";
	private long accessTokenTtlSeconds = 900; //15분
	private long refreshTokenTtlSeconds = 1_209_600; // 14일

	public String getIssuer() {
		return issuer;
	}

	public void setIssuer(String issuer) {
		this.issuer = issuer;
	}

	public String getSecret() {
		return secret;
	}

	public void setSecret(String secret) {
		this.secret = secret;
	}

	public long getAccessTokenTtlSeconds() {
		return accessTokenTtlSeconds;
	}

	public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
		this.accessTokenTtlSeconds = accessTokenTtlSeconds;
	}

	public long getRefreshTokenTtlSeconds() {
		return refreshTokenTtlSeconds;
	}

	public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
		this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
	}
}
