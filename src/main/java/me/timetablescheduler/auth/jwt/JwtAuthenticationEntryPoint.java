package me.timetablescheduler.auth.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.OffsetDateTime;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

// 401 응답
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	public static final String AUTH_ERROR_ATTRIBUTE = "authError";

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException {
		ExceptionCode exceptionCode = resolveExceptionCode(request);

		response.setStatus(exceptionCode.getStatus().value());
		response.setContentType("application/json;charset=UTF-8");
		response.getWriter().write(createErrorBody(exceptionCode, request.getRequestURI()));
	}

	private ExceptionCode resolveExceptionCode(HttpServletRequest request) {
		Object authError = request.getAttribute(AUTH_ERROR_ATTRIBUTE);
		if (authError instanceof AuthException authException) {
			return authException.getExceptionCode();
		}
		return ExceptionCode.UNAUTHORIZED;
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
