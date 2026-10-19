package me.timetablescheduler.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ExceptionCode {


	// 400 입력값 문제
	INVALID_PASSWORD(HttpStatus.BAD_REQUEST, "비밀번호가 일치하지 않습니다."),
	INVALID_TASK(HttpStatus.BAD_REQUEST, "할 일 입력값이 올바르지 않습니다."),
	INVALID_TASK_DURATION(HttpStatus.BAD_REQUEST, "할 일 소요 시간은 30분 단위의 양수여야 합니다."),
	INVALID_TASK_SCHEDULE_TIME(HttpStatus.BAD_REQUEST, "할 일 시작 시간은 종료 시간보다 빨라야 합니다."),
	INVALID_TASK_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "현재 할 일 상태에서는 요청한 상태 변경을 할 수 없습니다."),
	INVALID_PREFERENCE(HttpStatus.BAD_REQUEST, "사용자 선호 입력값이 올바르지 않습니다."),
	INVALID_PREFERENCE_TIME_RANGE(HttpStatus.BAD_REQUEST, "추천 가능 시작 시간은 종료 시간보다 빨라야 합니다."),
	INVALID_PREFERENCE_GAP(HttpStatus.BAD_REQUEST, "최소 여유 시간은 5분 단위의 0 이상 값이어야 합니다."),
	INVALID_RECOMMENDATION(HttpStatus.BAD_REQUEST, "추천 후보 입력값이 올바르지 않습니다."),
	INVALID_RECOMMENDATION_TIME(HttpStatus.BAD_REQUEST, "추천 후보 시간은 할 일 소요 시간과 일치해야 합니다."),
	INVALID_RECOMMENDATION_STATUS_TRANSITION(HttpStatus.BAD_REQUEST, "현재 추천 후보 상태에서는 요청한 상태 변경을 할 수 없습니다."),
	INVALID_TIMETABLE_SLOT(HttpStatus.BAD_REQUEST, "시간표 입력값이 올바르지 않습니다."),
	INVALID_TIMETABLE_SLOT_TIME(HttpStatus.BAD_REQUEST, "시간표 시작 시간은 종료 시간보다 빨라야 합니다."),
	GOOGLE_CALENDAR_NOT_CONNECTED(HttpStatus.BAD_REQUEST, "Google Calendar가 연결되어 있지 않습니다."),
	INVALID_GOOGLE_OAUTH_STATE(HttpStatus.BAD_REQUEST, "Google Calendar 연결 요청이 유효하지 않거나 만료되었습니다. 연결을 다시 시작해주세요."),
	GOOGLE_OAUTH_AUTHORIZATION_FAILED(HttpStatus.BAD_REQUEST, "Google Calendar 연결이 완료되지 않았습니다. 연결을 다시 시작해주세요."),
	// LLM이 반환한 구조화 결과가 비정상일 때 사용
	INVALID_LLM_PARSE_RESULT(HttpStatus.BAD_REQUEST, "입력한 문장을 일정 요청으로 변환할 수 없습니다. 날짜, 시간대, 소요 시간을 조금 더 명확히 입력해주세요."),
	INVALID_LLM_DATE_CONDITION(HttpStatus.BAD_REQUEST, "특정 날짜와 특정 요일은 함께 지정할 수 없습니다."),
	INVALID_LLM_DURATION(HttpStatus.BAD_REQUEST, "일정 소요 시간은 30분, 60분, 90분처럼 30분 단위로 입력해주세요."),
	INVALID_LLM_DATE_RANGE(HttpStatus.BAD_REQUEST, "날짜 범위가 올바르지 않습니다. 시작일은 종료일과 같거나 더 빨라야 합니다."),
	LLM_CLARIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "요청의 날짜 또는 시간을 확정할 수 없습니다. 날짜, 오전·오후, 필수 여부를 조금 더 명확히 입력해주세요."),
	// 사용자가 보낸 자연어 요청 자체가 비어 있는 경우 사용
	INVALID_LLM_PARSE_REQUEST(HttpStatus.BAD_REQUEST, "일정으로 해석할 자연어 입력이 필요합니다"),

	MISSING_LLM_TITLE(HttpStatus.BAD_REQUEST, "어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요."),
	UNSUPPORTED_LLM_RECURRENCE(HttpStatus.BAD_REQUEST, "반복 일정은 현재 지원하지 않습니다. 한 번의 일정으로 요청해주세요."),
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
	NOT_FOUND_EMAIL(HttpStatus.NOT_FOUND, "존재하지 않는 이메일입니다."),
	NOT_FOUND_TIMESLOT(HttpStatus.NOT_FOUND, "존재하지 않는 수업입니다."),
	NOT_FOUND_TASK(HttpStatus.NOT_FOUND, "존재하지 않는 할 일 입니다."),
	NOT_FOUND_PREFERENCE(HttpStatus.NOT_FOUND, "존재하지 않는 사용자 선호입니다."),
	NOT_FOUND_RECOMMENDATION(HttpStatus.NOT_FOUND, "존재하지 않는 추천 후보입니다."),

	// 409 Conflict
	GOOGLE_CALENDAR_EVENT_MISMATCH(HttpStatus.CONFLICT, "Google Calendar 이벤트가 예약 정보와 다릅니다. 외부 일정을 확인해주세요."),
	GOOGLE_CALENDAR_SYNC_RECOVERY_REQUIRED(HttpStatus.CONFLICT, "기존 동기화 기록을 안전하게 확인할 수 없습니다. Calendar 일정을 직접 확인해주세요."),
	GOOGLE_CALENDAR_RECONNECT_BLOCKED(HttpStatus.CONFLICT, "미완료된 Calendar 동기화를 먼저 복구한 후 다시 연결해주세요."),
	RECOMMENDATION_NO_LONGER_AVAILABLE(HttpStatus.CONFLICT, "추천 후보가 현재 일정 조건과 충돌하거나 더 이상 유효하지 않습니다. 다시 추천해주세요."),
	CONFLICT_USER_OPERATION(HttpStatus.CONFLICT, "다른 일정 변경 요청을 처리 중입니다. 잠시 후 다시 시도해주세요."),
	CONFLICT_EMAIL(HttpStatus.CONFLICT, "이미 존재하는 이메일입니다"),
	CONFLICT_TIMETABLE_SLOT(HttpStatus.CONFLICT, "이미 등록된 시간표와 시간이 겹칩니다."),

	// 503 외부 서비스 설정 누락
	GOOGLE_OAUTH_CONFIGURATION_INVALID(HttpStatus.SERVICE_UNAVAILABLE, "Google Calendar OAuth 설정이 올바르지 않습니다. 서버 설정을 확인해주세요."),

	// 502 외부 서비스 연동 실패
	GOOGLE_CALENDAR_EVENT_LOOKUP_FAILED(HttpStatus.BAD_GATEWAY, "Google Calendar 이벤트 상태를 확인하지 못했습니다. 같은 후보로 다시 시도해주세요."),
	OPENAI_API_FAILED(HttpStatus.BAD_GATEWAY, "AI 분석 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해주세요."),
	GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED(HttpStatus.BAD_GATEWAY, "Google OAuth 토큰 교환에 실패했습니다."),
	GOOGLE_OAUTH_TOKEN_REFRESH_FAILED(HttpStatus.BAD_GATEWAY, "Google access token 갱신에 실패했습니다."),
	GOOGLE_CALENDAR_FREE_BUSY_FAILED(HttpStatus.BAD_GATEWAY, "Google Calendar 바쁜 시간 조회에 실패했습니다."),
	GOOGLE_CALENDAR_EVENT_CREATE_FAILED(HttpStatus.BAD_GATEWAY, "Google Calendar 이벤트 생성에 실패했습니다.");

	private final HttpStatus status;
	private final String message;
}
