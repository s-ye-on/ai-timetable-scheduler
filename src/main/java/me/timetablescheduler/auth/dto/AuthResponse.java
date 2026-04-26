package me.timetablescheduler.auth.dto;

/// todo : AuthResponse도 sealed + record로 만들자
public record AuthResponse(
	String accessToken,
	String refreshToken,
	String tokenType,
	long accessTokenExpiresIn
) {
}
