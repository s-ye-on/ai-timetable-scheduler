package me.timetablescheduler.domain.task.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.recommendation.type.RecommendationStatus;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.reader.UserReader;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.TaskException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TaskService {
	private final TaskRepository taskRepository;
	private final RecommendationRepository recommendationRepository;
	private final LlmParsingService llmParsingService;
	private final UserReader userReader;

	@Transactional
	public TaskResponse.Create createFromNaturalLanguage(Long userId, TaskRequest.NaturalLanguage request) {
		ParsedTaskResponse response = llmParsingService.parseTask(new LlmParseRequest(request.message()));
		User user = userReader.read(userId);

		Task task = Task.create(
			user,
			response.title(),
			response.category(),
			response.durationMinutes(),
			response.preferredDate(),
			response.preferredDayOfWeek(),
			getPreferredStartDate(response.preferredDateRange()),
			getPreferredEndDate(response.preferredDateRange()),
			response.preferredTimeRange(),
			response.deadline(),
			response.priority(),
			response.description(),
			response.dateRequired(),
			response.timeRequired(),
			response.preferredStartTime()
		);

		taskRepository.save(task);

		return toCreateResponse(task);
	}

	public TaskResponse.Read read(Long taskId, Long userId) {
		Task task = findTask(taskId, userId);

		return toReadResponse(task);
	}

	public List<TaskResponse.Read> readAll(Long userId) {
		return taskRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
			.stream()
			.map(this::toReadResponse)
			.toList();
	}

	@Transactional
	public TaskResponse.Read update(Long taskId, TaskRequest.Update request, Long userId) {
		userReader.readForUpdate(userId);
		Task task = findTask(taskId, userId);
		validatePreferredStartTimeUpdate(request);

		task.updateDetails(
			request.title(),
			request.category(),
			request.durationMinutes(),
			request.preferredDate(),
			request.preferredDayOfWeek(),
			request.preferredStartDate(),
			request.preferredEndDate(),
			request.preferredTimeRange(),
			request.deadline(),
			request.priority(),
			request.description(),
			resolveDateRequired(task, request),
			resolveTimeRequired(task, request),
			resolvePreferredStartTime(task, request)
		);

		recommendationRepository.findAllByTaskIdAndUserIdAndStatus(taskId, userId, RecommendationStatus.PROPOSED)
			.forEach(Recommendation::expire);

		return toReadResponse(task);
	}

	@Transactional
	public void delete(Long taskId, Long userId) {
		userReader.readForUpdate(userId);
		Task task = findTask(taskId, userId);
		task.validateDeletable();
		List<Recommendation> recommendations = recommendationRepository.findAllByTaskIdAndUserId(taskId, userId);
		recommendations.forEach(Recommendation::validateDeletable);

		recommendationRepository.deleteAll(recommendations);
		taskRepository.delete(task);
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
			task.getPreferredDate(),
			task.getPreferredDayOfWeek(),
			task.getPreferredStartDate(),
			task.getPreferredEndDate(),
			task.getPreferredTimeRange(),
			task.getDeadline(),
			task.getPriority(),
			task.getDescription(),
			task.getDateRequired(),
			task.getTimeRequired(),
			task.getPreferredStartTime(),
			task.getStatus()
		);
	}

	private TaskResponse.Read toReadResponse(Task task) {
		return new TaskResponse.Read(
			task.getId(),
			task.getTitle(),
			task.getCategory(),
			task.getDurationMinutes(),
			task.getPreferredDate(),
			task.getPreferredDayOfWeek(),
			task.getPreferredStartDate(),
			task.getPreferredEndDate(),
			task.getPreferredTimeRange(),
			task.getDeadline(),
			task.getPriority(),
			task.getDescription(),
			task.getDateRequired(),
			task.getTimeRequired(),
			task.getPreferredStartTime(),
			task.getScheduledStartAt(),
			task.getScheduledEndAt(),
			task.getStatus()
		);
	}

	private void validatePreferredStartTimeUpdate(TaskRequest.Update request) {
		if (Boolean.TRUE.equals(request.clearPreferredStartTime()) && request.preferredStartTime() != null) {
			throw new TaskException(ExceptionCode.INVALID_TASK);
		}
	}

	private Boolean resolveDateRequired(Task task, TaskRequest.Update request) {
		return request.dateRequired() == null ? task.getDateRequired() : request.dateRequired();
	}

	private Boolean resolveTimeRequired(Task task, TaskRequest.Update request) {
		return request.timeRequired() == null ? task.getTimeRequired() : request.timeRequired();
	}

	private LocalTime resolvePreferredStartTime(Task task, TaskRequest.Update request) {
		if (Boolean.TRUE.equals(request.clearPreferredStartTime())) {
			return null;
		}
		return request.preferredStartTime() == null ? task.getPreferredStartTime() : request.preferredStartTime();
	}

	private LocalDate getPreferredStartDate(DateRange dateRange) {
		if (dateRange == null) {
			return null;
		}

		return dateRange.startDate();
	}

	private LocalDate getPreferredEndDate(DateRange dateRange) {
		if (dateRange == null) {
			return null;
		}

		return dateRange.endDate();
	}
}
