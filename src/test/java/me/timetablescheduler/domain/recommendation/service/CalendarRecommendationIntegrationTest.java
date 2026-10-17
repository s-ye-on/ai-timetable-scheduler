package me.timetablescheduler.domain.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import me.timetablescheduler.domain.calendar.CalendarToken;
import me.timetablescheduler.domain.calendar.CalendarTokenRepository;
import me.timetablescheduler.domain.calendar.external.GoogleCalendarClient;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t05-calendar;MODE=MySQL;DB_CLOSE_DELAY=-1")
class CalendarRecommendationIntegrationTest {

	@Autowired private RecommendationService service;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private TaskRepository tasks;
	@Autowired private UserRepository users;
	@Autowired private CalendarTokenRepository tokens;
	@MockitoBean private GoogleCalendarClient calendarClient;
	@MockitoBean private Clock clock;
	private User user;
	private Task task;

	@BeforeEach
	void setUp() {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-19T00:00:00Z"));
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		user = users.save(new User("테스터", "t05-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-password-hash"));
		task = tasks.save(Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60, LocalDate.of(2026, 10, 20),
			null, null, null, null, null, TaskPriority.NORMAL, "테스트 일정"));
	}

	@Test
	void disconnectedUserCanGenerateButCannotFinalizeOrChangeStoredState() {
		var result = service.recommend(user.getId(), task.getId());
		assertThat(result.recommendations()).hasSize(3);
		Long id = result.recommendations().getFirst().id();
		assertThatThrownBy(() -> service.select(user.getId(), id)).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED));
		assertTaskUnscheduled();
		assertThat(recommendations.findById(id).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
		assertNoEventCreated();
		verify(calendarClient, never()).getBusyBlocks(anyString(), anyString(), any(), any());
	}

	@Test
	void connectedEmptyCalendarAllowsRecommendationsWithoutAutoScheduling() {
		connect();
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of());
		var result = service.recommend(user.getId(), task.getId());
		assertThat(result.recommendations()).hasSize(3);
		assertThat(recommendations.findAllByTaskIdAndUserIdAndStatus(task.getId(), user.getId(), RecommendationStatus.PROPOSED))
			.hasSize(3);
		assertTaskUnscheduled();
		assertNoEventCreated();
	}

	@Test
	void failedFreeBusyBlocksNewRecommendationsAndPreservesExistingCandidates() {
		connect();
		var previous = recommendations.save(Recommendation.create(user, task,
			LocalDateTime.of(2026, 10, 20, 10, 0), LocalDateTime.of(2026, 10, 20, 11, 0), 1, 80, "기존 후보"));
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));

		assertThatThrownBy(() -> service.recommend(user.getId(), task.getId())).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
		assertThat(recommendations.findAllByTaskIdAndUserIdAndStatus(task.getId(), user.getId(), RecommendationStatus.PROPOSED))
			.extracting(Recommendation::getId).containsExactly(previous.getId());
		assertTaskUnscheduled();
		assertNoEventCreated();
	}

	private void connect() {
		tokens.save(CalendarToken.create(user, "test-only-access", "test-only-refresh", LocalDateTime.of(2026, 10, 30, 0, 0)));
	}

	private void assertTaskUnscheduled() {
		var stored = tasks.findById(task.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		assertThat(stored.getScheduledStartAt()).isNull();
		assertThat(stored.getScheduledEndAt()).isNull();
	}

	private void assertNoEventCreated() {
		verify(calendarClient, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}
}
