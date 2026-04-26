package me.timetablescheduler.domain.timetable;


import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeTableSlotRepository extends JpaRepository<TimetableSlot, Long> {
	Optional<TimetableSlot> findByIdAndUserId(Long id, Long userId);
}
