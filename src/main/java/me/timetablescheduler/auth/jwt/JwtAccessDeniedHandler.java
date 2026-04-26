package me.timetablescheduler.auth.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

// 403 응답
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

	@Override
	public void handle(HttpServletRequest request,
	                   HttpServletResponse response,
	                   AccessDeniedException accessDeniedException)
		throws IOException {

		ExceptionCode exceptionCode = ExceptionCode.FORBIDDEN;
		response.setStatus(exceptionCode.getStatus().value());
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(createErrorBody(exceptionCode, request.getRequestURI()));
	}

	private String createErrorBody(ExceptionCode exceptionCode, String path) {
		return """
			{
			  "code": "%s",
			  "message": "%s",
			  "status": %d,
			  "timestamp": "%s",
			  "path": "%s",
			  "fieldErrors": []
			}
			""".formatted(
			exceptionCode.name(),
			exceptionCode.getMessage(),
			exceptionCode.getStatus().value(),
			OffsetDateTime.now(),
			path
		);
	}
}
