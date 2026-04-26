package me.timetablescheduler.auth.jwt;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.security.CustomUserDetails;
import me.timetablescheduler.auth.security.CustomUserDetailsService;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String REGISTER_PATH = "/api/auth/register";
	private static final String LOGIN_PATH = "/api/auth/login";
	private static final String REFRESH_PATH = "/api/auth/refresh";

	private final JwtTokenService jwtTokenService;
	private final CustomUserDetailsService customUserDetailsService;
	private final AuthenticationEntryPoint authenticationEntryPoint;

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return REGISTER_PATH.equals(path)
			|| LOGIN_PATH.equals(path)
			|| REFRESH_PATH.equals(path);
	}

	@Override
	protected void doFilterInternal(
		HttpServletRequest request,
		HttpServletResponse response,
		FilterChain filterChain
	) throws ServletException, IOException {
		String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

		if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
			filterChain.doFilter(request, response);
			return;
		}

		if (SecurityContextHolder.getContext().getAuthentication() != null) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = authorizationHeader.substring(7);

		try {
			JwtTokenService.TokenClaims claims = jwtTokenService.parseAccessToken(token);
			CustomUserDetails userDetails = (CustomUserDetails) customUserDetailsService.loadUserByUsername(claims.subject());

			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
				userDetails,
				null,
				userDetails.getAuthorities()
			);
			SecurityContextHolder.getContext().setAuthentication(authentication);
			filterChain.doFilter(request, response);
		} catch (AuthException exception) {
			SecurityContextHolder.clearContext();
			request.setAttribute(JwtAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, exception);
			authenticationEntryPoint.commence(
				request,
				response,
				new InsufficientAuthenticationException(exception.getMessage(), exception)
			);
		} catch (UsernameNotFoundException exception) {
			SecurityContextHolder.clearContext();
			AuthException authException = new AuthException(ExceptionCode.TOKEN_INVALID);
			request.setAttribute(JwtAuthenticationEntryPoint.AUTH_ERROR_ATTRIBUTE, authException);
			authenticationEntryPoint.commence(
				request,
				response,
				new InsufficientAuthenticationException(authException.getMessage(), exception)
			);
		}
	}
}
