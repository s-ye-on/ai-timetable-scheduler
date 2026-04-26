package me.timetablescheduler.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import me.timetablescheduler.config.JwtProperties;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {

	private static final String TOKEN_TYPE_CLAIM = "tokenType";
	private static final String ACCESS_TOKEN_TYPE = "access";
	private static final String REFRESH_TOKEN_TYPE = "refresh";

	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final Key key;

	public JwtTokenService(JwtProperties jwtProperties, Clock clock) {
		this.jwtProperties = jwtProperties;
		this.clock = clock;
		this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
	}

	public String generateAccessToken(String email) {
		return createToken(email, jwtProperties.getAccessTokenTtlSeconds(), ACCESS_TOKEN_TYPE);
	}

	public String generateRefreshToken(String email) {
		return createToken(email, jwtProperties.getRefreshTokenTtlSeconds(), REFRESH_TOKEN_TYPE);
	}

	public Instant calculateRefreshExpiry() {
		return Instant.now(clock).plusSeconds(jwtProperties.getRefreshTokenTtlSeconds());
	}

	public void validateToken(String token) {
		parseClaims(token);
	}

	public TokenClaims parseAccessToken(String token) {
		Claims claims = parseClaims(token);
		validateTokenType(claims, ACCESS_TOKEN_TYPE);
		return toTokenClaims(claims);
	}

	public TokenClaims parseRefreshToken(String token) {
		Claims claims = parseClaims(token);
		validateTokenType(claims, REFRESH_TOKEN_TYPE);
		return toTokenClaims(claims);
	}

	public String getEmail(String token) {
		return parseClaims(token).getSubject();
	}

	public String getTokenType(String token) {
		return parseClaims(token).get(TOKEN_TYPE_CLAIM, String.class);
	}

	public long getAccessTokenTtlSeconds() {
		return jwtProperties.getAccessTokenTtlSeconds();
	}

	public Instant refreshTokenExpiry() {
		return calculateRefreshExpiry();
	}

	private String createToken(String email, long ttlSeconds, String tokenType) {
		Instant issuedAt = Instant.now(clock);
		Instant expiresAt = issuedAt.plusSeconds(ttlSeconds);

		return Jwts.builder()
			.setIssuer(jwtProperties.getIssuer())
			.setSubject(email)
			.setId(UUID.randomUUID().toString())
			.claim(TOKEN_TYPE_CLAIM, tokenType)
			.setIssuedAt(Date.from(issuedAt))
			.setExpiration(Date.from(expiresAt))
			.signWith(key, SignatureAlgorithm.HS256)
			.compact();
	}

	private Claims parseClaims(String token) {
		try {
			return Jwts.parserBuilder()
				.setSigningKey(key)
				.requireIssuer(jwtProperties.getIssuer())
				.setClock(() -> Date.from(Instant.now(clock)))
				.build()
				.parseClaimsJws(token)
				.getBody();
		} catch (ExpiredJwtException exception) {
			throw new AuthException(ExceptionCode.TOKEN_EXPIRED);
		} catch (SecurityException | MalformedJwtException exception) {
			throw new AuthException(ExceptionCode.TOKEN_INVALID);
		} catch (UnsupportedJwtException exception) {
			throw new AuthException(ExceptionCode.TOKEN_UNSUPPORTED);
		} catch (IllegalArgumentException | JwtException exception) {
			throw new AuthException(ExceptionCode.TOKEN_INVALID);
		}
	}

	private void validateTokenType(Claims claims, String expectedTokenType) {
		String tokenType = claims.get(TOKEN_TYPE_CLAIM, String.class);
		if (!expectedTokenType.equals(tokenType)) {
			throw new AuthException(ExceptionCode.TOKEN_INVALID);
		}
	}

	private TokenClaims toTokenClaims(Claims claims) {
		return new TokenClaims(
			claims.getSubject(),
			claims.get(TOKEN_TYPE_CLAIM, String.class),
			claims.getExpiration().toInstant()
		);
	}

	public record TokenClaims(String subject, String tokenType, Instant expiresAt) {
	}
}
