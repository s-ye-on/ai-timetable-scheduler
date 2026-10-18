package me.timetablescheduler.domain.calendar;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import me.timetablescheduler.domain.calendar.external.GoogleOAuthClient;
import me.timetablescheduler.auth.jwt.JwtTokenService;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import me.timetablescheduler.global.exception.CalendarException;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CalendarOAuthIntegrationTest {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository users;
	@Autowired CalendarTokenRepository tokens;
	@MockitoBean GoogleOAuthClient google;
	@MockitoBean Clock clock;
	@Autowired JwtTokenService jwt;
	private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");

	@BeforeEach
	void 테스트_준비() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(google.createAuthorizationUrl(anyString())).thenAnswer(invocation ->
			"https://accounts.google.com/o/oauth2/auth?state=" + invocation.getArgument(0));
	}

	@Test
	void 사용자_ID를_state로_사용해_다른_사용자의_Calendar를_덮어쓸_수_없다() throws Exception {
		User victim = users.saveAndFlush(new User("Victim", "victim@example.com", "encoded"));
		tokens.saveAndFlush(CalendarToken.create(victim, "original-access", "original-refresh",
			LocalDateTime.of(2030, 1, 1, 0, 0)));
		when(google.exchangeCode("attacker-code")).thenReturn(new GoogleTokenResponse()
			.setAccessToken("attacker-access").setRefreshToken("attacker-refresh").setExpiresInSeconds(3600L));

		var response = mockMvc.perform(get("/api/calendar/oauth/callback")
			.param("code", "attacker-code").param("state", victim.getId().toString())).andReturn();

		assertThat(tokens.findByUserId(victim.getId()).orElseThrow().getAccessToken()).isEqualTo("original-access");
		assertThat(response.getResponse().getStatus()).isEqualTo(400);
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void 잘못된_OAuth_설정은_동의_전에_안전한_서비스_이용_불가_응답을_반환한다() throws Exception {
		User owner = 사용자_생성("config-error");
		when(google.createAuthorizationUrl(anyString()))
			.thenThrow(new CalendarException(ExceptionCode.GOOGLE_OAUTH_CONFIGURATION_INVALID));
		mockMvc.perform(get("/api/calendar/oauth/url").header("Authorization", 인증_헤더_생성(owner)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
				.value("GOOGLE_OAUTH_CONFIGURATION_INVALID"));
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void 정상_콜백은_요청을_시작한_사용자만_재연결한다() throws Exception {
		User owner = 사용자_생성("owner");
		User other = 사용자_생성("other");
		tokens.saveAndFlush(CalendarToken.create(owner, "old-owner", "old-refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		tokens.saveAndFlush(CalendarToken.create(other, "other-access", "other-refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		Pending pending = OAuth_시작(owner, null);
		when(google.exchangeCode("valid-code")).thenReturn(응답_생성());

		mockMvc.perform(OAuth_콜백_처리(pending).param("userId", other.getId().toString())).andExpect(status().isOk());
		tokens.flush();
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("new-access");
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getRefreshToken()).isEqualTo("new-refresh");
		assertThat(tokens.findByUserId(other.getId()).orElseThrow().getAccessToken()).isEqualTo("other-access");
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isBadRequest());
		verify(google, times(1)).exchangeCode("valid-code");
	}

	@Test
	void 새_연결을_저장하고_조회_상태에_서버_상태를_반영한다() throws Exception {
		User owner = 사용자_생성("fresh");
		Pending pending = OAuth_시작(owner, null);
		when(google.exchangeCode("valid-code")).thenReturn(응답_생성());
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isOk());
		tokens.flush();
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getUser().getId()).isEqualTo(owner.getId());
		mockMvc.perform(get("/api/calendar/status").header("Authorization", 인증_헤더_생성(owner)))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.connected").value(true));
	}

	@Test
	void 누락되거나_변조되거나_다른_브라우저의_state로_토큰을_저장할_수_없다() throws Exception {
		User owner = 사용자_생성("tampered");
		Pending pending = OAuth_시작(owner, null);
		String changed = (pending.state().startsWith("A") ? "B" : "A") + pending.state().substring(1);
		mockMvc.perform(get("/api/calendar/oauth/callback").session(pending.session())
			.param("state", changed).param("code", "valid-code")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/calendar/oauth/callback").session(new MockHttpSession())
			.param("state", pending.state()).param("code", "valid-code")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/calendar/oauth/callback").param("state", pending.state())
			.param("code", "valid-code")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/calendar/oauth/callback").session(pending.session())
			.param("code", "valid-code")).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
		// 잘못된 state가 정상 요청을 소비하지는 않는다.
		when(google.exchangeCode("valid-code")).thenReturn(응답_생성());
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(owner.getId())).isPresent();
	}

	@Test
	void state는_5분이_되면_Google_호출_없이_만료된다() throws Exception {
		User owner = 사용자_생성("expired");
		Pending pending = OAuth_시작(owner, null);
		when(clock.instant()).thenReturn(NOW.plusSeconds(300));
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void 동의가_거절되거나_코드가_없어도_유효한_state를_소비한다() throws Exception {
		User owner = 사용자_생성("denied");
		Pending denied = OAuth_시작(owner, null);
		mockMvc.perform(get("/api/calendar/oauth/callback").session(denied.session())
			.param("state", denied.state()).param("error", "access_denied")).andExpect(status().isBadRequest());
		mockMvc.perform(OAuth_콜백_처리(denied)).andExpect(status().isBadRequest());
		Pending missing = OAuth_시작(owner, denied.session());
		mockMvc.perform(get("/api/calendar/oauth/callback").session(missing.session())
			.param("state", missing.state())).andExpect(status().isBadRequest());
		mockMvc.perform(OAuth_콜백_처리(missing)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void 토큰_교환에_실패하면_새_인증이_필요하고_기존_연결을_보존한다() throws Exception {
		User owner = 사용자_생성("exchange-failed");
		tokens.saveAndFlush(CalendarToken.create(owner, "original", "refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		Pending pending = OAuth_시작(owner, null);
		when(google.exchangeCode("valid-code")).thenThrow(new CalendarException(ExceptionCode.GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED));
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isBadGateway());
		mockMvc.perform(OAuth_콜백_처리(pending)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("original");
		verify(google, times(1)).exchangeCode("valid-code");
		Pending retry = OAuth_시작(owner, pending.session());
		doReturn(응답_생성()).when(google).exchangeCode("valid-code");
		mockMvc.perform(OAuth_콜백_처리(retry)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("new-access");
	}

	@Test
	void 새_인증은_기존_state를_대체하고_세션은_인증으로_사용하지_않는다() throws Exception {
		User first = 사용자_생성("first");
		User second = 사용자_생성("second");
		Pending old = OAuth_시작(first, null);
		String oldSessionId = old.session().getId();
		Pending latest = OAuth_시작(second, old.session());
		assertThat(latest.session().getId()).isNotEqualTo(oldSessionId);
		assertThat(latest.state()).isNotEqualTo(old.state()).hasSize(43).matches("[A-Za-z0-9_-]+");
		mockMvc.perform(OAuth_콜백_처리(old)).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/calendar/status").session(latest.session())).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/calendar/oauth/url").session(latest.session())).andExpect(status().isUnauthorized());
		when(google.exchangeCode("valid-code")).thenReturn(응답_생성());
		mockMvc.perform(OAuth_콜백_처리(latest)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(first.getId())).isEmpty();
		assertThat(tokens.findByUserId(second.getId())).isPresent();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void 동시_콜백은_토큰을_한_번만_교환하고_연결을_하나만_저장한다() throws Exception {
		User owner = 사용자_생성("concurrent");
		try (var executor = Executors.newFixedThreadPool(2)) {
			Pending pending = OAuth_시작(owner, null);
			when(google.exchangeCode("valid-code")).thenReturn(응답_생성());
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			java.util.concurrent.Callable<Integer> call = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback 시작 시간 초과");
				return mockMvc.perform(OAuth_콜백_처리(pending)).andReturn().getResponse().getStatus();
			};
			var first = executor.submit(call);
			var second = executor.submit(call);
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
				.containsExactlyInAnyOrder(200, 400);
			assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("new-access");
			verify(google, times(1)).exchangeCode("valid-code");
		} finally {
			tokens.findByUserId(owner.getId()).ifPresent(tokens::delete);
			users.deleteById(owner.getId());
		}
	}

	private User 사용자_생성(String name) {
		return users.saveAndFlush(new User(name, name + "@example.com", "encoded"));
	}

	private String 인증_헤더_생성(User user) {
		return "Bearer " + jwt.generateAccessToken(user.getEmail());
	}

	private Pending OAuth_시작(User user, MockHttpSession session) throws Exception {
		var request = get("/api/calendar/oauth/url").header("Authorization", 인증_헤더_생성(user));
		if (session != null) request.session(session);
		var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		String state = body.substring(body.indexOf("state=") + 6, body.lastIndexOf('"'));
		return new Pending((MockHttpSession) result.getRequest().getSession(false), state);
	}

	private MockHttpServletRequestBuilder OAuth_콜백_처리(Pending pending) {
		return get("/api/calendar/oauth/callback").session(pending.session())
			.param("code", "valid-code").param("state", pending.state());
	}

	private GoogleTokenResponse 응답_생성() {
		return new GoogleTokenResponse().setAccessToken("new-access").setRefreshToken("new-refresh").setExpiresInSeconds(3600L);
	}

	private record Pending(MockHttpSession session, String state) { }
}
