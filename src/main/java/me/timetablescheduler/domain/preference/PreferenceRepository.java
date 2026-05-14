package me.timetablescheduler.domain.preference;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PreferenceRepository extends JpaRepository<Preference, Long> {
	Optional<Preference> findByUserId(Long userId);

	boolean existsByUserId(Long userId);
}
