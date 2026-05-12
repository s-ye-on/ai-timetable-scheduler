package me.timetablescheduler.domain.preference;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
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

	@Enumerated(EnumType.STRING)
	// 사용자 기본 선호는 필수
	@Column(nullable = false)
	private PreferredTimeRange preferredTimeRange;
}
