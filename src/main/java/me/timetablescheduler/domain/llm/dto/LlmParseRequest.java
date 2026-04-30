package me.timetablescheduler.domain.llm.dto;

import jakarta.validation.constraints.NotBlank;

// 사용자 입력 원문을 받는 DTO
public record LlmParseRequest(
	@NotBlank String message
) {
}
