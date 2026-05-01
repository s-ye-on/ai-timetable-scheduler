package me.timetablescheduler.domain.preference;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Preference {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	Long id;

	public enum PreferredTimeRange {
		MORNING, // 09:00 ~ 12:00
		LUNCH, // 12:00 ~ 14:00
		AFTERNOON, //14:00 ~ 18:00
		EVENING, // 18:00 ~ 22:00
		ANYTIME
	}
}
