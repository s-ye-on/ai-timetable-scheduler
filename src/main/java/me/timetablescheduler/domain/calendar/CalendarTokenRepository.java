package me.timetablescheduler.domain.calendar;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CalendarTokenRepository extends JpaRepository<CalendarToken, Long> {
	Optional<CalendarToken> findByUserId(Long userId);

	boolean existsByUserId(Long userId);
}
