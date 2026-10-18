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
	void 테스트_준비() {
		LocalValidatorFactoryBean mvcValidator = new LocalValidatorFactoryBean();
		mvcValidator.afterPropertiesSet();
		mockMvc = MockMvcBuilders.standaloneSetup(new ValidationController())
			.setControllerAdvice(new ApiExceptionHandler())
			.setValidator(mvcValidator)
			.build();
	}

	@Test
	void 영속_문자열_컬럼_길이를_초과하는_모든_DTO_필드를_거부한다() {
		필드_검증_오류_확인(new AuthRequest.Register("사용자", "a".repeat(20) + "@example.com", "password123"), "email");
		필드_검증_오류_확인(new AuthRequest.Login("a".repeat(20) + "@example.com", "password123"), "email");
		필드_검증_오류_확인(new UserRequest.Update("password123", "가".repeat(31)), "name");
		필드_검증_오류_확인(new TaskRequest.NaturalLanguage("가".repeat(501)), "message");
		필드_검증_오류_확인(new TaskRequest.Update(
			"가".repeat(101), TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.MONDAY,
			null, null, null, null, TaskPriority.NORMAL, "설명"
		), "title");
		필드_검증_오류_확인(Task_수정_요청_생성("제목", "가".repeat(501)), "description");
		필드_검증_오류_확인(new TimetableSlotRequest.Create(
			"가".repeat(101), DayOfWeek.MONDAY, "강의실", LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "subjectName");
		필드_검증_오류_확인(new TimetableSlotRequest.Create(
			"과목", DayOfWeek.MONDAY, "가".repeat(101), LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "location");
		필드_검증_오류_확인(new TimetableSlotRequest.Update(
			"가".repeat(101), DayOfWeek.MONDAY, "강의실", LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "subjectName");
		필드_검증_오류_확인(new TimetableSlotRequest.Update(
			"과목", DayOfWeek.MONDAY, "가".repeat(101), LocalTime.of(9, 0), LocalTime.of(10, 0)
		), "location");
		필드_검증_오류_확인(new LlmParseRequest("가".repeat(501)), "message");
	}

	@Test
	void 영속_문자열_컬럼_최대_길이는_허용한다() {
		검증_오류_없음_확인(new AuthRequest.Register("사용자", "a".repeat(18) + "@example.com", "password123"));
		검증_오류_없음_확인(new AuthRequest.Login("a".repeat(18) + "@example.com", "password123"));
		검증_오류_없음_확인(new UserRequest.Update("password123", "가".repeat(30)));
		검증_오류_없음_확인(new TaskRequest.NaturalLanguage("가".repeat(500)));
		검증_오류_없음_확인(Task_수정_요청_생성("가".repeat(100), "가".repeat(500)));
		검증_오류_없음_확인(new TimetableSlotRequest.Create(
			"가".repeat(100), DayOfWeek.MONDAY, "가".repeat(100), LocalTime.of(9, 0), LocalTime.of(10, 0)
		));
		검증_오류_없음_확인(new TimetableSlotRequest.Update(
			"가".repeat(100), DayOfWeek.MONDAY, "가".repeat(100), LocalTime.of(9, 0), LocalTime.of(10, 0)
		));
		검증_오류_없음_확인(new LlmParseRequest("가".repeat(500)));
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
		잘못된_JSON_오류_응답_검증("{\"title\":");
		잘못된_JSON_오류_응답_검증("""
			{
			  "title": "과제",
			  "category": "UNKNOWN",
			  "durationMinutes": 60,
			  "preferredDate": "2026-10-17",
			  "priority": "NORMAL"
			}
			""");
		잘못된_JSON_오류_응답_검증("""
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

	private void 필드_검증_오류_확인(Object request, String fieldName) {
		assertTrue(validator.validate(request).stream().anyMatch(violation -> violation.getPropertyPath().toString().equals(fieldName)));
	}

	private void 검증_오류_없음_확인(Object request) {
		assertTrue(validator.validate(request).isEmpty());
	}

	private TaskRequest.Update Task_수정_요청_생성(String title, String description) {
		return new TaskRequest.Update(
			title, TaskCategory.ASSIGNMENT, 60, null, DayOfWeek.MONDAY,
			null, null, null, null, TaskPriority.NORMAL, description
		);
	}

	private void 잘못된_JSON_오류_응답_검증(String body) throws Exception {
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
		void Task_입력_검증(@jakarta.validation.Valid @RequestBody TaskRequest.Update request) {
		}
	}
}
