package me.timetablescheduler.domain.task.service;

import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TaskServiceTest {

	private TaskRepository taskRepository;
	private LlmParsingService llmParsingService;
	private UserReader userReader;
	private TaskService taskService;

	@BeforeEach
	void 테스트_준비() {
		taskRepository = Mockito.mock(TaskRepository.class);
		llmParsingService = Mockito.mock(LlmParsingService.class);
		userReader = Mockito.mock(UserReader.class);
		taskService = new TaskService(taskRepository, Mockito.mock(RecommendationRepository.class), llmParsingService, userReader);
	}

	@Test
	void 자연어_생성시_LLM_선호시간대가_null이면_Task에도_null로_저장한다() {
		User user = 사용자_생성();
		ParsedTaskResponse parsed = 분석_응답_생성(null);
		when(userReader.read(1L)).thenReturn(user);
		when(llmParsingService.parseTask(any(LlmParseRequest.class))).thenReturn(parsed);
		when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TaskResponse.Create response = taskService.createFromNaturalLanguage(
			1L,
			new TaskRequest.NaturalLanguage("화요일에 과제할 시간 잡아줘")
		);

		ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
		Mockito.verify(taskRepository).save(taskCaptor.capture());
		assertNull(taskCaptor.getValue().getPreferredTimeRange());
		assertNull(response.preferredTimeRange());
	}

	@Test
	void 수정시_선호시간대를_null로_변경할_수_있다() {
		Task task = 선호_시간대가_있는_Task_생성(PreferredTimeRange.AFTERNOON);
		TaskRequest.Update request = new TaskRequest.Update(
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"화요일에 과제할 시간 잡아줘"
		);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));

		TaskResponse.Read response = taskService.update(10L, request, 1L);

		assertNull(task.getPreferredTimeRange());
		assertNull(response.preferredTimeRange());
	}

	@Test
	void 자연어_생성시_필수조건과_명시시각을_Task에_보존한다() {
		User user = 사용자_생성();
		ParsedTaskResponse parsed = new ParsedTaskResponse(
			"알고리즘 공부", TaskCategory.STUDY, 120, null, DayOfWeek.THURSDAY, null,
			PreferredTimeRange.EVENING, null, TaskPriority.NORMAL,
			"목요일 저녁 7시 10분에 알고리즘 공부 2시간", true, true, LocalTime.of(19, 10), null
		);
		when(userReader.read(1L)).thenReturn(user);
		when(llmParsingService.parseTask(any(LlmParseRequest.class))).thenReturn(parsed);
		when(taskRepository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TaskResponse.Create response = taskService.createFromNaturalLanguage(
			1L, new TaskRequest.NaturalLanguage("목요일 저녁 7시 10분에 알고리즘 공부 2시간")
		);

		ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
		Mockito.verify(taskRepository).save(taskCaptor.capture());
		assertEquals(Boolean.TRUE, taskCaptor.getValue().getDateRequired());
		assertEquals(Boolean.TRUE, taskCaptor.getValue().getTimeRequired());
		assertEquals(LocalTime.of(19, 10), taskCaptor.getValue().getPreferredStartTime());
		assertEquals(LocalTime.of(19, 10), response.preferredStartTime());
	}

	@Test
	void 수정_요청에_새_조건필드가_없으면_기존_명시시각과_필수조건을_보존한다() {
		Task task = Task.create(
			사용자_생성(), "공부", TaskCategory.STUDY, 60, null, DayOfWeek.THURSDAY,
			null, null, PreferredTimeRange.EVENING, null, TaskPriority.NORMAL, "목요일 저녁 7시 공부",
			true, true, LocalTime.of(19, 0)
		);
		TaskRequest.Update request = new TaskRequest.Update(
			"공부", TaskCategory.STUDY, 60, null, DayOfWeek.THURSDAY, null, null,
			PreferredTimeRange.EVENING, null, null, null, false, null, TaskPriority.NORMAL, "목요일 저녁 공부"
		);
		when(taskRepository.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(task));

		TaskResponse.Read response = taskService.update(10L, request, 1L);

		assertEquals(Boolean.TRUE, response.dateRequired());
		assertEquals(Boolean.TRUE, response.timeRequired());
		assertEquals(LocalTime.of(19, 0), response.preferredStartTime());
	}

	@Test
	void 사용자의_Task_목록을_최신순으로_조회한다() {
		Task firstTask = 제목이_있는_Task_생성("첫 번째 과제");
		Task secondTask = 제목이_있는_Task_생성("두 번째 과제");
		when(taskRepository.findAllByUserIdOrderByCreatedAtDesc(1L))
			.thenReturn(List.of(secondTask, firstTask));

		List<TaskResponse.Read> responses = taskService.readAll(1L);

		assertEquals(2, responses.size());
		assertEquals("두 번째 과제", responses.get(0).title());
		assertEquals("첫 번째 과제", responses.get(1).title());
	}

	private ParsedTaskResponse 분석_응답_생성(PreferredTimeRange preferredTimeRange) {
		return new ParsedTaskResponse(
			"과제",
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			preferredTimeRange,
			null,
			TaskPriority.NORMAL,
			"화요일에 과제할 시간 잡아줘"
		);
	}

	private Task 선호_시간대가_있는_Task_생성(PreferredTimeRange preferredTimeRange) {
		return 제목과_선호_시간대가_있는_Task_생성("과제", preferredTimeRange);
	}

	private Task 제목이_있는_Task_생성(String title) {
		return 제목과_선호_시간대가_있는_Task_생성(title, null);
	}

	private Task 제목과_선호_시간대가_있는_Task_생성(String title, PreferredTimeRange preferredTimeRange) {
		return Task.create(
			사용자_생성(),
			title,
			TaskCategory.ASSIGNMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			null,
			preferredTimeRange,
			null,
			TaskPriority.NORMAL,
			"화요일 오후에 과제할 시간 잡아줘"
		);
	}

	private User 사용자_생성() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
