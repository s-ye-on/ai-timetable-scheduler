package me.timetablescheduler.domain.llm.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 사용자 입력 원문을 받는 DTO
public record LlmParseRequest(
	@NotBlank
	@Size(max = 500, message = "자연어 입력은 500자를 넘어갈 수 없습니다.")
	String message
) {
}
