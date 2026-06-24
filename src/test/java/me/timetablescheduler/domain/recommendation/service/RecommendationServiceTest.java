package me.timetablescheduler.domain.recommendation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
import me.timetablescheduler.domain.recommendation.policy.CandidateSlot;
import me.timetablescheduler.domain.recommendation.policy.RecommendationPolicy;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class RecommendationServiceTest {

	private RecommendationRepository recommendationRepository;
	private TaskRepository taskRepository;
	private PreferenceRepository preferenceRepository;
	private TimetableSlotRepository timetableSlotRepository;
	private UserReader userReader;
	private RecommendationPolicy recommendationPolicy;
	private CalendarService calendarService;
	private RecommendationService recommendationService;

	@BeforeEach
	void setUp() {
		recommendationRepository = Mockito.mock(RecommendationRepository.class);
		taskRepository = Mockito.mock(TaskRepository.class);
		preferenceRepository = Mockito.mock(PreferenceRepository.class);
		timetableSlotRepository = Mockito.mock(TimetableSlotRepository.class);
		userReader = Mockito.mock(UserReader.class);
		recommendationPolicy = Mockito.mock(RecommendationPolicy.class);
		calendarService = Mockito.mock(CalendarService.class);
		recommendationService = new RecommendationService(
			recommendationRepository,
			taskRepository,
			preferenceRepository,
			timetableSlotRepository,
			userReader,
			recommendationPolicy,
			calendarService
		);
	}

	@Test
	void 새_추천을_생성하기_전에_기존_PROPOSED_추천을_만료한다() {
		User user = user(1L);
		Task task = task(user);
		Preference preference = Preference.createDefault(user);
		Recommendation existingRecommendation = Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			1,
			70,
			"기존 추천"
		);
		List<CandidateSlot> candidateSlots = List.of(
			new CandidateSlot(
				LocalDateTime.of(2026, 5, 18, 10, 0),
				LocalDateTime.of(2026, 5, 18, 11, 0),
				90,
				"새 추천"
			)
		);

		when(userReader.read(1L)).thenReturn(user);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));
		when(timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());
		when(taskRepository.findAllByUserIdAndStatus(1L, TaskStatus.SCHEDULED)).thenReturn(List.of());
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of(existingRecommendation));
		when(recommendationPolicy.generateCandidates(task, preference, List.of(), List.of(), List.of())).thenReturn(candidateSlots);
		when(recommendationRepository.saveAll(Mockito.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

		RecommendationResponse.Generate response = recommendationService.recommend(1L, 10L);

		assertEquals(RecommendationStatus.EXPIRED, existingRecommendation.getStatus());
		assertEquals("추천 가능한 시간을 찾았습니다.", response.message());
		assertEquals(1, response.recommendations().size());
		assertEquals(1, response.recommendations().get(0).rank());
		assertEquals(90, response.recommendations().get(0).score());
	}

	@Test
	void 점수가_높은_후보_3개만_추천으로_저장한다() {
		User user = user(1L);
		Task task = task(user);
		Preference preference = Preference.createDefault(user);
		List<CandidateSlot> candidateSlots = List.of(
			candidate(60, 9),
			candidate(90, 10),
			candidate(70, 11),
			candidate(80, 12)
		);

		when(userReader.read(1L)).thenReturn(user);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));
		when(timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());
		when(taskRepository.findAllByUserIdAndStatus(1L, TaskStatus.SCHEDULED)).thenReturn(List.of());
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of());
		when(recommendationPolicy.generateCandidates(task, preference, List.of(), List.of(), List.of())).thenReturn(candidateSlots);
		when(recommendationRepository.saveAll(Mockito.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

		recommendationService.recommend(1L, 10L);

		ArgumentCaptor<List<Recommendation>> captor = ArgumentCaptor.forClass(List.class);
		Mockito.verify(recommendationRepository).saveAll(captor.capture());
		List<Recommendation> savedRecommendations = captor.getValue();
		assertEquals(3, savedRecommendations.size());
		assertEquals(90, savedRecommendations.get(0).getScore());
		assertEquals(1, savedRecommendations.get(0).getRank());
		assertEquals(80, savedRecommendations.get(1).getScore());
		assertEquals(2, savedRecommendations.get(1).getRank());
		assertEquals(70, savedRecommendations.get(2).getScore());
		assertEquals(3, savedRecommendations.get(2).getRank());
	}

	@Test
	void 사용자_선호가_없으면_기본값을_생성해서_추천에_사용한다() {
		User user = user(1L);
		Task task = task(user);
		List<CandidateSlot> candidateSlots = List.of(candidate(90, 10));

		when(userReader.read(1L)).thenReturn(user);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.empty());
		when(preferenceRepository.save(Mockito.any(Preference.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());
		when(taskRepository.findAllByUserIdAndStatus(1L, TaskStatus.SCHEDULED)).thenReturn(List.of());
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of());
		when(recommendationPolicy.generateCandidates(
				Mockito.eq(task),
				Mockito.any(Preference.class),
				Mockito.eq(List.of()),
				Mockito.eq(List.of()),
				Mockito.eq(List.of())
			))
			.thenReturn(candidateSlots);
		when(recommendationRepository.saveAll(Mockito.anyList())).thenAnswer(invocation -> invocation.getArgument(0));

		RecommendationResponse.Generate response = recommendationService.recommend(1L, 10L);

		assertEquals(1, response.recommendations().size());
		assertEquals(90, response.recommendations().get(0).score());
		Mockito.verify(preferenceRepository).save(Mockito.any(Preference.class));
	}

	@Test
	void 추천_후보가_없으면_안내_메시지와_빈_목록을_반환하고_저장하지_않는다() {
		User user = user(1L);
		Task task = task(user);
		Preference preference = Preference.createDefault(user);

		when(userReader.read(1L)).thenReturn(user);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));
		when(preferenceRepository.findByUserId(1L)).thenReturn(Optional.of(preference));
		when(timetableSlotRepository.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(1L)).thenReturn(List.of());
		when(taskRepository.findAllByUserIdAndStatus(1L, TaskStatus.SCHEDULED)).thenReturn(List.of());
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of());
		when(recommendationPolicy.generateCandidates(task, preference, List.of(), List.of(), List.of())).thenReturn(List.of());

		RecommendationResponse.Generate response = recommendationService.recommend(1L, 10L);

		assertEquals("추천 가능한 시간이 없습니다. 시간표 또는 선호 시간 범위를 조정해 주세요.", response.message());
		assertEquals(List.of(), response.recommendations());
		Mockito.verify(recommendationRepository, Mockito.never()).saveAll(Mockito.anyList());
	}

	@Test
	void 이미_스케줄된_Task는_추천을_생성할_수_없다() {
		User user = user(1L);
		Task task = task(user);
		task.schedule(
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0)
		);

		when(userReader.read(1L)).thenReturn(user);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));

		TaskException exception = assertThrows(
			TaskException.class,
			() -> recommendationService.recommend(1L, 10L)
		);

		assertEquals(ExceptionCode.INVALID_TASK_STATUS_TRANSITION, exception.getExceptionCode());
	}

	@Test
	void 추천_후보를_선택하면_Task가_스케줄되고_나머지_PROPOSED_후보를_거절한다() {
		User user = user(1L);
		Task task = task(user);
		ReflectionTestUtils.setField(task, "id", 10L);
		Recommendation recommendation = Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			1,
			90,
			"선택할 추천"
		);
		Recommendation otherRecommendation = Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 5, 18, 10, 0),
			LocalDateTime.of(2026, 5, 18, 11, 0),
			2,
			80,
			"다른 추천"
		);

		when(recommendationRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(recommendation));
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of(otherRecommendation));
		when(calendarService.isConnected(1L)).thenReturn(true);
		when(calendarService.createEvent(
			1L,
			task.getTitle(),
			task.getDescription(),
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0)
		)).thenReturn("google-event-id");

		RecommendationResponse.Read response = recommendationService.select(1L, 100L);

		assertEquals(RecommendationStatus.SYNCED, recommendation.getStatus());
		assertEquals("google-event-id", recommendation.getGoogleEventId());
		assertEquals(TaskStatus.SCHEDULED, task.getStatus());
		assertEquals(RecommendationStatus.REJECTED, otherRecommendation.getStatus());
		assertEquals(RecommendationStatus.SYNCED, response.status());
		assertEquals(LocalDateTime.of(2026, 5, 18, 9, 0), response.recommendedStartAt());
	}

	@Test
	void Google_Calendar가_연동되지_않으면_추천_후보_선택은_실패한다() {
		User user = user(1L);
		Task task = task(user);
		ReflectionTestUtils.setField(task, "id", 10L);
		Recommendation recommendation = Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			1,
			90,
			"선택할 추천"
		);

		when(recommendationRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(recommendation));
		when(calendarService.isConnected(1L)).thenReturn(false);

		CalendarException exception = assertThrows(
			CalendarException.class,
			() -> recommendationService.select(1L, 100L)
		);

		assertEquals(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED, exception.getExceptionCode());
		assertEquals(RecommendationStatus.PROPOSED, recommendation.getStatus());
		assertEquals(TaskStatus.UNSCHEDULED, task.getStatus());
	}

	@Test
	void Google_Calendar_Event_생성에_실패하면_추천_후보는_SYNC_FAILED가_된다() {
		User user = user(1L);
		Task task = task(user);
		ReflectionTestUtils.setField(task, "id", 10L);
		Recommendation recommendation = Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0),
			1,
			90,
			"선택할 추천"
		);

		when(recommendationRepository.findByIdAndUserId(100L, 1L)).thenReturn(Optional.of(recommendation));
		when(recommendationRepository.findAllByTaskIdAndUserIdAndStatus(10L, 1L, RecommendationStatus.PROPOSED))
			.thenReturn(List.of());
		when(calendarService.isConnected(1L)).thenReturn(true);
		when(calendarService.createEvent(
			1L,
			task.getTitle(),
			task.getDescription(),
			LocalDateTime.of(2026, 5, 18, 9, 0),
			LocalDateTime.of(2026, 5, 18, 10, 0)
		)).thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED));

		RecommendationResponse.Read response = recommendationService.select(1L, 100L);

		assertEquals(RecommendationStatus.SYNC_FAILED, recommendation.getStatus());
		assertEquals(TaskStatus.SCHEDULED, task.getStatus());
		assertEquals(RecommendationStatus.SYNC_FAILED, response.status());
	}

	private CandidateSlot candidate(int score, int hour) {
		return new CandidateSlot(
			LocalDateTime.of(2026, 5, 18, hour, 0),
			LocalDateTime.of(2026, 5, 18, hour + 1, 0),
			score,
			score + "점 후보"
		);
	}

	private Task task(User user) {
		return Task.create(
			user,
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.MONDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"월요일에 과제할 시간 잡아줘"
		);
	}

	private User user(Long id) {
		User user = new User("Tester", "tester@example.com", "encodedPassword");
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
