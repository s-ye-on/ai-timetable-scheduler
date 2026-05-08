package me.timetablescheduler.domain.task.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.task.dto.TaskResponse;
import me.timetablescheduler.domain.task.service.TaskService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/tasks")
public class TaskController {
	private final TaskService taskService;

	// LLM 호출해서 자연어 받고, 자연어 처리 후 저장
	@PostMapping("/natural-language")
	@ResponseStatus(HttpStatus.CREATED)
	public TaskResponse.Create createFromNaturalLanguage(
		@AuthenticationPrincipal CustomUserDetails userDetails,
		@Valid @RequestBody LlmParseRequest llmParseRequest
	) {
		return taskService.createFromNaturalLanguage(userDetails.getId(), llmParseRequest);
	}

}
