package me.timetablescheduler.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import me.timetablescheduler.domain.calendar.CalendarToken;
import me.timetablescheduler.domain.calendar.CalendarTokenRepository;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.preference.dto.PreferenceRequest;
import me.timetablescheduler.domain.preference.service.PreferenceService;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.service.TaskService;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.timetable.TimetableSlot;
import me.timetablescheduler.domain.timetable.TimetableSlotRepository;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotRequest;
import me.timetablescheduler.domain.timetable.service.TimetableSlotService;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ApiException;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t07-selection;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
class RecommendationSelectionIntegrationTest {

	private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
	private static final LocalDateTime START = DATE.atTime(10, 0);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	@Autowired private RecommendationService service;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private TaskRepository tasks;
	@Autowired private UserRepository users;
	@Autowired private PreferenceRepository preferences;
	@Autowired private CalendarTokenRepository tokens;
	@Autowired private TimetableSlotRepository timetable;
	@Autowired private TaskService taskService;
	@Autowired private TimetableSlotService timetableService;
	@Autowired private PreferenceService preferenceService;
	@Autowired private PlatformTransactionManager transactionManager;
	@MockitoBean private GoogleCalendarClient google;
	@MockitoBean private Clock clock;
	private User user;
	private Task task;

	@BeforeEach
	void setUp() {
		when(clock.getZone()).thenReturn(SEOUL);
		setNow(DATE.atTime(9, 0));
		user = user();
		task = task(user);
		preferences.save(Preference.createDefault(user));
		connect(user);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of());
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(2));
	}

	@Test
	void newTimetableConflictBlocksSelectionWithoutChangingStoredState() {
		var candidate = candidate(task, START);
		timetable.save(TimetableSlot.create(user, "새 수업", DATE.getDayOfWeek(), "강의실", LocalTime.of(10, 30), LocalTime.of(11, 30)));
		assertUnavailable(candidate);
	}

	@Test
	void newlyScheduledTaskBlocksPreviouslyGeneratedCandidate() {
		var candidate = candidate(task, START);
		var other = task(user);
		other.schedule(START.plusMinutes(30), START.plusMinutes(90));
		tasks.saveAndFlush(other);
		assertUnavailable(candidate);
	}

	@Test
	void latestPreferenceGapBlocksAdjacentTask() {
		var preference = preferences.findByUserId(user.getId()).orElseThrow();
		preference.update(preference.getPreferredTimeRange(), preference.getScheduleStartTime(), preference.getScheduleEndTime(),
			0, preference.getScheduleDensity(), preference.getDeadlineTiming());
		preferences.saveAndFlush(preference);
		var candidate = candidate(task, START);
		var other = task(user);
		other.schedule(START.minusMinutes(60), START);
		tasks.saveAndFlush(other);
		preference.update(preference.getPreferredTimeRange(), preference.getScheduleStartTime(), preference.getScheduleEndTime(),
			20, preference.getScheduleDensity(), preference.getDeadlineTiming());
		preferences.saveAndFlush(preference);
		assertUnavailable(candidate);
	}

	@Test
	void newlyAddedGoogleEventBlocksSelection() {
		var candidate = candidate(task, START);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new BusyBlock(START.plusMinutes(30), START.plusMinutes(90))));
		assertUnavailable(candidate);
	}

	@Test
	void googleQueryFailureDoesNotConfirmOrCreateAnEvent() {
		var candidate = candidate(task, START);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId())).isInstanceOf(CalendarException.class)
			.satisfies(e -> assertThat(((CalendarException) e).getExceptionCode()).isEqualTo(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
		assertUnchanged(candidate);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 60})
	void candidateAtOrBeforeCurrentTimeIsRejected(int minutesAfterStart) {
		var candidate = candidate(task, START);
		setNow(START.plusMinutes(minutesAfterStart));
		assertUnavailable(candidate);
	}

	@Test
	void currentTimeIsCheckedAgainAfterSlowGoogleQuery() {
		var candidate = candidate(task, START);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
			setNow(START.plusMinutes(1));
			return List.of();
		});
		assertUnavailable(candidate);
	}

	@Test
	void disconnectedGenerationIsRevalidatedAfterConnection() {
		tokens.deleteAll(tokens.findAll().stream().filter(t -> t.getUser().getId().equals(user.getId())).toList());
		var generated = service.recommend(user.getId(), task.getId());
		assertThat(generated.recommendations()).isNotEmpty();
		var candidate = recommendations.findById(generated.recommendations().getFirst().id()).orElseThrow();
		connect(user);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of(
			new BusyBlock(candidate.getRecommendedStartAt(), candidate.getRecommendedEndAt())));
		assertUnavailable(candidate);
	}

	@Test
	void validCandidateUsesExpandedCalendarRangeAndPreservesScore() {
		var candidate = candidate(task, START);
		var response = service.select(user.getId(), candidate.getId());
		assertThat(response.status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(response.score()).isEqualTo(80);
		assertThat(tasks.findById(task.getId()).orElseThrow().getScheduledStartAt()).isEqualTo(START);
		verify(google, times(2)).getBusyBlocks("test-only-access", "primary", START.minusMinutes(10), START.plusMinutes(70));
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void exactlyTheMinimumGapIsAllowed() {
		var candidate = candidate(task, START);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new BusyBlock(START.minusMinutes(70), START.minusMinutes(10))));
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
	}

	@Test
	void simultaneousSameCandidateSelectionCreatesOnlyOneEvent() throws Exception {
		var candidate = candidate(task, START);
		// 동일 선택의 재요청은 멱등 성공으로 응답하되 실제 외부 생성은 한 번이어야 한다.
		var results = selectConcurrently(candidate, candidate);
		assertThat(results).allMatch(result -> result == null);
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(tasks.findAllByUserIdAndStatus(user.getId(), TaskStatus.SCHEDULED)).hasSize(1);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void failedSynchronizationCanRetryTheSameSelection() {
		var candidate = candidate(task, START);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED))
			.thenAnswer(invocation -> invocation.getArgument(2));
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);
	}

	@Test
	void googleSuccessFollowedByCommitFailureKeepsARecoverableReservation() {
		var candidate = candidate(task, START);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenAnswer(invocation -> {
				org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
					new org.springframework.transaction.support.TransactionSynchronization() {
						@Override public void beforeCommit(boolean readOnly) {
							throw new IllegalStateException("최종 커밋 실패 재현");
						}
					});
				return invocation.getArgument(2);
			});
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId())).isInstanceOf(IllegalStateException.class);
		var reserved = recommendations.findById(candidate.getId()).orElseThrow();
		assertThat(reserved.getStatus()).isEqualTo(RecommendationStatus.SELECTED);
		assertThat(reserved.getGoogleEventId()).isNotBlank();
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);
	}

	@Test
	void simultaneousDifferentCandidatesOfTheSameTaskCreateOnlyOneEvent() throws Exception {
		assertOneWinner(candidate(task, START), candidate(task, START.plusHours(2)));
	}

	@Test
	void simultaneousOverlappingDifferentTasksCreateOnlyOneEvent() throws Exception {
		assertOneWinner(candidate(task, START), candidate(task(user), START.plusMinutes(30)));
	}

	@Test
	void nonOverlappingTasksCanBothBeConfirmed() throws Exception {
		var first = candidate(task, START);
		var second = candidate(task(user), START.plusHours(2));
		var results = selectConcurrently(first, second);
		assertThat(results).allMatch(result -> result == null);
		assertThat(recommendations.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(recommendations.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		verify(google, times(2)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(strings = {"task-update", "task-delete", "recommend", "timetable-create", "timetable-update", "timetable-delete", "preference-update", "preference-reset"})
	void scheduleWritersWaitForTheSameUserDatabaseLock(String operation) throws Exception {
		var existing = candidate(task, START);
		var seededSlot = operation.equals("timetable-update") || operation.equals("timetable-delete")
			? timetable.save(TimetableSlot.create(user, "기존 수업", DATE.getDayOfWeek(), "강의실", LocalTime.of(13, 0), LocalTime.of(14, 0)))
			: null;
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch writerStarted = new CountDownLatch(1);
		CountDownLatch writerFinished = new CountDownLatch(1);
		Runnable action = switch (operation) {
			case "task-update" -> () -> taskService.update(task.getId(), new TaskRequest.Update("수정", TaskCategory.ASSIGNMENT,
				60, DATE.plusDays(1), null, null, null, null, null, TaskPriority.NORMAL, "수정"), user.getId());
			case "task-delete" -> () -> taskService.delete(task.getId(), user.getId());
			case "recommend" -> () -> service.recommend(user.getId(), task.getId());
			case "timetable-create" -> () -> timetableService.create(new TimetableSlotRequest.Create("새 수업", DATE.getDayOfWeek(),
				"강의실", LocalTime.of(13, 0), LocalTime.of(14, 0)), user.getId());
			case "timetable-update" -> () -> timetableService.update(seededSlot.getId(), new TimetableSlotRequest.Update("수정 수업",
				DATE.getDayOfWeek(), "강의실", LocalTime.of(14, 0), LocalTime.of(15, 0)), user.getId());
			case "timetable-delete" -> () -> timetableService.delete(seededSlot.getId(), user.getId());
			case "preference-update" -> () -> {
				var p = preferences.findByUserId(user.getId()).orElseThrow();
				preferenceService.update(user.getId(), new PreferenceRequest.Update(p.getPreferredTimeRange(), p.getScheduleStartTime(),
					p.getScheduleEndTime(), 20, p.getScheduleDensity(), p.getDeadlineTiming()));
			};
			case "preference-reset" -> () -> preferenceService.resetToDefault(user.getId());
			default -> throw new AssertionError(operation);
		};
		try (var executor = Executors.newFixedThreadPool(2)) {
			var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				users.findByIdForUpdate(user.getId()).orElseThrow();
				locked.countDown();
				await(release);
			}));
			try {
				assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
				var writer = executor.submit(() -> {
					writerStarted.countDown();
					try { action.run(); } finally { writerFinished.countDown(); }
				});
				assertThat(writerStarted.await(3, TimeUnit.SECONDS)).isTrue();
				assertThat(writerFinished.await(200, TimeUnit.MILLISECONDS)).isFalse();
				release.countDown();
				holder.get(5, TimeUnit.SECONDS);
				writer.get(5, TimeUnit.SECONDS);
			} finally { release.countDown(); }
		}
		switch (operation) {
			case "task-update" -> {
				assertThat(tasks.findById(task.getId()).orElseThrow().getPreferredDate()).isEqualTo(DATE.plusDays(1));
				assertThat(recommendations.findById(existing.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.EXPIRED);
			}
			case "task-delete" -> {
				assertThat(tasks.existsById(task.getId())).isFalse();
				assertThat(recommendations.existsById(existing.getId())).isFalse();
			}
			case "recommend" -> {
				assertThat(recommendations.findById(existing.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.EXPIRED);
				assertThat(service.readProposedByTask(user.getId(), task.getId())).hasSize(3);
			}
			case "timetable-create" -> assertThat(timetable.findAllByUserIdOrderByDayOfWeekAscStartTimeAsc(user.getId())).hasSize(1);
			case "timetable-update" -> assertThat(timetable.findById(seededSlot.getId()).orElseThrow().getStartTime()).isEqualTo(LocalTime.of(14, 0));
			case "timetable-delete" -> assertThat(timetable.existsById(seededSlot.getId())).isFalse();
			case "preference-update" -> assertThat(preferences.findByUserId(user.getId()).orElseThrow().getMinimumGapMinutes()).isEqualTo(20);
			case "preference-reset" -> assertThat(preferences.findByUserId(user.getId()).orElseThrow().isCustomized()).isFalse();
			default -> throw new AssertionError(operation);
		}
	}

	@Test
	void differentUsersDoNotBlockEachOthersConfirmation() throws Exception {
		var first = candidate(task, START);
		var otherUser = user();
		connect(otherUser);
		var second = candidate(task(otherUser), START);
		CountDownLatch firstCreating = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger calls = new AtomicInteger();
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
			if (calls.incrementAndGet() == 1) { firstCreating.countDown(); await(release); }
			return invocation.getArgument(2);
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var firstSelection = executor.submit(() -> service.select(user.getId(), first.getId()));
			try {
				assertThat(firstCreating.await(3, TimeUnit.SECONDS)).isTrue();
				var secondSelection = executor.submit(() -> service.select(otherUser.getId(), second.getId()));
				assertThat(secondSelection.get(3, TimeUnit.SECONDS).status()).isEqualTo(RecommendationStatus.SYNCED);
				assertThat(tasks.findById(second.getTask().getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);
				release.countDown();
				assertThat(firstSelection.get(3, TimeUnit.SECONDS).status()).isEqualTo(RecommendationStatus.SYNCED);
			} finally { release.countDown(); }
		}
		assertThat(calls.get()).isEqualTo(2);
	}

	@Test
	void databaseLockTimeoutReturnsAConflictWithoutChangingOrRegisteringTheCandidate() throws Exception {
		var candidate = candidate(task, START);
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				users.findByIdForUpdate(user.getId()).orElseThrow();
				locked.countDown();
				await(release);
			}));
			try {
				assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
				var selection = executor.submit(() -> {
					try { service.select(user.getId(), candidate.getId()); return null; }
					catch (RuntimeException error) { return error; }
				});
				assertThat(selection.get(7, TimeUnit.SECONDS)).isInstanceOf(ApiException.class)
					.satisfies(error -> {
						var code = ((ApiException) error).getExceptionCode();
						assertThat(code).isEqualTo(ExceptionCode.CONFLICT_USER_OPERATION);
						assertThat(code.getStatus().value()).isEqualTo(409);
					});
			} finally { release.countDown(); }
			holder.get(3, TimeUnit.SECONDS);
		}
		assertUnchanged(candidate);
	}

	@Test
	void calendarQueryIncludesThePreviousDateWhenMinimumGapCrossesMidnight() {
		var p = preferences.findByUserId(user.getId()).orElseThrow();
		p.update(p.getPreferredTimeRange(), LocalTime.of(0, 5), LocalTime.of(23, 0), 10, p.getScheduleDensity(), p.getDeadlineTiming());
		preferences.saveAndFlush(p);
		setNow(DATE.minusDays(1).atTime(23, 0));
		var start = DATE.atTime(0, 5);
		var candidate = candidate(task, start);
		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new BusyBlock(DATE.minusDays(1).atTime(23, 0), DATE.atStartOfDay())));
		assertUnavailable(candidate);
		verify(google).getBusyBlocks("test-only-access", "primary", DATE.minusDays(1).atTime(23, 55), DATE.atTime(1, 15));
	}

	private void await(CountDownLatch latch) {
		try { if (!latch.await(8, TimeUnit.SECONDS)) throw new AssertionError("동시성 테스트 대기 시간 초과"); }
		catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
	}

	private void assertOneWinner(Recommendation first, Recommendation second) throws Exception {
		var results = selectConcurrently(first, second);
		assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
		assertThat(results.stream().filter(result -> result != null).toList()).singleElement().isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).getExceptionCode())
				.isEqualTo(first.getTask().getId().equals(second.getTask().getId())
					? ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION : ExceptionCode.RECOMMENDATION_NO_LONGER_AVAILABLE));
		assertThat(recommendations.findById(first.getId()).orElseThrow().getStatus() == RecommendationStatus.SYNCED
			|| recommendations.findById(second.getId()).orElseThrow().getStatus() == RecommendationStatus.SYNCED).isTrue();
		assertThat(tasks.findAllByUserIdAndStatus(user.getId(), TaskStatus.SCHEDULED)).hasSize(1);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	private List<Throwable> selectConcurrently(Recommendation first, Recommendation second) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch bothQueries = new CountDownLatch(2);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
			// 락이 없다면 두 트랜잭션의 외부 등록이 겹치도록 유도한다.
			bothQueries.countDown();
			bothQueries.await(250, TimeUnit.MILLISECONDS);
			return invocation.getArgument(2);
		});
		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> attemptSelection(first.getId(), start));
			var b = executor.submit(() -> attemptSelection(second.getId(), start));
			start.countDown();
			return java.util.Arrays.asList(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
		}
	}

	private Throwable attemptSelection(Long id, CountDownLatch start) throws InterruptedException {
		start.await();
		try { service.select(user.getId(), id); return null; }
		catch (Throwable error) { return error; }
	}

	private void assertUnavailable(Recommendation candidate) {
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId())).isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode().name()).isEqualTo("RECOMMENDATION_NO_LONGER_AVAILABLE"));
		assertUnchanged(candidate);
	}

	private void assertUnchanged(Recommendation candidate) {
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
		var stored = tasks.findById(candidate.getTask().getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		assertThat(stored.getScheduledStartAt()).isNull();
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	private User user() {
		return users.save(new User("테스터", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-hash"));
	}

	private Task task(User owner) {
		return tasks.save(Task.create(owner, "과제", TaskCategory.ASSIGNMENT, 60, DATE, null, null, null, null, null, TaskPriority.NORMAL, "설명"));
	}

	private Recommendation candidate(Task target, LocalDateTime start) {
		return recommendations.save(Recommendation.create(target.getUser(), target, start, start.plusHours(1), 1, 80, "기존 후보"));
	}

	private void connect(User owner) {
		tokens.save(CalendarToken.create(owner, "test-only-access", "test-only-refresh", DATE.plusMonths(1).atStartOfDay()));
	}

	private void setNow(LocalDateTime now) {
		when(clock.instant()).thenReturn(now.atZone(SEOUL).toInstant());
	}
}
