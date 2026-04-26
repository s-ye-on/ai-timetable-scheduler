package me.timetablescheduler.auth.dto;

public record UserProfileResponse(
	Long id,
	String name,
	String email
) {
}
