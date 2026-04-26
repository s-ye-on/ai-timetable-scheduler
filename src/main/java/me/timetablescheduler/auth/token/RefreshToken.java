package me.timetablescheduler.auth.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "refresh_tokens")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 512)
	private String token;

	// userEmail
	@Column(nullable = false, length = 50)
	private String username;

	@Column(nullable = false)
	private Instant expiresAt;

	public RefreshToken(String token, String username, Instant expiresAt) {
		this.token = token;
		this.username = username;
		this.expiresAt = expiresAt;
	}

	public boolean isExpired(Instant now) {
		return expiresAt.isBefore(now);
	}

	public void rotate(String nextToken, Instant nextExpiresAt) {
		this.token = nextToken;
		this.expiresAt = nextExpiresAt;
	}
}
