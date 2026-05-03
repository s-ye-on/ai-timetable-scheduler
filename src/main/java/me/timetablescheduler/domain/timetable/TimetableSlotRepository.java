package me.timetablescheduler.domain.timetable;


import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TimetableSlotRepository extends JpaRepository<TimetableSlot, Long> {
	Optional<TimetableSlot> findByIdAndUserId(Long id, Long userId);

	List<TimetableSlot> findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(Long userId);

	@Query("""
		select count(slot) > 0
		from TimetableSlot slot
		where slot.user.id = :userId
			and slot.dayOfWeek = :dayOfWeek
			and slot.startTime < :endTime
			and slot.endTime > :startTime
		""")
	boolean existsOverlappingSlot(
		@Param("userId") Long userId,
		@Param("dayOfWeek") DayOfWeek dayOfWeek,
		@Param("startTime") LocalTime startTime,
		@Param("endTime") LocalTime endTime
	);

	@Query("""
		select count(slot) > 0
		from TimetableSlot slot
		where slot.user.id = :userId
			and slot.id <> :excludedSlotId
			and slot.dayOfWeek = :dayOfWeek
			and slot.startTime < :endTime
			and slot.endTime > :startTime
		""")
	boolean existsOverlappingSlotExcept(
		@Param("userId") Long userId,
		@Param("excludedSlotId") Long excludedSlotId,
		@Param("dayOfWeek") DayOfWeek dayOfWeek,
		@Param("startTime") LocalTime startTime,
		@Param("endTime") LocalTime endTime
	);
}
