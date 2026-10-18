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
	void 테스트_준비() {
		eventStore.초기화();
		when(clock.getZone()).thenReturn(SEOUL);
		when(clock.instant()).thenReturn(DATE.atTime(9, 0).atZone(SEOUL).toInstant());
		user = users.save(new User("테스터", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-hash"));
		task = tasks.save(Task.create(user, "동기화 과제", TaskCategory.ASSIGNMENT, 60, DATE, null, null,
			null, null, null, TaskPriority.NORMAL, "설명"));
		preferences.save(Preference.createDefault(user));
		tokens.save(CalendarToken.create(user, "test-only-access", "test-only-refresh", DATE.plusDays(1).atStartOfDay()));
	}

	@Test
	void 생성_응답이_유실되면_고정된_Google_이벤트_ID를_조회해_복구한다() {
		Recommendation candidate = 후보_생성();
		eventStore.다음_등록_응답_유실_설정();

		service.select(user.getId(), candidate.getId());

		Recommendation stored = recommendations.findById(candidate.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(stored.getGoogleEventId()).isNotBlank();
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
		assertThat(eventStore.이벤트_존재_여부(stored.getGoogleEventId())).isTrue();
	}

	@Test
	void 동기화_실패를_재시도하면_새로_생성하지_않고_기존_이벤트를_찾는다() {
		Recommendation candidate = 후보_생성();
		eventStore.다음_등록_응답_유실_설정();
		eventStore.지정_조회_실패_설정(2);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
	}

	@Test
	void Google_등록_후_DB_커밋에_실패해도_중복_이벤트_없이_재시도한다() {
		Recommendation candidate = 후보_생성();
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
		assertThat(eventStore.이벤트_존재_여부(reservedId)).isTrue();
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus())
			.isEqualTo(me.timetablescheduler.domain.task.type.TaskStatus.SCHEDULED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getGoogleEventId()).isEqualTo(reservedId);

		service.select(user.getId(), candidate.getId());

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
	}

	@Test
	void 동시_선택과_응답_유실이_발생해도_Google_이벤트는_하나만_생성한다() throws Exception {
		Recommendation candidate = 후보_생성();
		eventStore.다음_등록_응답_유실_설정();
		CountDownLatch start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> 완료_대기_후_선택(start, candidate.getId()));
			var second = executor.submit(() -> 완료_대기_후_선택(start, candidate.getId()));
			start.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS)).isNull();
			assertThat(second.get(10, TimeUnit.SECONDS)).isNull();
		}

		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus())
			.isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
	}

	private Throwable 완료_대기_후_선택(CountDownLatch start, Long recommendationId) {
		try {
			start.await();
			service.select(user.getId(), recommendationId);
			return null;
		} catch (Throwable error) {
			return error;
		}
	}

	@Test
	void 등록_충돌은_기존_이벤트를_검증한_경우에만_복구한다() {
		var candidate = 후보_생성();
		eventStore.conflictNextInsert.set(true);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNCED);
		assertThat(eventStore.등록_횟수()).isEqualTo(1);
		assertThat(eventStore.events).hasSize(1);
		assertThat(eventStore.lookups.get()).isEqualTo(2);
	}

	@Test
	void 조회_결과가_불확실하면_이벤트를_등록하지_않는다() {
		var candidate = 후보_생성();
		eventStore.지정_조회_실패_설정(1);
		assertThat(service.select(user.getId(), candidate.getId()).status()).isEqualTo(RecommendationStatus.SYNC_FAILED);
		assertThat(eventStore.events).isEmpty();
		assertThat(eventStore.등록_횟수()).isZero();
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getGoogleEventId()).isNotBlank();
	}

	private Recommendation 후보_생성() {
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

		void 초기화() {
			events.clear();
			inserts.set(0);
			lookups.set(0);
			loseNextInsert.set(false);
			conflictNextInsert.set(false);
			failedLookupCall = -1;
		}

		void 다음_등록_응답_유실_설정() {
			loseNextInsert.set(true);
		}

		void 지정_조회_실패_설정(int call) {
			failedLookupCall = call;
		}

		int 등록_횟수() {
			return inserts.get();
		}

		boolean 이벤트_존재_여부(String eventId) {
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
				return 응답_생성(200, "{\"calendars\":{\"primary\":{\"busy\":[]}}}");
			}
			if ("POST".equals(method) && url.endsWith("/events")) {
				String body = 요청_본문();
				String eventId = 이벤트_ID_추출(body);
				eventStore.inserts.incrementAndGet();
				if (eventStore.events.putIfAbsent(eventId, body) != null
					|| eventStore.conflictNextInsert.getAndSet(false)) {
					return 응답_생성(409, "{\"error\":{\"message\":\"duplicate\"}}");
				}
				if (eventStore.loseNextInsert.compareAndSet(true, false)) {
					throw new java.net.SocketTimeoutException("test-only lost create response");
				}
				return 응답_생성(200, body);
			}
			if ("GET".equals(method) && url.contains("/events/")) {
				int call = eventStore.lookups.incrementAndGet();
				if (call == eventStore.failedLookupCall) {
					throw new java.net.SocketTimeoutException("test-only lookup failure");
				}
				String eventId = url.substring(url.lastIndexOf('/') + 1);
				String body = eventStore.events.get(eventId);
				return body == null ? 응답_생성(404, "{\"error\":{\"message\":\"not found\"}}") : 응답_생성(200, body);
			}
			return 응답_생성(500, "{\"error\":{\"message\":\"unexpected request\"}}");
		}

		private String 요청_본문() throws IOException {
			return getContentAsString();
		}

		private String 이벤트_ID_추출(String body) {
			Matcher matcher = EventStore.ID_FIELD.matcher(body);
			if (!matcher.find()) {
				throw new AssertionError("Google 이벤트 생성 요청에 ID가 없습니다.");
			}
			return matcher.group(1);
		}

		private LowLevelHttpResponse 응답_생성(int status, String body) {
			return new MockLowLevelHttpResponse().setStatusCode(status)
				.setContentType("application/json").setContent(body);
		}
	}
}
