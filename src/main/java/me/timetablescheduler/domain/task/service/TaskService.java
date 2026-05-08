package me.timetablescheduler.domain.task.service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {
	private final TaskRepository taskRepository;
	private final UserRepository userRepository;
	private final LlmParsingService llmParsingService;

	@Transactional
	public TaskResponse.Create createFromNaturalLanguage(Long userId, LlmParseRequest llmParseRequest) {
		ParsedTaskResponse response = llmParsingService.parseTask(llmParseRequest);
		User user = findUser(userId);

		Task task = Task.create(
			user,
			response.title(),
			response.category(),
			response.durationMinutes(),
			response.deadline(),
			response.priority(),
			response.description()
		);

		return toCreateResponse(taskRepository.save(task));
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}

	private TaskResponse.Create toCreateResponse(Task task) {
		return new TaskResponse.Create(
			task.getId(),
			task.getTitle(),
			task.getCategory(),
			task.getDurationMinutes(),
			task.getDeadline(),
			task.getPriority(),
			task.getDescription(),
			task.getStatus()
		);
	}
}
