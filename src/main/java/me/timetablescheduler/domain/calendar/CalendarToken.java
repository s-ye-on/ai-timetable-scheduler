package me.timetablescheduler.domain.calendar;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.user.User;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CalendarToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "calendar_token_id")
	private Long id;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false, unique = true)
	private User user;

	@Column(nullable = false, length = 2000)
	private String accessToken;

	@Column(length = 2000)
	private String refreshToken;

	/// todo : 만료 시각은 Instant가 더 정확하기에 추후 변경할지 고민
	/// OAuth 토큰 만료는 특정 지역 시간이 아니라 절대 시각이기 때문
	@Column(nullable = false)
	private LocalDateTime expiresAt;

	@Column(nullable = false)
	private String calendarId;

	protected CalendarToken(
		User user,
		String accessToken,
		String refreshToken,
		LocalDateTime expiresAt,
		String calendarId
	) {
		this.user = user;
		this.accessToken = accessToken;
		this.refreshToken = refreshToken;
		this.expiresAt = expiresAt;
		this.calendarId = calendarId;
	}

	public static CalendarToken create(
		User user,
		String accessToken,
		String refreshToken,
		LocalDateTime expiresAt
	) {
		return new CalendarToken(user, accessToken, refreshToken, expiresAt, "primary");
	}

	public void updateToken(String accessToken, String refreshToken, LocalDateTime expiresAt) {
		this.accessToken = accessToken;

		if (refreshToken != null && !refreshToken.isBlank()) {
			this.refreshToken = refreshToken;
		}

		this.expiresAt = expiresAt;
	}

	public boolean isExpired(LocalDateTime now) {
		return !expiresAt.isAfter(now.plusMinutes(1));
	}
}
