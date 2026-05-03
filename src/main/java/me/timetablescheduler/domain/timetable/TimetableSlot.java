package me.timetablescheduler.domain.timetable;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TimetableSlotException;

import java.time.DayOfWeek;
import java.time.LocalTime;
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
	private LocalTime startTime;
	private LocalTime endTime;
	private OffsetDateTime createTime;
	private OffsetDateTime updateTime;

	private TimetableSlot(
		User user,
		String subjectName,
		DayOfWeek dayOfWeek,
		String location,
		LocalTime startTime,
		LocalTime endTime
	) {
		validateRequired(user, subjectName, dayOfWeek, startTime, endTime);
		validateTimeRange(startTime, endTime);

		this.user = user;
		this.subjectName = subjectName;
		this.dayOfWeek = dayOfWeek;
		this.location = location;
		this.startTime = startTime;
		this.endTime = endTime;
		this.createTime = OffsetDateTime.now();
		this.updateTime = OffsetDateTime.now();
	}

	public static TimetableSlot create(
		User user,
		String subjectName,
		DayOfWeek dayOfWeek,
		String location,
		LocalTime startTime,
		LocalTime endTime
	) {
		return new TimetableSlot(user, subjectName, dayOfWeek, location, startTime, endTime);
	}

	public void update(
		String subjectName,
		DayOfWeek dayOfWeek,
		String location,
		LocalTime startTime,
		LocalTime endTime
	) {
		validateRequired(this.user, subjectName, dayOfWeek, startTime, endTime);
		validateTimeRange(startTime, endTime);

		this.subjectName = subjectName;
		this.dayOfWeek = dayOfWeek;
		this.location = location;
		this.startTime = startTime;
		this.endTime = endTime;
		this.updateTime = OffsetDateTime.now();
	}

	private void validateRequired(
		User user,
		String subjectName,
		DayOfWeek dayOfWeek,
		LocalTime startTime,
		LocalTime endTime
	) {
		if (user == null || subjectName == null || subjectName.isBlank()
			|| dayOfWeek == null || startTime == null || endTime == null) {
			throw new TimetableSlotException(ExceptionCode.INVALID_TIMETABLE_SLOT);
		}
	}

	private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
		if (!startTime.isBefore(endTime)) {
			throw new TimetableSlotException(ExceptionCode.INVALID_TIMETABLE_SLOT_TIME);
		}
	}
}
