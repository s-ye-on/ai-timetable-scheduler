package me.timetablescheduler.global.exception;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.DayOfWeek;
import java.time.LocalTime;
import me.timetablescheduler.auth.dto.AuthRequest;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.task.dto.TaskRequest;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.domain.timetable.dto.TimetableSlotRequest;
import me.timetablescheduler.domain.user.dto.UserRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

class RequestValidationTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		LocalValidatorFactoryBean mvcValidator = new LocalValidatorFactoryBean();
		mvcValidator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController())
			.setControllerAdvice(new ApiExceptionHandler())
			.setValidator(mvcValidator)
			.build();
	}

	@Test
	void 영속_문자열_컬럼_길이를_초과하는_모든_DTO_필드를_거부한다() {
		assertFieldHasViolation(new AuthRequest.Register("사용자", "a".repeat(20) + "@example.com", "password123"), "email");
		assertFieldHasViolation(new AuthRequest.Login("a".repeat(20) + "@example.com", "password123"), "email");
		assertFieldHasViolation(new UserRequest.Update("password123", "가".repeat(31)), "name");
		assertFieldHasViolation(new TaskRequest.NaturalLanguage("가".repeat(501)), "message");
		assertFieldHasViolation(new TaskRequest.Update(
			"가".repeat(101), TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.MONDAY,
			null, null, null, null, TaskPriority.NORMAL, "설명"
		), "title");
		assertFieldHasViolation(taskUpdate("제목", "가".repeat(501)), "description");
		assertFieldHasViolation(new TimetableSlotRequest.Create(
			"가".repeat(101), DayOfWeek.MONDAY, "강의실", LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "subjectName");
		assertFieldHasViolation(new TimetableSlotRequest.Create(
			"과목", DayOfWeek.MONDAY, "가".repeat(101), LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "location");
		assertFieldHasViolation(new TimetableSlotRequest.Update(
			"가".repeat(101), DayOfWeek.MONDAY, "강의실", LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "subjectName");
		assertFieldHasViolation(new TimetableSlotRequest.Update(
			"과목", DayOfWeek.MONDAY, "가".repeat(101), LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "location");
		assertFieldHasViolation(new LlmParseRequest("가".repeat(501)), "message");
	}

	@Test
	void 영속_문자열_컬럼_최대_길이는_허용한다() {
		assertNoViolations(new AuthRequest.Register("사용자", "a".repeat(18) + "@example.com", "password123"));
		assertNoViolations(new AuthRequest.Login("a".repeat(18) + "@example.com", "password123"));
		assertNoViolations(new UserRequest.Update("password123", "가".repeat(30)));
		assertNoViolations(new TaskRequest.NaturalLanguage("가".repeat(500)));
		assertNoViolations(taskUpdate("가".repeat(100), "가".repeat(500)));
		assertNoViolations(new TimetableSlotRequest.Create(
			"가".repeat(100), DayOfWeek.MONDAY, "가".repeat(100), LocalTime.of(9, 0), LocalTime.of(10, 0)
		));
		assertNoViolations(new TimetableSlotRequest.Update(
			"가".repeat(100), DayOfWeek.MONDAY, "가".repeat(100), LocalTime.of(9, 0), LocalTime.of(10, 0)
		));
		assertNoViolations(new LlmParseRequest("가".repeat(500)));
	}

	@Test
	void Bean_Validation_실패를_공통_ErrorResponse로_반환한다() throws Exception {
		mockMvc.perform(post("/validation/task")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "%s",
					  "category": "ASSIGNMENT",
					  "durationMinutes": 60,
					  "preferredDate": "2026-10-17",
					  "priority": "NORMAL"
					}
					""".formatted("가".repeat(101))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.path").value("/validation/task"))
			.andExpect(jsonPath("$.fieldErrors[0].field").value("title"))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("가".repeat(101)))));
	}

	@Test
	void 잘못된_JSON_유형과_본문_누락을_공통_ErrorResponse로_반환한다() throws Exception {
		assertInvalidJsonResponse("{\"title\":");
		assertInvalidJsonResponse("""
			{
			  "title": "과제",
			  "category": "UNKNOWN",
			  "durationMinutes": 60,
			  "preferredDate": "2026-10-17",
			  "priority": "NORMAL"
			}
			""");
		assertInvalidJsonResponse("""
			{
			  "title": "과제",
			  "category": "ASSIGNMENT",
			  "durationMinutes": 60,
			  "preferredDate": "not-a-date",
			  "priority": "NORMAL"
			}
			""");
		mockMvc.perform(post("/validation/task")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.path").value("/validation/task"))
			.andExpect(jsonPath("$.fieldErrors").isEmpty());
	}

	private void assertFieldHasViolation(Object request, String fieldName) {
		assertTrue(validator.validate(request).stream().anyMatch(violation -> violation.getPropertyPath().toString().equals(fieldName)));
	}

	private void assertNoViolations(Object request) {
		assertTrue(validator.validate(request).isEmpty());
	}

	private TaskRequest.Update taskUpdate(String title, String description) {
		return new TaskRequest.Update(
			title, TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.MONDAY,
			null, null, null, null, TaskPriority.NORMAL, description
		);
	}

	private void assertInvalidJsonResponse(String body) throws Exception {
		mockMvc.perform(post("/validation/task")
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.path").value("/validation/task"))
			.andExpect(jsonPath("$.fieldErrors").isEmpty());
	}

	@Controller
	static class ValidationController {

		@PostMapping("/validation/task")
		void validateTask(@jakarta.validation.Valid @RequestBody TaskRequest.Update request) {
		}
	}
}
