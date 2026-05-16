package me.timetablescheduler.domain.preference.service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.dto.PreferenceRequest;
import me.timetablescheduler.domain.preference.dto.PreferenceResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.PreferenceException;
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
		User user = userReader.read(userId);
		Preference preference = preferenceRepository.findByUserId(userId)
			.orElseGet(() -> preferenceRepository.save(Preference.createDefault(user)));

		preference.resetToDefault();

		return toReadResponse(preference);
	}

	/// 이 부분 회원가입 시 기본값 생성 정책을 택했기 때문에 read에서도 없으면
	/// 기본값 생성으로 복구하는 것이 더 사용자 친화적일 수도 있겠다 생각이 듬
	private Preference findPreference(Long userId) {
		return preferenceRepository.findByUserId(userId)
			.orElseThrow(() -> new PreferenceException(ExceptionCode.NOT_FOUND_PREFERENCE));
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
