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
	void setUp() {
		when(clock.instant()).thenReturn(NOW);
		when(clock.getZone()).thenReturn(ZoneOffset.UTC);
		when(google.createAuthorizationUrl(anyString())).thenAnswer(invocation ->
			"https://accounts.google.com/o/oauth2/auth?state=" + invocation.getArgument(0));
	}

	@Test
	void numericUserIdStateCannotOverwriteVictimCalendar() throws Exception {
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
	void normalCallbackUsesInitiatingUserAndReconnectsOnlyThatUser() throws Exception {
		User owner = createUser("owner");
		User other = createUser("other");
		tokens.saveAndFlush(CalendarToken.create(owner, "old-owner", "old-refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		tokens.saveAndFlush(CalendarToken.create(other, "other-access", "other-refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		Pending pending = begin(owner, null);
		when(google.exchangeCode("valid-code")).thenReturn(response());

		mockMvc.perform(callback(pending).param("userId", other.getId().toString())).andExpect(status().isOk());
		tokens.flush();
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("new-access");
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getRefreshToken()).isEqualTo("new-refresh");
		assertThat(tokens.findByUserId(other.getId()).orElseThrow().getAccessToken()).isEqualTo("other-access");
		mockMvc.perform(callback(pending)).andExpect(status().isBadRequest());
		verify(google, times(1)).exchangeCode("valid-code");
	}

	@Test
	void freshConnectionPersistsAndStatusReflectsServerState() throws Exception {
		User owner = createUser("fresh");
		Pending pending = begin(owner, null);
		when(google.exchangeCode("valid-code")).thenReturn(response());
		mockMvc.perform(callback(pending)).andExpect(status().isOk());
		tokens.flush();
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getUser().getId()).isEqualTo(owner.getId());
		mockMvc.perform(get("/api/calendar/status").header("Authorization", bearer(owner)))
			.andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.connected").value(true));
	}

	@Test
	void missingTamperedAndForeignBrowserStatesCannotWriteTokens() throws Exception {
		User owner = createUser("tampered");
		Pending pending = begin(owner, null);
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
		when(google.exchangeCode("valid-code")).thenReturn(response());
		mockMvc.perform(callback(pending)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(owner.getId())).isPresent();
	}

	@Test
	void expiresAtFiveMinutesWithoutCallingGoogle() throws Exception {
		User owner = createUser("expired");
		Pending pending = begin(owner, null);
		when(clock.instant()).thenReturn(NOW.plusSeconds(300));
		mockMvc.perform(callback(pending)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void deniedOrMissingCodeConsumesValidState() throws Exception {
		User owner = createUser("denied");
		Pending denied = begin(owner, null);
		mockMvc.perform(get("/api/calendar/oauth/callback").session(denied.session())
			.param("state", denied.state()).param("error", "access_denied")).andExpect(status().isBadRequest());
		mockMvc.perform(callback(denied)).andExpect(status().isBadRequest());
		Pending missing = begin(owner, denied.session());
		mockMvc.perform(get("/api/calendar/oauth/callback").session(missing.session())
			.param("state", missing.state())).andExpect(status().isBadRequest());
		mockMvc.perform(callback(missing)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId())).isEmpty();
		verify(google, never()).exchangeCode(anyString());
	}

	@Test
	void failedExchangeRequiresNewAuthorizationAndPreservesExistingConnection() throws Exception {
		User owner = createUser("exchange-failed");
		tokens.saveAndFlush(CalendarToken.create(owner, "original", "refresh", LocalDateTime.of(2030, 1, 1, 0, 0)));
		Pending pending = begin(owner, null);
		when(google.exchangeCode("valid-code")).thenThrow(new CalendarException(ExceptionCode.GOOGLE_OAUTH_TOKEN_EXCHANGE_FAILED));
		mockMvc.perform(callback(pending)).andExpect(status().isBadGateway());
		mockMvc.perform(callback(pending)).andExpect(status().isBadRequest());
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("original");
		verify(google, times(1)).exchangeCode("valid-code");
		Pending retry = begin(owner, pending.session());
		doReturn(response()).when(google).exchangeCode("valid-code");
		mockMvc.perform(callback(retry)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(owner.getId()).orElseThrow().getAccessToken()).isEqualTo("new-access");
	}

	@Test
	void newerAuthorizationReplacesPriorUserStateAndSessionIsNotAuthentication() throws Exception {
		User first = createUser("first");
		User second = createUser("second");
		Pending old = begin(first, null);
		String oldSessionId = old.session().getId();
		Pending latest = begin(second, old.session());
		assertThat(latest.session().getId()).isNotEqualTo(oldSessionId);
		assertThat(latest.state()).isNotEqualTo(old.state()).hasSize(43).matches("[A-Za-z0-9_-]+");
		mockMvc.perform(callback(old)).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/calendar/status").session(latest.session())).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/calendar/oauth/url").session(latest.session())).andExpect(status().isUnauthorized());
		when(google.exchangeCode("valid-code")).thenReturn(response());
		mockMvc.perform(callback(latest)).andExpect(status().isOk());
		assertThat(tokens.findByUserId(first.getId())).isEmpty();
		assertThat(tokens.findByUserId(second.getId())).isPresent();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void simultaneousCallbacksExchangeOnceAndPersistOneConnection() throws Exception {
		User owner = createUser("concurrent");
		try (var executor = Executors.newFixedThreadPool(2)) {
			Pending pending = begin(owner, null);
			when(google.exchangeCode("valid-code")).thenReturn(response());
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch start = new CountDownLatch(1);
			java.util.concurrent.Callable<Integer> call = () -> {
				ready.countDown();
				if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("callback 시작 시간 초과");
				return mockMvc.perform(callback(pending)).andReturn().getResponse().getStatus();
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

	private User createUser(String name) {
		return users.saveAndFlush(new User(name, name + "@example.com", "encoded"));
	}

	private String bearer(User user) {
		return "Bearer " + jwt.generateAccessToken(user.getEmail());
	}

	private Pending begin(User user, MockHttpSession session) throws Exception {
		var request = get("/api/calendar/oauth/url").header("Authorization", bearer(user));
		if (session != null) request.session(session);
		var result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
		String body = result.getResponse().getContentAsString();
		String state = body.substring(body.indexOf("state=") + 6, body.lastIndexOf('"'));
		return new Pending((MockHttpSession) result.getRequest().getSession(false), state);
	}

	private MockHttpServletRequestBuilder callback(Pending pending) {
		return get("/api/calendar/oauth/callback").session(pending.session())
			.param("code", "valid-code").param("state", pending.state());
	}

	private GoogleTokenResponse response() {
		return new GoogleTokenResponse().setAccessToken("new-access").setRefreshToken("new-refresh").setExpiresInSeconds(3600L);
	}

	private record Pending(MockHttpSession session, String state) { }
}
