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
	void 테스트_준비() {
		when(clock.instant()).thenReturn(Instant.parse("2026-10-19T00:00:00Z"));
		when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		user = users.save(new User("테스터", "t05-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-password-hash"));
		task = tasks.save(Task.create(user, "과제", TaskCategory.ASSIGNMENT, 60, LocalDate.of(2026, 10, 20),
			null, null, null, null, null, TaskPriority.NORMAL, "테스트 일정"));
	}

	@Test
	void 미연동_사용자는_추천할_수_있지만_확정하거나_저장_상태를_변경할_수_없다() {
		var result = service.recommend(user.getId(), task.getId());
		assertThat(result.recommendations()).hasSize(3);
		Long id = result.recommendations().getFirst().id();
		assertThatThrownBy(() -> service.select(user.getId(), id)).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_NOT_CONNECTED));
		Task_미확정_검증();
		assertThat(recommendations.findById(id).orElseThrow().getStatus()).isEqualTo(RecommendationStatus.PROPOSED);
		이벤트_미생성_검증();
		verify(calendarClient, never()).getBusyBlocks(anyString(), anyString(), any(), any());
	}

	@Test
	void 연결된_Calendar에_일정이_없으면_자동_확정_없이_추천한다() {
		연결_설정();
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any())).thenReturn(List.of());
		var result = service.recommend(user.getId(), task.getId());
		assertThat(result.recommendations()).hasSize(3);
		assertThat(recommendations.findAllByTaskIdAndUserIdAndStatus(task.getId(), user.getId(), RecommendationStatus.PROPOSED))
			.hasSize(3);
		Task_미확정_검증();
		이벤트_미생성_검증();
	}

	@Test
	void FreeBusy_조회에_실패하면_새_추천을_차단하고_기존_후보를_보존한다() {
		연결_설정();
		var previous = recommendations.save(Recommendation.create(user, task,
			LocalDateTime.of(2026, 10, 20, 10, 0), LocalDateTime.of(2026, 10, 20, 11, 0), 1, 80, "기존 후보"));
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));

		assertThatThrownBy(() -> service.recommend(user.getId(), task.getId())).isInstanceOf(CalendarException.class)
			.satisfies(error -> assertThat(((CalendarException) error).getExceptionCode())
				.isEqualTo(ExceptionCode.GOOGLE_CALENDAR_FREE_BUSY_FAILED));
		assertThat(recommendations.findAllByTaskIdAndUserIdAndStatus(task.getId(), user.getId(), RecommendationStatus.PROPOSED))
			.extracting(Recommendation::getId).containsExactly(previous.getId());
		Task_미확정_검증();
		이벤트_미생성_검증();
	}

	private void 연결_설정() {
		tokens.save(CalendarToken.create(user, "test-only-access", "test-only-refresh", LocalDateTime.of(2026, 10, 30, 0, 0)));
	}

	@Test
	void 원조건이_Calendar와_겹치면_실제_사유와_대안을_함께_안내한다() {
		연결_설정();
		var date = LocalDate.of(2026, 10, 22);
		var requested = tasks.save(Task.create(user, "공부", TaskCategory.STUDY, 120, date,
			null, null, null, null, null, TaskPriority.NORMAL, "목요일 19시 공부", false, false, java.time.LocalTime.of(19, 0)));
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new me.timetablescheduler.domain.recommendation.policy.BusyBlock(date.atTime(19, 0), date.atTime(20, 0))));
		var result = service.recommend(user.getId(), requested.getId());
		assertThat(result.message()).contains("Google Calendar", "겹쳐", "다른 대안");
		assertThat(result.recommendations()).isNotEmpty().noneMatch(r -> r.recommendedStartAt().equals(date.atTime(19, 0)));
		assertThat(result.recommendations()).allMatch(r -> r.status() == RecommendationStatus.PROPOSED);
		이벤트_미생성_검증();
	}

	@Test
	void 필수_원조건이_충돌하면_사유를_안내하되_대안을_추천했다고_하지_않는다() {
		연결_설정();
		var date = LocalDate.of(2026, 10, 22);
		var requested = tasks.save(Task.create(user, "공부", TaskCategory.STUDY, 120, date,
			null, null, null, null, null, TaskPriority.NORMAL, "목요일 19시만", true, true, java.time.LocalTime.of(19, 0)));
		when(calendarClient.getBusyBlocks(anyString(), anyString(), any(), any()))
			.thenReturn(List.of(new me.timetablescheduler.domain.recommendation.policy.BusyBlock(date.atTime(19, 0), date.atTime(20, 0))));
		var result = service.recommend(user.getId(), requested.getId());
		assertThat(result.message()).contains("Google Calendar", "겹쳐").doesNotContain("다른 대안을 추천");
		assertThat(result.recommendations()).isEmpty();
		이벤트_미생성_검증();
	}

	private void Task_미확정_검증() {
		var stored = tasks.findById(task.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		assertThat(stored.getScheduledStartAt()).isNull();
		assertThat(stored.getScheduledEndAt()).isNull();
	}

	private void 이벤트_미생성_검증() {
		verify(calendarClient, never()).createEvent(anyString(), anyString(), anyString(), anyString(), any(), any(), any());
	}
}
