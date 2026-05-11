package me.timetablescheduler.domain.preference;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.user.User;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Preference {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	// User와 1:1 매핑
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;
}
