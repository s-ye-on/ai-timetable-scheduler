package me.timetablescheduler.domain.task.service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;
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
	private final UserReader userReader;

	@Transactional
	public TaskResponse.Create createFromNaturalLanguage(Long userId, TaskRequest.NaturalLanguage request) {
		ParsedTaskResponse response = llmParsingService.parseTask(new LlmParseRequest(request.message()));
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

		taskRepository.save(task);

		return toCreateResponse(task);
	}

	public TaskResponse.Read read(Long taskId, Long userId) {
		Task task = findTask(taskId, userId);

		return toReadResponse(task);
	}

	@Transactional
	public TaskResponse.Read update(Long taskId, TaskRequest.Update request, Long userId) {
		Task task = findTask(taskId, userId);

		task.updateDetails(
			request.title(),
			request.category(),
			request.durationMinutes(),
			request.deadline(),
			request.priority(),
			request.description()
		);

		return toReadResponse(task);
	}

	@Transactional
	public void delete(Long taskId, Long userId) {
		Task task = findTask(taskId, userId);
		taskRepository.delete(task);
	}

	private User findUser(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}

	private Task findTask(Long taskId, Long userId) {
		return taskRepository.findByIdAndUserId(taskId, userId)
			.orElseThrow(() -> new TaskException(ExceptionCode.NOT_FOUND_TASK));
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

	private TaskResponse.Read toReadResponse(Task task) {
		return new TaskResponse.Read(
			task.getId(),
			task.getTitle(),
			task.getCategory(),
			task.getDurationMinutes(),
			task.getDeadline(),
			task.getPriority(),
			task.getDescription(),
			task.getScheduledStartAt(),
			task.getScheduledEndAt(),
			task.getStatus()
		);
	}
}
