package me.timetablescheduler.auth;

import java.time.Clock;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.dto.AuthRequest;
import me.timetablescheduler.auth.dto.AuthResponse;
import me.timetablescheduler.auth.dto.UserProfileResponse;
import me.timetablescheduler.auth.jwt.JwtTokenService;
import me.timetablescheduler.auth.token.RefreshToken;
import me.timetablescheduler.auth.token.RefreshTokenRepository;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

	private final UserRepository userRepository;
	private final RefreshTokenRepository refreshTokenRepository;
	private final JwtTokenService jwtTokenService;
	private final PasswordEncoder passwordEncoder;
	private final Clock clock;

	public AuthResponse register(AuthRequest.Register request) {
		if (userRepository.existsByEmail(request.email())) {
			throw new AuthException(ExceptionCode.CONFLICT_EMAIL);
		}

		User user = userRepository.save(
			new User(request.name(), request.email(), passwordEncoder.encode(request.password()))
		);
		return issueTokens(user);
	}

	public AuthResponse login(AuthRequest.Login request) {
		User user = userRepository.findByEmail(request.email())
			.orElseThrow(() -> new AuthException(ExceptionCode.INVALID_CREDENTIALS));

		if (!passwordEncoder.matches(request.password(), user.getPassword())) {
			throw new AuthException(ExceptionCode.INVALID_CREDENTIALS);
		}

		return issueTokens(user);
	}

	public AuthResponse refresh(AuthRequest.TokenRefresh request) {
		JwtTokenService.TokenClaims claims = jwtTokenService.parseRefreshToken(request.refreshToken());
		RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
			.orElseThrow(() -> new AuthException(ExceptionCode.INVALID_REFRESH_TOKEN));

		if (refreshToken.isExpired(java.time.Instant.now(clock))) {
			refreshTokenRepository.delete(refreshToken);
			throw new AuthException(ExceptionCode.EXPIRED_REFRESH_TOKEN);
		}

		if (!refreshToken.getUsername().equals(claims.subject())) {
			throw new AuthException(ExceptionCode.INVALID_REFRESH_TOKEN);
		}

		User user = userRepository.findByEmail(claims.subject())
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));

		String nextRefreshToken = jwtTokenService.generateRefreshToken(user.getEmail());
		refreshToken.rotate(nextRefreshToken, jwtTokenService.calculateRefreshExpiry());

		String accessToken = jwtTokenService.generateAccessToken(user.getEmail());
		return new AuthResponse(
			accessToken,
			nextRefreshToken,
			"Bearer",
			jwtTokenService.getAccessTokenTtlSeconds()
		);
	}

	public void logout(String email) {
		refreshTokenRepository.deleteByUsername(email);
	}

	/// todo : AuthService는 인증 흐름을 담당하기에
	/// 내 정보 조회는 객체 자신의 책임에 두는게 맞다고 생각함
	@Transactional(readOnly = true)
	public UserProfileResponse getCurrentUser(String email) {
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));

		return new UserProfileResponse(user.getId(), user.getName(), user.getEmail());
	}

	private AuthResponse issueTokens(User user) {
		refreshTokenRepository.deleteByUsername(user.getEmail());

		String accessToken = jwtTokenService.generateAccessToken(user.getEmail());
		String refreshToken = jwtTokenService.generateRefreshToken(user.getEmail());
		refreshTokenRepository.save(new RefreshToken(refreshToken, user.getEmail(), jwtTokenService.calculateRefreshExpiry()));

		return new AuthResponse(
			accessToken,
			refreshToken,
			"Bearer",
			jwtTokenService.getAccessTokenTtlSeconds()
		);
	}
}
