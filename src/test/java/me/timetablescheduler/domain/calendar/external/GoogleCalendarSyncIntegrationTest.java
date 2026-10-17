package me.timetablescheduler.domain.calendar.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import me.timetablescheduler.TimetableSchedulerApplication;
import me.timetablescheduler.domain.calendar.CalendarToken;
import me.timetablescheduler.domain.calendar.CalendarTokenRepository;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.PreferenceRepository;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.recommendation.service.RecommendationService;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
	classes = {TimetableSchedulerApplication.class, GoogleCalendarSyncIntegrationTest.CalendarStubConfiguration.class},
	properties = "spring.datasource.url=jdbc:h2:mem:t08-calendar-sync;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000"
)
class GoogleCalendarSyncIntegrationTest {

	private static final LocalDate DATE = LocalDate.of(2026, 10, 22);
	private static final LocalDateTime START = DATE.atTime(10, 0);
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired private RecommendationService service;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private TaskRepository tasks;
	@Autowired private UserRepository users;
	@Autowired private PreferenceRepository preferences;
	@Autowired private CalendarTokenRepository tokens;
	@Autowired private EventStore eventStore;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
	@MockitoBean private Clock clock;

	private User user;
	private Task task;

	@BeforeEach
	void setUp() {
		eventStore.reset();
		when(clock.getZone()).thenReturn(SEOUL);
		when(clock.instant()).thenReturn(DATE.atTime(9, 0).atZone(SEOUL).toInstant());
		user = users.save(new User("테스터", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-hash"));
		task = tasks.save(Task.create(user, "동기화 과제", TaskCategory.ASSIGNMENT, 60, DATE, null, null,
			null, null, null, TaskPriority.NORMAL, "설명"));
		preferences.save(Preference.createDefault(user));
		tokens.save(CalendarToken.create(user, "test-only-access", "test-only-refresh", DATE.plusDays(1).atStartOfDay()));
	}

	@Test
	void lostCreateResponseIsRecoveredByLookingUpTheStableGoogleEventId() {
		Recommendation candidate = candidate();
		eventStore.loseNextInsertResponse();

		service.select(user.getId(), candidate.getId());

		Recommendation stored = recommendations.findById(candidate.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(stored.getGoogleEventId()).isNotBlank();
		assertThat(eventStore.insertCount()).isEqualTo(1);
		assertThat(eventStore.contains(stored.getGoogleEventId())).isTrue();
	}

	@Test
	void syncFailedRetryFindsTheExistingEventWithoutCreatingAnotherOne() {
		Recommendation candidate = candidate();
		eventStore.loseNextInsertResponse();
		eventStore.failLookupOnCall(2);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(eventStore.insertCount()).isEqualTo(1);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.insertCount()).isEqualTo(1);
	}

	@Test
	void databaseCommitFailureAfterGoogleInsertCanBeRetriedWithoutDuplicateEventCreation() {
		Recommendation candidate = candidate();
		String constraint = "t08_sync_" + candidate.getId();
		// 격리 H2에서 실제 UPDATE를 거부한다. 예약 SELECTED 저장은 허용한다.
		jdbc.execute("ALTER TABLE recommendation ADD CONSTRAINT " + constraint
			+ " CHECK (id <> " + candidate.getId() + " OR status <> 'SYNCED')");
		try {
			assertThatThrownBy(() -> service.select(user.getId(), candidate.getId()))
				.isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			jdbc.execute("ALTER TABLE recommendation DROP CONSTRAINT " + constraint);
		}
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SELECTED);
		String reservedId = recommendations.findById(candidate.getId()).orElseThrow().getGoogleEventId();
		assertThat(eventStore.contains(reservedId)).isTrue();
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus())
			.isEqualTo(me.timetablescheduler.domain.task.type.TaskStatus.SCHEDULED);
		assertThat(eventStore.insertCount()).isEqualTo(1);
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getGoogleEventId()).isEqualTo(reservedId);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.insertCount()).isEqualTo(1);
	}

	@Test
	void simultaneousSelectionAndResponseLossStillCreatesExactlyOneGoogleEvent() throws Exception {
		Recommendation candidate = candidate();
		eventStore.loseNextInsertResponse();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> selectAfter(start, candidate.getId()));
			var second = executor.submit(() -> selectAfter(start, candidate.getId()));
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
			assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
		}

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.insertCount()).isEqualTo(1);
	}

	private Throwable selectAfter(CountDownLatch start, Long recommendationId) {
		try {
			start.await();
			service.select(user.getId(), recommendationId);
			return null;
		} catch (Throwable error) {
			return error;
		}
	}

	@Test
	void insertConflictIsRecoveredOnlyByVerifyingTheExistingEvent() {
		var candidate = candidate();
		eventStore.conflictNextInsert.set(true);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.insertCount()).isEqualTo(1);
		assertThat(eventStore.events).hasSize(1);
		assertThat(eventStore.lookups.get()).isEqualTo(2);
	}

	@Test
	void unknownLookupResultDoesNotInsertAnEvent() {
		var candidate = candidate();
		eventStore.failLookupOnCall(1);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(eventStore.events).isEmpty();
		assertThat(eventStore.insertCount()).isZero();
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getGoogleEventId()).isNotBlank();
	}

	private Recommendation candidate() {
		return recommendations.save(Recommendation.create(user, task, START, START.plusHours(1), 1, 80, "기존 후보"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CalendarStubConfiguration {
		@Bean
		EventStore eventStore() {
			return new EventStore();
		}

		@Bean
		@Primary
		GoogleCalendarClient googleCalendarClient(EventStore eventStore) {
			GoogleCalendarProperties properties = new GoogleCalendarProperties("test-client", "test-only-secret",
				"http://localhost/callback", "calendar-scope", "test-app", "Asia/Seoul");
			return new GoogleCalendarClient(properties, eventStore.transport());
		}
	}

	static class EventStore {
		private static final Pattern ID_FIELD = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-v]+)\\\"");
		private final Map<String, String> events = new ConcurrentHashMap<>();
		private final AtomicInteger inserts = new AtomicInteger();
		private final AtomicInteger lookups = new AtomicInteger();
		private final AtomicBoolean loseNextInsert = new AtomicBoolean();
		private final AtomicBoolean conflictNextInsert = new AtomicBoolean();
		private volatile int failedLookupCall = -1;

		HttpTransport transport() {
			return new HttpTransport() {
				@Override
				protected LowLevelHttpRequest buildRequest(String method, String url) {
					return new StubRequest(method, url, EventStore.this);
				}
			};
		}

		void reset() {
			events.clear();
			inserts.set(0);
			lookups.set(0);
			loseNextInsert.set(false);
			conflictNextInsert.set(false);
			failedLookupCall = -1;
		}

		void loseNextInsertResponse() {
			loseNextInsert.set(true);
		}

		void failLookupOnCall(int call) {
			failedLookupCall = call;
		}

		int insertCount() {
			return inserts.get();
		}

		boolean contains(String eventId) {
			return events.containsKey(eventId);
		}
	}

	static class StubRequest extends com.google.api.client.testing.http.MockLowLevelHttpRequest {
		private final String method;
		private final String url;
		private final EventStore eventStore;

		StubRequest(String method, String url, EventStore eventStore) {
			this.method = method;
			this.url = url;
			this.eventStore = eventStore;
		}

		@Override
		public LowLevelHttpResponse execute() throws IOException {
			if (url.contains("/freeBusy")) {
				return response(200, "{\"calendars\":{\"primary\":{\"busy\":[]}}}");
			}
			if ("POST".equals(method) && url.endsWith("/events")) {
				String body = requestBody();
				String eventId = idFrom(body);
				eventStore.inserts.incrementAndGet();
				if (eventStore.events.putIfAbsent(eventId, body) != null
					|| eventStore.conflictNextInsert.getAndSet(false)) {
					return response(409, "{\"error\":{\"message\":\"duplicate\"}}");
				}
				if (eventStore.loseNextInsert.compareAndSet(true, false)) {
					throw new java.net.SocketTimeoutException("test-only lost create response");
				}
				return response(200, body);
			}
			if ("GET".equals(method) && url.contains("/events/")) {
				int call = eventStore.lookups.incrementAndGet();
				if (call == eventStore.failedLookupCall) {
					throw new java.net.SocketTimeoutException("test-only lookup failure");
				}
				String eventId = url.substring(url.lastIndexOf('/') + 1);
				String body = eventStore.events.get(eventId);
				return body == null ? response(404, "{\"error\":{\"message\":\"not found\"}}") : response(200, body);
			}
			return response(500, "{\"error\":{\"message\":\"unexpected request\"}}");
		}

		private String requestBody() throws IOException {
			return getContentAsString();
		}

		private String idFrom(String body) {
			Matcher matcher = EventStore.ID_FIELD.matcher(body);
			if (!matcher.find()) {
				throw new AssertionError("Google 이벤트 생성 요청에 ID가 없습니다.");
			}
			return matcher.group(1);
		}

		private LowLevelHttpResponse response(int status, String body) {
			return new MockLowLevelHttpResponse().setStatusCode(status)
				.setContentType("application/json").setContent(body);
		}
	}
}
