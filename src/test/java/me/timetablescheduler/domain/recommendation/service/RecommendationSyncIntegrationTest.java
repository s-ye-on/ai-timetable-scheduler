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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import me.timetablescheduler.domain.calendar.CalendarToken;
import me.timetablescheduler.domain.calendar.CalendarTokenRepository;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.dto.RecommendationResponse;
import me.timetablescheduler.domain.recommendation.policy.BusyBlock;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ApiException;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.RecommendationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t08-sync;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000")
class RecommendationSyncIntegrationTest {

	private static final LocalDate DATE = LocalDate.of(2026, 10, 20);
	private static final LocalDateTime START = DATE.atTime(10, 0);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired private RecommendationService service;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private TaskRepository tasks;
	@Autowired private UserRepository users;
	@Autowired private PreferenceRepository preferences;
	@Autowired private CalendarTokenRepository tokens;
	@Autowired private me.timetablescheduler.domain.calendar.CalendarService calendar;
	@Autowired private jakarta.persistence.EntityManagerFactory entityManagerFactory;
	@MockitoBean private me.timetablescheduler.domain.calendar.external.GoogleOAuthClient oauth;
	@MockitoBean private GoogleCalendarClient google;
	@MockitoBean private Clock clock;

	private User user;
	private Task task;

	@BeforeEach
	void 테스트_준비() {
		when(clock.getZone()).thenReturn(SEOUL);
		when(clock.instant()).thenReturn(DATE.atTime(9, 0).atZone(SEOUL).toInstant());
		user = users.save(new User("동기화 테스터", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-hash"));
		task = tasks.save(Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60, DATE, null, null, null, null, null,
			TaskPriority.NORMAL, "설명"));
		preferences.save(Preference.createDefault(user));
		tokens.save(CalendarToken.create(user, "test-only-access", "test-only-refresh", DATE.plusMonths(1).atStartOfDay()));
		when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of());
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(false);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenAnswer(invocation -> invocation.getArgument(2));
	}

