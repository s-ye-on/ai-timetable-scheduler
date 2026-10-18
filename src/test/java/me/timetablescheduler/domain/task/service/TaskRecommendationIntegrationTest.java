package me.timetablescheduler.domain.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import me.timetablescheduler.domain.calendar.CalendarService;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.service.RecommendationService;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.task.type.TaskStatus;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ApiException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:t06-task;MODE=MySQL;DB_CLOSE_DELAY=-1")
class TaskRecommendationIntegrationTest {

	private static final LocalDate DATE = LocalDate.of(2026, 10, 25);
	private static final LocalDateTime START = DATE.atTime(10, 0);
	@Autowired private TaskService taskService;
	@Autowired private RecommendationService recommendationService;
	@Autowired private TaskRepository tasks;
	@Autowired private RecommendationRepository recommendations;
	@Autowired private UserRepository users;
	@MockitoBean private CalendarService calendar;
	private User user;
	private Task task;

	@BeforeEach
	void 테스트_준비() {
		user = users.save(new User("테스터", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-password-hash"));
		task = Task_생성(user);
	}

	@Test
	void 조건을_변경하면_제안_후보만_만료하고_다른_Task와_이력을_보존한다() {
		var proposed = 후보_생성(task, RecommendationStatus.PROPOSED);
		var rejected = 후보_생성(task, RecommendationStatus.REJECTED);
		var expired = 후보_생성(task, RecommendationStatus.EXPIRED);
		var otherTask = Task_생성(user);
		var otherCandidate = 후보_생성(otherTask, RecommendationStatus.PROPOSED);

		var response = taskService.update(task.getId(), 수정_요청_생성(60), user.getId());

		assertThat(response.preferredDate()).isEqualTo(DATE.plusDays(1));
		assertThat(tasks.findById(task.getId()).orElseThrow().getPreferredDate()).isEqualTo(DATE.plusDays(1));
		상태_검증(proposed, RecommendationStatus.EXPIRED);
		상태_검증(rejected, RecommendationStatus.REJECTED);
		상태_검증(expired, RecommendationStatus.EXPIRED);
		상태_검증(otherCandidate, RecommendationStatus.PROPOSED);
		assertThat(recommendationService.readProposedByTask(user.getId(), task.getId())).isEmpty();
	}

	@Test
	void 조건_변경_후_이전_후보를_선택하면_Calendar_등록_없이_실패한다() {
		var oldCandidate = 후보_생성(task, RecommendationStatus.PROPOSED);
		when(calendar.isConnected(user.getId())).thenReturn(true);
		when(calendar.createEvent(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
		taskService.update(task.getId(), 수정_요청_생성(60), user.getId());

		assertThatThrownBy(() -> recommendationService.select(user.getId(), oldCandidate.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode())
				.isEqualTo(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION));
		상태_검증(oldCandidate, RecommendationStatus.EXPIRED);
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		verify(calendar, never()).createEvent(any(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 잘못된_수정은_Task와_제안_후보를_변경하지_않는다() {
		var proposed = 후보_생성(task, RecommendationStatus.PROPOSED);
		assertThatThrownBy(() -> taskService.update(task.getId(), 수정_요청_생성(45), user.getId()))
			.isInstanceOf(ApiException.class);
		var stored = tasks.findById(task.getId()).orElseThrow();
		assertThat(stored.getDurationMinutes()).isEqualTo(60);
		assertThat(stored.getPreferredDate()).isEqualTo(DATE);
		상태_검증(proposed, RecommendationStatus.PROPOSED);
	}

	@Test
	void 후보가_없는_미확정_Task를_삭제한다() {
		taskService.delete(task.getId(), user.getId());
		assertThat(tasks.existsById(task.getId())).isFalse();
		assertThat(users.existsById(user.getId())).isTrue();
	}

	@Test
	void 다른_데이터를_변경하지_않고_미확정_Task와_미확정_후보를_삭제한다() {
		var proposed = 후보_생성(task, RecommendationStatus.PROPOSED);
		var rejected = 후보_생성(task, RecommendationStatus.REJECTED);
		var expired = 후보_생성(task, RecommendationStatus.EXPIRED);
		var otherTask = Task_생성(user);
		var otherCandidate = 후보_생성(otherTask, RecommendationStatus.PROPOSED);

		taskService.delete(task.getId(), user.getId());

		assertThat(tasks.existsById(task.getId())).isFalse();
		for (var item : List.of(proposed, rejected, expired)) {
			assertThat(recommendations.existsById(item.getId())).isFalse();
		}
		assertThat(tasks.existsById(otherTask.getId())).isTrue();
		상태_검증(otherCandidate, RecommendationStatus.PROPOSED);
		assertThat(users.existsById(user.getId())).isTrue();
	}

	@ParameterizedTest
	@EnumSource(value = TaskStatus.class, names = {"SCHEDULED", "COMPLETED", "CANCELED"})
	void 보호된_상태의_Task는_후보가_없어도_삭제할_수_없다(TaskStatus state) {
		task.schedule(START, START.plusHours(1));
		if (state == TaskStatus.COMPLETED) task.complete(START.plusHours(1));
		if (state == TaskStatus.CANCELED) task.cancel();
		tasks.saveAndFlush(task);

		assertThatThrownBy(() -> taskService.delete(task.getId(), user.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode())
				.isEqualTo(ExceptionCode.INVALID_TASK_STATUS_TRANSITION));
		var stored = tasks.findById(task.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(state);
		assertThat(stored.getScheduledStartAt()).isEqualTo(START);
	}

	@ParameterizedTest
	@EnumSource(value = RecommendationStatus.class, names = {"SELECTED", "SYNCED", "SYNC_FAILED"})
	void Task가_미확정이_되어도_확정_이력이_있으면_삭제할_수_없다(RecommendationStatus state) {
		var proposed = 후보_생성(task, RecommendationStatus.PROPOSED);
		var history = 후보_생성(task, state);
		task.schedule(START, START.plusHours(1));
		task.unschedule();
		tasks.saveAndFlush(task);

		assertThatThrownBy(() -> taskService.delete(task.getId(), user.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode())
				.isEqualTo(ExceptionCode.INVALID_RECOMMENDATION_STATUS_TRANSITION));
		assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNSCHEDULED);
		상태_검증(proposed, RecommendationStatus.PROPOSED);
		상태_검증(history, state);
		assertThat(recommendations.findById(history.getId()).orElseThrow().getGoogleEventId())
			.isEqualTo(state == RecommendationStatus.SYNCED ? "test-only-event" : null);
		verify(calendar, never()).createEvent(any(), anyString(), anyString(), any(), any(), any());
	}

	@Test
	void 저장된_상태가_미확정이어도_Google_이벤트_ID가_있으면_삭제할_수_없다() {
		var linked = 후보_생성(task, RecommendationStatus.PROPOSED);
		// 과거 데이터의 상태 불일치가 있어도 외부 이벤트 식별자를 삭제하지 않는다.
		ReflectionTestUtils.setField(linked, "googleEventId", "test-only-legacy-event");
		recommendations.saveAndFlush(linked);
		assertThatThrownBy(() -> taskService.delete(task.getId(), user.getId())).isInstanceOf(ApiException.class);
		assertThat(tasks.existsById(task.getId())).isTrue();
		assertThat(recommendations.findById(linked.getId()).orElseThrow().getGoogleEventId()).isEqualTo("test-only-legacy-event");
	}

	@Test
	void 다른_사용자의_수정과_삭제로_Task나_후보를_변경할_수_없다() {
		var proposed = 후보_생성(task, RecommendationStatus.PROPOSED);
		var other = users.save(new User("다른 사용자", UUID.randomUUID().toString().substring(0, 8) + "@example.com", "test-only-password"));
		assertThatThrownBy(() -> taskService.update(task.getId(), 수정_요청_생성(60), other.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode()).isEqualTo(ExceptionCode.NOT_FOUND_TASK));
		assertThatThrownBy(() -> taskService.delete(task.getId(), other.getId()))
			.isInstanceOf(ApiException.class)
			.satisfies(e -> assertThat(((ApiException) e).getExceptionCode()).isEqualTo(ExceptionCode.NOT_FOUND_TASK));
		assertThat(tasks.findById(task.getId()).orElseThrow().getPreferredDate()).isEqualTo(DATE);
		상태_검증(proposed, RecommendationStatus.PROPOSED);
	}

	private Task Task_생성(User owner) {
		return tasks.save(Task.create(owner, "과제", TaskCategory.ASSIGNMENT, 60, DATE,
			null, null, null, null, null, TaskPriority.NORMAL, "기존 조건"));
	}

	private Recommendation 후보_생성(Task target, RecommendationStatus status) {
		var item = Recommendation.create(user, target, START, START.plusHours(1), 1, 80, "기존 후보");
		switch (status) {
			case REJECTED -> item.reject();
			case EXPIRED -> item.expire();
			case SELECTED -> item.select();
			case SYNCED -> { item.select(); item.sync("test-only-event"); }
			case SYNC_FAILED -> { item.select(); item.failSync(); }
			case PROPOSED -> { }
		}
		return recommendations.save(item);
	}

	private TaskRequest.Update 수정_요청_생성(int duration) {
		return new TaskRequest.Update("과제", TaskCategory.ASSIGNMENT, duration, DATE.plusDays(1),
			null, null, null, null, null, TaskPriority.NORMAL, "변경 조건");
	}

	private void 상태_검증(Recommendation candidate, RecommendationStatus status) {
		assertThat(recommendations.findById(candidate.getId()).orElseThrow().getStatus()).isEqualTo(status);
	}
}
