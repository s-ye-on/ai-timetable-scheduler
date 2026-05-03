package me.timetablescheduler.domain.timetable;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class TimetableSlotIntegrationTest {

	private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");
	private static final Pattern JSON_NUMBER_FIELD = Pattern.compile("\"%s\"\\s*:\\s*(\\d+)");

	@Autowired
	private MockMvc mockMvc;

	@Test
	void 인증된_사용자는_시간표를_생성하고_조회할_수_있다() throws Exception {
		String accessToken = registerAndGetAccessToken("slot-owner@example.com");

		MvcResult createResult = mockMvc.perform(post("/timetable-slots")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "subjectName": "자료구조",
					  "dayOfWeek": "MONDAY",
					  "location": "공학관 101",
					  "startTime": "09:00:00",
					  "endTime": "10:30:00"
					}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNumber())
			.andExpect(jsonPath("$.subjectName").value("자료구조"))
			.andExpect(jsonPath("$.dayOfWeek").value("MONDAY"))
			.andExpect(jsonPath("$.location").value("공학관 101"))
			.andExpect(jsonPath("$.startTime").value("09:00:00"))
			.andExpect(jsonPath("$.endTime").value("10:30:00"))
			.andReturn();

		long slotId = jsonNumberField(createResult.getResponse().getContentAsString(), "id");

		mockMvc.perform(get("/timetable-slots/{id}", slotId)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(slotId))
			.andExpect(jsonPath("$.subjectName").value("자료구조"));
	}

	@Test
	void 같은_요일에_시간이_겹치는_시간표는_생성할_수_없다() throws Exception {
		String accessToken = registerAndGetAccessToken("slot-conflict@example.com");

		mockMvc.perform(post("/timetable-slots")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "subjectName": "자료구조",
					  "dayOfWeek": "MONDAY",
					  "location": "공학관 101",
					  "startTime": "09:00:00",
					  "endTime": "10:30:00"
					}
					"""))
			.andExpect(status().isCreated());

		mockMvc.perform(post("/timetable-slots")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "subjectName": "알고리즘",
					  "dayOfWeek": "MONDAY",
					  "location": "공학관 202",
					  "startTime": "10:00:00",
					  "endTime": "11:30:00"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CONFLICT_TIMETABLE_SLOT"));
	}

	private String registerAndGetAccessToken(String email) throws Exception {
		MvcResult registerResult = mockMvc.perform(post("/api/auth/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "name": "Tester",
					  "email": "%s",
					  "password": "password123"
					}
					""".formatted(email)))
			.andExpect(status().isCreated())
			.andReturn();

		return jsonStringField(registerResult.getResponse().getContentAsString(), "accessToken");
	}

	private String jsonStringField(String content, String fieldName) {
		Matcher matcher = Pattern.compile(JSON_STRING_FIELD.pattern().formatted(fieldName)).matcher(content);
		if (!matcher.find()) {
			throw new IllegalStateException("Missing field: " + fieldName);
		}
		return matcher.group(1);
	}

	private long jsonNumberField(String content, String fieldName) {
		Matcher matcher = Pattern.compile(JSON_NUMBER_FIELD.pattern().formatted(fieldName)).matcher(content);
		if (!matcher.find()) {
			throw new IllegalStateException("Missing field: " + fieldName);
		}
		return Long.parseLong(matcher.group(1));
	}
}
