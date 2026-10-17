package me.timetablescheduler.global.exception;

import java.util.List;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
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

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidationException(
		MethodArgumentNotValidException e,
		HttpServletRequest request
	) {
		List<ErrorResponse.FieldError> fieldErrors = e.getBindingResult().getFieldErrors().stream()
			.map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
			.toList();

		return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(request.getRequestURI(), fieldErrors));
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleUnreadableMessage(
		HttpMessageNotReadableException e,
		HttpServletRequest request
	) {
		return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(request.getRequestURI(), List.of()));
	}

}
