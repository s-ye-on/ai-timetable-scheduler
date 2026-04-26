package me.timetablescheduler.global.exception;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.Builder;
import org.springframework.http.HttpStatus;

@Builder
public record ErrorResponse(
	String code,
	String message,
	int status,
	OffsetDateTime timestamp,
	String path,
	List<FieldError> fieldErrors
) {

	@Builder
	public record FieldError(String field, String message) {
	}

	// 비즈니스 예외용
	public static ErrorResponse of(ExceptionCode exceptionCode, String path) {
		return ErrorResponse.builder()
			.code(exceptionCode.name())
			.message(exceptionCode.getMessage())
			.status(exceptionCode.getStatus().value())
			.timestamp(OffsetDateTime.now())
			.path(path)
			.fieldErrors(List.of())
			.build();
	}

	// 검증 에러용 @Valid
	public static ErrorResponse ofValidation(String path, List<FieldError> errors) {
		return ErrorResponse.builder()
			.code("INVALID_REQUEST")
			.message("요청 값이 올바르지 않습니다")
			.status(HttpStatus.BAD_REQUEST.value())
			.timestamp(OffsetDateTime.now())
			.path(path)
			.fieldErrors(errors)
			.build();
	}
}
