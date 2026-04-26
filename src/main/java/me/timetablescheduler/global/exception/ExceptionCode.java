package me.timetablescheduler.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExceptionCode {


	// 400 입력값 문제
	INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),

	// 401 인증 안됨
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
	INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "유효하지 않는 이메일 또는 비밀번호 입니다."),
	TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "token이 유효하지 않습니다."),
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "token이 만료되었습니다."),
	TOKEN_UNSUPPORTED(HttpStatus.UNAUTHORIZED, "지원하지 않는 token입니다."),
	EXPIRED_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "refresh token이 만료되었습니다."),
	INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "refresh token이 유효하지 않습니다."),
	FORBIDDEN(HttpStatus.FORBIDDEN, "이 리소스에 접근할 권한이 없습니다."),


	// 404 Not Found
	NOT_FOUND_USER(HttpStatus.NOT_FOUND, "존재하지 않는 아이디입니다."),
	NOT_FOUND_EMAIL(HttpStatus.NOT_FOUND, "존재하지 않는 이메일입니다"),
	NOT_FOUND_TIMESLOT(HttpStatus.NOT_FOUND, "존재하지 않는 수업입니다"),

	// 409 Conflict
	CONFLICT_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다");

	private final HttpStatus status;
	private final String message;
}