	@Test
	void 등록_요청이_실패해도_예약된_ID를_유지하고_같은_ID로_재시도한다() {
		Recommendation candidate = 후보_생성();
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED))
			.thenAnswer(invocation -> invocation.getArgument(2));

		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		Recommendation failed = 저장된_후보_조회(candidate);
		String reservedEventId = failed.getGoogleEventId();
		assertThat(reservedEventId).matches("[0-9a-f]{32}");
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);

		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isEqualTo(reservedEventId);
		verify(google, times(2)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 응답이_유실되면_추가_등록_없이_예약된_이벤트를_조회해_복구한다() {
		Recommendation candidate = 후보_생성();
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenReturn(false, true);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED));

		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).matches("[0-9a-f]{32}");
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 최종_DB_커밋이_실패해도_예약을_보존하고_재시도에서_중복_등록하지_않는다() {
		Recommendation candidate = 후보_생성();
		AtomicBoolean failFinalCommit = new AtomicBoolean(true);
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenAnswer(invocation -> {
				if (failFinalCommit.getAndSet(false)) {
					TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
						@Override
						public void beforeCommit(boolean readOnly) {
							throw new IllegalStateException("T08 final persistence failure");
						}
					});
				}
				return invocation.getArgument(2);
			});
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenReturn(false, true);

		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId()))
			.isInstanceOf(RuntimeException.class);
		Recommendation reserved = 저장된_후보_조회(candidate);
		assertThat(reserved.getStatus()).isEqualTo(RecommendationStatus.SELECTED);
		assertThat(reserved.getGoogleEventId()).matches("[0-9a-f]{32}");
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.SCHEDULED);

		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 이벤트_존재가_불확실하면_동기화_실패로_표시하고_등록하지_않는다() {
		Recommendation candidate = 후보_생성();
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED));

		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).matches("[0-9a-f]{32}");
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 재시도할_때_새_Calendar_충돌이_있으면_등록하지_않고_실패한_예약을_유지한다() {
		Recommendation candidate = 후보_생성();
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED));
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);

		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new BusyBlock(START, START.plusHours(1))));
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId()))
			.isInstanceOf(RecommendationException.class)
			.satisfies(error -> assertThat(((RecommendationException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.RECOMMENDATION_NO_LONGER_AVAILABLE));
		assertThat(저장된_후보_조회(candidate).getStatus()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 예약_ID가_없는_기존_동기화_실패는_외부_호출_없이_거절한다() {
		Recommendation candidate = 후보_생성();
		candidate.select();
		candidate.failSync();
		recommendations.saveAndFlush(candidate);

		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(error -> assertThat(((ApiException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_SYNC_RECOVERY_REQUIRED));
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 동일한_예약_후보를_동시에_재시도해도_이벤트는_하나만_생성한다() throws Exception {
		Recommendation candidate = 후보_생성();
		candidate.reserveCalendarSync("abcde");
		task.schedule(START, START.plusHours(1));
		candidate.failSync();
		recommendations.saveAndFlush(candidate);
		tasks.saveAndFlush(task);

		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> 완료_대기_후_선택(start, candidate.getId()));
			var second = executor.submit(() -> 완료_대기_후_선택(start, candidate.getId()));
			start.countDown();
			assertThat(first.get(8, TimeUnit.SECONDS).status()).isEqualTo(RecommendationStatus.SYNCED);
			assertThat(second.get(8, TimeUnit.SECONDS).status()).isEqualTo(RecommendationStatus.SYNCED);
		}
		assertThat(저장된_후보_조회(candidate).getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	private RecommendationResponse.Read 완료_대기_후_선택(CountDownLatch start, Long recommendationId) throws InterruptedException {
		start.await();
		return service.select(user.getId(), recommendationId);
	}

	@Test
	void 예약_DB_커밋에_실패하면_외부_이벤트를_생성하지_않는다() {
		var candidate = 후보_생성();
		when(google.getBusyBlocks(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override public void beforeCommit(boolean readOnly) {
					throw new IllegalStateException("예약 저장 실패 재현");
				}
			});
			return List.of();
		});
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId())).isInstanceOf(IllegalStateException.class);
		assertThat(저장된_후보_조회(candidate).getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isNull();
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		verify(google, never()).hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 재시도_중_FreeBusy_장애가_나면_트랜잭션_프록시를_거쳐_동기화_실패를_저장한다() {
		var candidate = 후보_생성();
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED));
		service.select(user.getId(), candidate.getId());
		String eventId = 저장된_후보_조회(candidate).getGoogleEventId();
		when(google.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isEqualTo(eventId);
		assertThat(저장된_후보_조회(candidate).getStatus()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 기존_이벤트는_시작_후에도_자신의_FreeBusy와_충돌하지_않고_복구할_수_있다() {
		var candidate = 실패한_예약_생성();
		when(clock.instant()).thenReturn(START.plusHours(2).atZone(SEOUL).toInstant());
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any())).thenReturn(true);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isEqualTo("abcde");
		verify(google, never()).getBusyBlocks(anyString(), anyString(), any(), any());
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 존재하지_않는_이벤트는_시작_시각_후에_새로_등록할_수_없다() {
		var candidate = 실패한_예약_생성();
		when(clock.instant()).thenReturn(START.atZone(SEOUL).toInstant());
		assertThatThrownBy(() -> service.select(user.getId(), candidate.getId()))
			.isInstanceOf(RecommendationException.class).satisfies(error ->
				assertThat(((RecommendationException) error).getExceptionCode()).isEqualTo(ExceptionCode.RECOMMENDATION_NO_LONGER_AVAILABLE));
		assertThat(저장된_후보_조회(candidate).getStatus()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		verify(google, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 재연결은_미해결_예약의_Calendar를_교체할_수_없다() {
		실패한_예약_생성();
		assertThatThrownBy(() -> calendar.connect("test-only-code", user.getId()))
			.isInstanceOf(CalendarException.class).satisfies(error ->
				assertThat(((CalendarException) error).getExceptionCode()).isEqualTo(ExceptionCode.GOOGLE_CALENDAR_RECONNECT_BLOCKED));
		assertThat(tokens.findByUserId(user.getId()).orElseThrow().getAccessToken()).isEqualTo("test-only-access");
		org.mockito.Mockito.verifyNoInteractions(oauth);
	}

	@Test
	void 재연결은_동일한_사용자_락을_기다리고_동기화와_경쟁하지_않는다() throws Exception {
		var candidate = 후보_생성();
		CountDownLatch lookupStarted = new CountDownLatch(1);
		CountDownLatch releaseLookup = new CountDownLatch(1);
		CountDownLatch reconnectStarted = new CountDownLatch(1);
		when(google.hasMatchingEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenAnswer(invocation -> {
				lookupStarted.countDown();
				if (!releaseLookup.await(5, TimeUnit.SECONDS)) throw new AssertionError("조회 대기 시간 초과");
				return false;
			});
		when(google.createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_EVENT_CREATE_FAILED));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var selection = executor.submit(() -> service.select(user.getId(), candidate.getId()));
			try {
				assertThat(lookupStarted.await(3, TimeUnit.SECONDS)).isTrue();
				var reconnect = executor.submit(() -> {
					reconnectStarted.countDown();
					try { calendar.connect("test-only-code", user.getId()); return null; }
					catch (CalendarException error) { return error.getExceptionCode(); }
				});
				assertThat(reconnectStarted.await(3, TimeUnit.SECONDS)).isTrue();
				assertThatThrownBy(() -> reconnect.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
				releaseLookup.countDown();
				assertThat(selection.get(5, TimeUnit.SECONDS).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
				assertThat(reconnect.get(5, TimeUnit.SECONDS)).isEqualTo(ExceptionCode.GOOGLE_CALENDAR_RECONNECT_BLOCKED);
			} finally {
				releaseLookup.countDown();
			}
		}
		assertThat(tokens.findByUserId(user.getId()).orElseThrow().getAccessToken()).isEqualTo("test-only-access");
		org.mockito.Mockito.verifyNoInteractions(oauth);
	}

	@Test
	void 복구_중_만료된_액세스_토큰을_갱신해도_Calendar_연결은_유지한다() {
		var candidate = 실패한_예약_생성();
		var token = tokens.findByUserId(user.getId()).orElseThrow();
		token.updateToken("test-only-expired", null, DATE.minusDays(1).atStartOfDay());
		tokens.saveAndFlush(token);
		when(oauth.refreshAccessToken("test-only-refresh")).thenReturn(new com.google.api.client.auth.oauth2.TokenResponse()
			.setAccessToken("test-only-refreshed").setExpiresInSeconds(3600L));
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		var refreshed = tokens.findByUserId(user.getId()).orElseThrow();
		assertThat(refreshed.getAccessToken()).isEqualTo("test-only-refreshed");
		assertThat(refreshed.getRefreshToken()).isEqualTo("test-only-refresh");
		assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isEqualTo("abcde");
	}

	@Test
	void 오래된_OSIV_영속성_컨텍스트로_이미_동기화된_후보를_재생성할_수_없다() throws Exception {
		var candidate = 후보_생성();
		var entityManager = entityManagerFactory.createEntityManager();
		try {
			assertThat(entityManager.find(Recommendation.class, candidate.getId()).getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
			try (var executor = Executors.newSingleThreadExecutor()) {
				assertThat(executor.submit(() -> service.select(user.getId(), candidate.getId())).get(8, TimeUnit.SECONDS).status())
					.isEqualTo(RecommendationStatus.SYNCED);
			}
			String syncedId = 저장된_후보_조회(candidate).getGoogleEventId();
			TransactionSynchronizationManager.bindResource(entityManagerFactory, new org.springframework.orm.jpa.EntityManagerHolder(entityManager));
			assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
			assertThat(저장된_후보_조회(candidate).getGoogleEventId()).isEqualTo(syncedId);
			verify(google, times(1)).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
		} finally {
			TransactionSynchronizationManager.unbindResourceIfPossible(entityManagerFactory);
			entityManager.close();
		}
	}

	private Recommendation 실패한_예약_생성() {
		var candidate = 후보_생성();
		candidate.reserveCalendarSync("abcde");
		task.schedule(START, START.plusHours(1));
		candidate.failSync();
		tasks.saveAndFlush(task);
		return recommendations.saveAndFlush(candidate);
	}

	private Recommendation 후보_생성() {
		return recommendations.save(Recommendation.create(user, task, START, START.plusHours(1), 1, 80, "기존 후보"));
	}

	private Recommendation 저장된_후보_조회(Recommendation candidate) {
		return recommendations.findById(candidate.getId()).orElseThrow();
	}
}
