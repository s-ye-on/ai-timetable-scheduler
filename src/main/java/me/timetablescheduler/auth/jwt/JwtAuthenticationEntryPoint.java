package me.timetablescheduler.auth.jwt;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ErrorResponse;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// 401 응답
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	public static final String AUTH_ERROR_ATTRIBUTE = "authError";

	private final ObjectMapper objectMapper;

	@Override
	public void commence(
		HttpServletRequest request,
		HttpServletResponse response,
		AuthenticationException authException
	) throws IOException {
		ExceptionCode exceptionCode = resolveExceptionCode(request);

		response.setStatus(exceptionCode.getStatus().value());
		response.setContentType("application/json;charset=UTF-8");
		objectMapper.writeValue(response.getWriter(), ErrorResponse.of(exceptionCode, request.getRequestURI()));
	}

	private ExceptionCode resolveExceptionCode(HttpServletRequest request) {
		Object authError = request.getAttribute(AUTH_ERROR_ATTRIBUTE);
		if (authError instanceof AuthException authException) {
			return authException.getExceptionCode();
		}
		return ExceptionCode.UNAUTHORIZED;
	}
}
