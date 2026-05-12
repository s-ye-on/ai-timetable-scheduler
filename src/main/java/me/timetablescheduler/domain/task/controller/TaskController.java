package me.timetablescheduler.domain.task.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.task.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tasks")
public class TaskController {
	private final TaskService taskService;

	@GetMapping("/{taskId}")
	public TaskResponse.Read read(
		@PathVariable Long taskId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return taskService.read(taskId, userDetails.getId());
	}

	// LLM 호출해서 자연어 받고, 자연어 처리 후 저장
	@PostMapping("/natural-language")
	@ResponseStatus(HttpStatus.CREATED)
	public TaskResponse.Create createFromNaturalLanguage(
		@AuthenticationPrincipal CustomUserDetails userDetails,
		@Valid @RequestBody TaskRequest.NaturalLanguage naturalLanguage
	) {
		return taskService.createFromNaturalLanguage(userDetails.getId(), naturalLanguage);
	}

	@PutMapping("/{taskId}")
	public TaskResponse.Read update(
		@PathVariable Long taskId,
		@Valid @RequestBody TaskRequest.Update request,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		return taskService.update(taskId, request, userDetails.getId());
	}

	@DeleteMapping("/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(
		@PathVariable Long taskId,
		@AuthenticationPrincipal CustomUserDetails userDetails
	) {
		taskService.delete(taskId, userDetails.getId());
	}
}
