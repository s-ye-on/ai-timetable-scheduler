package me.timetablescheduler.domain.preference.service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.dto.PreferenceRequest;
import me.timetablescheduler.domain.preference.dto.PreferenceResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PreferenceService {
	private final PreferenceRepository preferenceRepository;
	private final UserReader userReader;

	@Transactional
	public PreferenceResponse.Read createDefault(User user) {
		if (preferenceRepository.existsByUserId(user.getId())) {
			return toReadResponse(findPreference(user.getId()));
		}

		return toReadResponse(preferenceRepository.save(Preference.createDefault(user)));
	}

	@Transactional
	public PreferenceResponse.Read read(Long userId) {
		return toReadResponse(findPreference(userId));
	}

	@Transactional
	public PreferenceResponse.Read update(Long userId, PreferenceRequest.Update request) {
		Preference preference = findPreference(userId);

		preference.update(
			request.preferredTimeRange(),
			request.scheduleStartTime(),
			request.scheduleEndTime(),
			request.minimumGapMinutes(),
			request.scheduleDensity(),
			request.deadlineTiming()
		);

		return toReadResponse(preference);
	}

	@Transactional
	public PreferenceResponse.Read resetToDefault(Long userId) {
		Preference preference = findPreference(userId);

		preference.resetToDefault();

		return toReadResponse(preference);
	}

	/// todo : 메서드 이름 더 명확히 변경 getOrCreateDefault()
	private Preference findPreference(Long userId) {
		return preferenceRepository.findByUserId(userId)
			.orElseGet(() -> {
				User user = userReader.read(userId);
				return preferenceRepository.save(Preference.createDefault(user));
			});
	}

	private PreferenceResponse.Read toReadResponse(Preference preference) {
		return new PreferenceResponse.Read(
			preference.getId(),
			preference.getPreferredTimeRange(),
			preference.getScheduleStartTime(),
			preference.getScheduleEndTime(),
			preference.getMinimumGapMinutes(),
			preference.getScheduleDensity(),
			preference.getDeadlineTiming(),
			preference.isCustomized()
		);
	}
}
