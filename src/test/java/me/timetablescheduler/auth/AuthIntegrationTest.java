package me.timetablescheduler.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {

	private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 회원가입하면_JWT를_발급하고_보호된_API에_접근할_수_있다() throws Exception {
		MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Alice",
					  "email": "alice@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString())
			.andReturn();

		String accessToken = JSON_필드_조회(registerResult.getResponse().getContentAsString(), "accessToken");

		mockMvc.perform(get("/api/auth/me")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Alice"))
			.andExpect(jsonPath("$.email").value("alice@example.com"));
	}

	@Test
	void 로그인하고_토큰을_갱신하면_토큰이_교체된다() throws Exception {
		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Bob",
					  "email": "bob@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isCreated());

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "bob@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString())
			.andReturn();

		String refreshToken = JSON_필드_조회(loginResult.getResponse().getContentAsString(), "refreshToken");

		mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "%s"
					}
					""".formatted(refreshToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString());
	}

	@Test
	void 토큰_갱신은_액세스_토큰_인증_헤더를_무시한다() throws Exception {
		mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Dora",
					  "email": "dora@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isCreated());

		MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "dora@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isOk())
			.andReturn();

		String refreshToken = JSON_필드_조회(loginResult.getResponse().getContentAsString(), "refreshToken");

		mockMvc.perform(post("/api/auth/refresh")
				.header(HttpHeaders.AUTHORIZATION, "Bearer malformed-access-token")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "%s"
					}
					""".formatted(refreshToken)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").isString());
	}

	@Test
	void 로그아웃하면_리프레시_토큰이_무효화된다() throws Exception {
		MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Charlie",
					  "email": "charlie@example.com",
					  "password": "password123"
					}
					"""))
			.andExpect(status().isCreated())
			.andReturn();

		String accessToken = JSON_필드_조회(registerResult.getResponse().getContentAsString(), "accessToken");
		String refreshToken = JSON_필드_조회(registerResult.getResponse().getContentAsString(), "refreshToken");

		mockMvc.perform(post("/api/auth/logout")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isNoContent());

		mockMvc.perform(post("/api/auth/refresh")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "refreshToken": "%s"
					}
					""".formatted(refreshToken)))
			.andExpect(status().isUnauthorized());
	}

	private String JSON_필드_조회(String content, String fieldName) {
		Matcher matcher = Pattern.compile(JSON_STRING_FIELD.pattern().formatted(fieldName)).matcher(content);
		if (!matcher.find()) {
			throw new IllegalStateException("Missing field: " + fieldName);
		}
		return matcher.group(1);
	}
}
