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
}
