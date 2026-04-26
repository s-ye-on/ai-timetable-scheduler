package me.timetablescheduler.domain.timetable;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.user.User;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

@Entity
@Getter
// jpa 엔티티에 필수
// 외부에서 아무렇게나 생성 못하게 protected 걸어둠
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TimetableSlot {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	private String subjectName;

	@Enumerated(EnumType.STRING) //0,1 이렇게 말고 enum으로 db에 저장되게
	private DayOfWeek dayOfWeek;

	private String location;
	private LocalDateTime startTime;
	private LocalDateTime endTime;
	private OffsetDateTime createTime;
	private OffsetDateTime updateTime;

}
