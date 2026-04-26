package me.timetablescheduler.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public sealed interface AuthRequest
	permits AuthRequest.Login,
	AuthRequest.Register,
	AuthRequest.TokenRefresh {

	record Login(
		@NotBlank(message = "ID는 필수 입니다.")
		@Email(message = "이메일 형식으로 입력해야 합니다.")
		String email,

		@NotBlank(message = "비밀번호 입력은 필수입니다.")
		String password
	) implements AuthRequest {
	}

	record Register(
		@NotBlank(message = "이름은 필수 입니다.")
		@Size(max = 30, message = "30자를 넘어갈 수 없습니다.")
		String name,

		@NotBlank(message = "이메일 입력은 필수 입니다.")
		@Email(message = "이메일 형식으로 입력해야 합니다.")
		String email,

		@NotBlank(message = "비밀번호 입력은 필수 입니다.")
		@Size(min = 8, max = 100, message = "비밀번호 길이는 최소 8자, 최대 100자 입니다.")
		String password
	) implements AuthRequest {
	}

	record TokenRefresh(
		@NotBlank(message = "refresh token이 필요합니다.")
		String refreshToken
	) implements AuthRequest {
	}

}
