package me.timetablescheduler.global.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {
	// 비즈니스 예외
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApiException(ApiException e, HttpServletRequest request) {
		ExceptionCode code = e.getExceptionCode();

		log.warn("Business Exception 발생 : {} ", code.name());

		ErrorResponse response = ErrorResponse.of(code, request.getRequestURI());

		return ResponseEntity
			.status(code.getStatus())
			.body(response);
	}

}
