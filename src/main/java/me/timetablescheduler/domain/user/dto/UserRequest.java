package me.timetablescheduler.domain.user.dto;

import jakarta.validation.constraints.NotBlank;

public sealed interface UserRequest
	permits UserRequest.Update {
	record Update(
		@NotBlank(message = "비밀번호 입력은 필수입니다.")
		String password,

		@NotBlank(message = "새 이름은 필수입니다.")
		String name
	) implements UserRequest{
	}
}
