package me.timetablescheduler.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExceptionCode {


	// 400 입력값 문제
	INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
	// LLM이 반환한 구조화 결과가 비정상일 때 사용
	INVALID_LLM_PARSE_RESULT(HttpStatus.BAD_REQUEST, "입력한 문장을 일정 요청으로 변환할 수 없습니다. 날짜, 시간대, 소요 시간을 조금 더 명확히 입력해주세요."),
	INVALID_LLM_DATE_CONDITION(HttpStatus.BAD_REQUEST, "일정 날짜 조건은 특정 날짜, 특정 요일, 날짜 범위 중 하나만 지정해야 합니다."),
	INVALID_LLM_DURATION(HttpStatus.BAD_REQUEST, "일정 소요 시간은 30분, 60분, 90분처럼 30분 단위로 입력해주세요."),
	INVALID_LLM_DATE_RANGE(HttpStatus.BAD_REQUEST, "날짜 범위가 올바르지 않습니다. 시작일은 종료일과 같거나 더 빨라야 합니다."),
	// 사용자가 보낸 자연어 요청 자체가 비어 있는 경우 사용
	INVALID_LLM_PARSE_REQUEST(HttpStatus.BAD_REQUEST, "일정으로 해석할 자연어 입력이 필요합니다"),

	MISSING_LLM_TITLE(HttpStatus.BAD_REQUEST, "일정 제목을 해석하지 못했습니다. 어떤 일을 할지 조금 더 명확히 입력해주세요."),
	MISSING_LLM_CATEGORY(HttpStatus.BAD_REQUEST, "일정 종류를 해석하지 못했습니다. 공부, 과제, 약속, 개인 업무 중 어떤 결정인지 입력해주세요."),
	MISSING_LLM_DURATION(HttpStatus.BAD_REQUEST, "일정 소요 시간을 해석하지 못했습니다. 예: 30분, 1시간, 2시간처럼 입력해주세요."),

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
