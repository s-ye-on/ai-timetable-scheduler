package me.timetablescheduler.domain.preference.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalTime;
import java.util.Optional;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.dto.PreferenceRequest;
import me.timetablescheduler.domain.preference.dto.PreferenceResponse;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class PreferenceServiceTest {

	private PreferenceRepository preferenceRepository;
	private UserReader userReader;
	private PreferenceService preferenceService;

	@BeforeEach
	void setUp() {
		preferenceRepository = Mockito.mock(PreferenceRepository.class);
		userReader = Mockito.mock(UserReader.class);
		preferenceService = new PreferenceService(preferenceRepository, userReader);
	}

	@Test
	void 기본_사용자_선호를_생성한다() {
		User user = user(1L);
		when(preferenceRepository.existsByUserId(1L)).thenReturn(false);
		when(preferenceRepository.save(any(Preference.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PreferenceResponse.Read response = preferenceService.createDefault(user);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		assertEquals(LocalTime.of(9, 0), response.scheduleStartTime());
		assertEquals(LocalTime.of(22, 0), response.scheduleEndTime());
		assertEquals(10, response.minimumGapMinutes());
		assertEquals(ScheduleDensity.BALANCED, response.scheduleDensity());
		assertEquals(DeadlineTiming.BALANCED, response.deadlineTiming());
		assertFalse(response.customized());
		verify(preferenceRepository).save(any(Preference.class));
	}

	@Test
	void 기본_사용자_선호가_이미_있으면_새로_저장하지_않고_반환한다() {
		User user = user(1L);
		Preference preference = Preference.createDefault(user);
		when(preferenceRepository.existsByUserId(1L)).thenReturn(true);
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));

		PreferenceResponse.Read response = preferenceService.createDefault(user);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		verify(preferenceRepository, never()).save(any(Preference.class));
	}

	@Test
	void 사용자_선호를_조회한다() {
		Preference preference = Preference.createDefault(user(1L));
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));

		PreferenceResponse.Read response = preferenceService.read(1L);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		assertFalse(response.customized());
	}

	@Test
	void 사용자_선호가_없으면_조회시_기본값으로_복구한다() {
		User user = user(1L);
		when(userReader.read(1L)).thenReturn(user);
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
		when(preferenceRepository.save(any(Preference.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PreferenceResponse.Read response = preferenceService.read(1L);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		assertFalse(response.customized());
		verify(preferenceRepository).save(any(Preference.class));
	}

	@Test
	void 사용자_선호를_수정하면_customized가_true가_된다() {
		Preference preference = Preference.createDefault(user(1L));
		PreferenceRequest.Update request = updateRequest();
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));

		PreferenceResponse.Read response = preferenceService.update(1L, request);

		assertEquals(PreferredTimeRange.EVENING, response.preferredTimeRange());
		assertEquals(LocalTime.of(10, 0), response.scheduleStartTime());
		assertEquals(LocalTime.of(21, 0), response.scheduleEndTime());
		assertEquals(15, response.minimumGapMinutes());
		assertEquals(ScheduleDensity.RELAXED, response.scheduleDensity());
		assertEquals(DeadlineTiming.NEAR_DEADLINE, response.deadlineTiming());
		assertTrue(response.customized());
	}

	@Test
	void 사용자_선호가_없으면_수정시_기본값으로_복구한_뒤_수정한다() {
		User user = user(1L);
		PreferenceRequest.Update request = updateRequest();
		when(userReader.read(1L)).thenReturn(user);
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
		when(preferenceRepository.save(any(Preference.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PreferenceResponse.Read response = preferenceService.update(1L, request);

		assertEquals(PreferredTimeRange.EVENING, response.preferredTimeRange());
		assertEquals(LocalTime.of(10, 0), response.scheduleStartTime());
		assertEquals(LocalTime.of(21, 0), response.scheduleEndTime());
		assertEquals(15, response.minimumGapMinutes());
		assertEquals(ScheduleDensity.RELAXED, response.scheduleDensity());
		assertEquals(DeadlineTiming.NEAR_DEADLINE, response.deadlineTiming());
		assertTrue(response.customized());
		verify(preferenceRepository).save(any(Preference.class));
	}

	@Test
	void 사용자_선호를_기본값으로_되돌리면_customized가_false가_된다() {
		User user = user(1L);
		Preference preference = Preference.create(
			user,
			PreferredTimeRange.EVENING,
			LocalTime.of(10, 0),
			LocalTime.of(21, 0),
			15,
			ScheduleDensity.RELAXED,
			DeadlineTiming.NEAR_DEADLINE
		);
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));

		PreferenceResponse.Read response = preferenceService.resetToDefault(1L);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		assertEquals(LocalTime.of(9, 0), response.scheduleStartTime());
		assertEquals(LocalTime.of(22, 0), response.scheduleEndTime());
		assertEquals(10, response.minimumGapMinutes());
		assertEquals(ScheduleDensity.BALANCED, response.scheduleDensity());
		assertEquals(DeadlineTiming.BALANCED, response.deadlineTiming());
		assertFalse(response.customized());
	}

	@Test
	void 사용자_선호가_없어도_기본값으로_되돌리면_기본_선호를_생성한다() {
		User user = user(1L);
		when(userReader.read(1L)).thenReturn(user);
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
		when(preferenceRepository.save(any(Preference.class))).thenAnswer(invocation -> invocation.getArgument(0));

		PreferenceResponse.Read response = preferenceService.resetToDefault(1L);

		assertEquals(PreferredTimeRange.ANYTIME, response.preferredTimeRange());
		assertFalse(response.customized());
		verify(preferenceRepository).save(any(Preference.class));
	}

	private PreferenceRequest.Update updateRequest() {
		return new PreferenceRequest.Update(
			PreferredTimeRange.EVENING,
			LocalTime.of(10, 0),
			LocalTime.of(21, 0),
			15,
			ScheduleDensity.RELAXED,
			DeadlineTiming.NEAR_DEADLINE
		);
	}

	private User user(Long id) {
		User user = new User("Tester", "tester@example.com", "encodedPassword");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
