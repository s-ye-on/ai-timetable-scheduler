package me.timetablescheduler.domain.recommendation.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import me.timetablescheduler.domain.recommendation.Recommendation;
import me.timetablescheduler.domain.recommendation.RecommendationRepository;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.TaskRepository;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerIntegrationTest {

	private static final Pattern JSON_STRING_FIELD = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]+)\"");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TaskRepository taskRepository;

	@Autowired
	private RecommendationRepository recommendationRepository;

	@Test
	void Google_Calendar_미연동_사용자가_추천을_선택하면_비즈니스_예외_응답을_반환한다() throws Exception {
		String email = "select-no-cal@example.com";
		String accessToken = registerAndGetAccessToken(email);
		User user = userRepository.findByEmail(email).orElseThrow();
		Task task = taskRepository.save(Task.create(
			user,
			"밥약속",
			TaskCategory.APPOINTMENT,
			60,
			LocalDate.of(2026, 6, 9),
			null,
			null,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"화요일 점심에 밥약속"
		));
		Recommendation recommendation = recommendationRepository.save(Recommendation.create(
			user,
			task,
			LocalDateTime.of(2026, 6, 9, 10, 0),
			LocalDateTime.of(2026, 6, 9, 11, 0),
			1,
			90,
			"추천 후보"
		));

		mockMvc.perform(post("/recommendations/{recommendationId}/select", recommendation.getId())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("GOOGLE_CALENDAR_NOT_CONNECTED"))
			.andExpect(jsonPath("$.message").value("Google Calendar가 연결되어 있지 않습니다."))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.path").value("/recommendations/%d/select".formatted(recommendation.getId())))
			.andExpect(jsonPath("$.fieldErrors").isArray());
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
}
