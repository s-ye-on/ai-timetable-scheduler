package me.timetablescheduler.global.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public sealed interface UserRequest
	permits UserRequest.Create, UserRequest.Login, UserRequest.Update{
	record Create(
		@NotBlank(message = "이름은 필수입니다.")
		String name,

		@NotBlank(message = "이메일은 필수입니다.")
		@Email
		String email,

		@NotBlank(message = "비밀번호 입력은 필수입니다.")
		String password
	) implements UserRequest {
	}

	record Login(
		@NotBlank(message = "이메일 아이디는 필수입니다.")
		@Email
		String email,

		@NotBlank(message = "비밀번호 입력은 필수입니다.")
		String password
	) implements UserRequest {
	}

	record Update(
		@NotBlank(message = "비밀번호 입력은 필수입니다.")
		String password,

		@NotBlank(message = "새 이름은 필수입니다.")
		String name
	) implements UserRequest{
	}
}
