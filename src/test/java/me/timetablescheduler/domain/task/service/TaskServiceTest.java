package me.timetablescheduler.domain.task.service;

import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class TaskServiceTest {

	private TaskRepository taskRepository;
	private LlmParsingService llmParsingService;
	private UserReader userReader;
	private TaskService taskService;

	@BeforeEach
	void setUp() {
		taskRepository = Mockito.mock(TaskRepository.class);
		llmParsingService = Mockito.mock(LlmParsingService.class);
		userReader = Mockito.mock(UserReader.class);
		taskService = new TaskService(taskRepository, llmParsingService, userReader);
	}

	@Test
	void 자연어_생성시_LLM_선호시간대가_null이면_Task에도_null로_저장한다() {
		User user = user();
		ParsedTaskResponse parsed = parsedResponse(null);
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
		Task task = taskWithPreferredTimeRange(PreferredTimeRange.AFTERNOON);
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

	private ParsedTaskResponse parsedResponse(PreferredTimeRange preferredTimeRange) {
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

	private Task taskWithPreferredTimeRange(PreferredTimeRange preferredTimeRange) {
		return Task.create(
			user(),
			"과제",
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

	private User user() {
		return new User("Tester", "tester@example.com", "encodedPassword");
	}
}
