package me.timetablescheduler.domain.calendar.external;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "google.calendar")
public record GoogleCalendarProperties(
	String clientId,
	String clientSecret,
	String redirectUri,
	String scope,
	String applicationName,
	String timeZone
) {
}
