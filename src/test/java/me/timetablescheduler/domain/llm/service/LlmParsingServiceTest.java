package me.timetablescheduler.domain.llm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.time.LocalDate;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;
import org.junit.jupiter.api.Test;

class LlmParsingServiceTest {

	@Test
	void 정상_파싱결과를_그대로_반환한다() {
		ParsedTaskResponse parsed = responseBuilder()
			.durationMinutes(90)
			.preferredDate(LocalDate.of(2026, 5, 5))
			.preferredTimeRange(PreferredTimeRange.AFTERNOON)
			.priority(TaskPriority.HIGH)
			.description("중요한 과제")
			.build();
		LlmParsingService service = serviceWith(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("5월 5일 오후에 과제 90분"));

		assertEquals("과제", result.title());
		assertEquals(TaskCategory.ASSIGNMENT, result.category());
		assertEquals(90, result.durationMinutes());
		assertEquals(LocalDate.of(2026, 5, 5), result.preferredDate());
		assertEquals(PreferredTimeRange.AFTERNOON, result.preferredTimeRange());
		assertEquals(TaskPriority.HIGH, result.priority());
		assertEquals("중요한 과제", result.description());
	}

	@Test
	void 소요시간이_없으면_60분으로_보정한다() {
		ParsedTaskResponse parsed = responseBuilder()
			.durationMinutes(null)
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertEquals(60, result.durationMinutes());
	}

	@Test
	void 선호시간대가_없으면_ANYTIME으로_보정한다() {
		ParsedTaskResponse parsed = responseBuilder()
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.preferredTimeRange(null)
			.build();
		LlmParsingService service = serviceWith(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertEquals(PreferredTimeRange.ANYTIME, result.preferredTimeRange());
	}

	@Test
	void 우선순위가_없으면_NORMAL로_보정한다() {
		ParsedTaskResponse parsed = responseBuilder()
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.priority(null)
			.build();
		LlmParsingService service = serviceWith(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertEquals(TaskPriority.NORMAL, result.priority());
	}

	@Test
	void 설명이_없으면_원문으로_보정한다() {
		String originalMessage = "화요일 점심에 밥약속 잡아줘";
		ParsedTaskResponse parsed = responseBuilder()
			.title("밥약속")
			.category(TaskCategory.APPOINTMENT)
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.preferredTimeRange(PreferredTimeRange.LUNCH)
			.description(null)
			.build();
		LlmParsingService service = serviceWith(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest(originalMessage));

		assertEquals(originalMessage, result.description());
	}

	@Test
	void 요청이_null이면_INVALID_LLM_PARSE_REQUEST를_던진다() {
		LlmParsingService service = serviceWith(validResponse());

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(null));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_REQUEST, exception.getExceptionCode());
	}

	@Test
	void 요청_메시지가_비어있으면_INVALID_LLM_PARSE_REQUEST를_던진다() {
		LlmParsingService service = serviceWith(validResponse());

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("   ")));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_REQUEST, exception.getExceptionCode());
	}

	@Test
	void LLM_응답이_null이면_INVALID_LLM_PARSE_RESULT를_던진다() {
		LlmParsingService service = serviceWith(null);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_RESULT, exception.getExceptionCode());
	}

	@Test
	void 제목이_없으면_MISSING_LLM_TITLE을_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.title(" ")
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.MISSING_LLM_TITLE, exception.getExceptionCode());
	}

	@Test
	void 카테고리가_없으면_MISSING_LLM_CATEGORY를_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.category(null)
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.MISSING_LLM_CATEGORY, exception.getExceptionCode());
	}

	@Test
	void 소요시간이_0분이면_INVALID_LLM_DURATION을_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.durationMinutes(0)
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DURATION, exception.getExceptionCode());
	}

	@Test
	void 소요시간이_30분_단위가_아니면_INVALID_LLM_DURATION을_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.durationMinutes(45)
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DURATION, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_없으면_INVALID_LLM_DATE_CONDITION을_던진다() {
		ParsedTaskResponse parsed = responseBuilder().build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("과제할 시간 잡아줘")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_CONDITION, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_둘_이상이면_INVALID_LLM_DATE_CONDITION을_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.preferredDate(LocalDate.of(2026, 5, 5))
			.preferredDayOfWeek(DayOfWeek.FRIDAY)
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("5월 5일 금요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_CONDITION, exception.getExceptionCode());
	}

	@Test
	void 날짜범위의_시작일이_없으면_INVALID_LLM_DATE_RANGE를_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.preferredDateRange(new DateRange(null, LocalDate.of(2026, 5, 10)))
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("이번 주 안에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_RANGE, exception.getExceptionCode());
	}

	@Test
	void 날짜범위의_시작일이_종료일보다_늦으면_INVALID_LLM_DATE_RANGE를_던진다() {
		ParsedTaskResponse parsed = responseBuilder()
			.preferredDateRange(new DateRange(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 4)))
			.build();
		LlmParsingService service = serviceWith(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("이번 주 안에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_RANGE, exception.getExceptionCode());
	}

	private LlmParsingService serviceWith(ParsedTaskResponse response) {
		return new LlmParsingService(new FakeOpenAiClient(response));
	}

	private ParsedTaskResponse validResponse() {
		return responseBuilder()
			.preferredDayOfWeek(DayOfWeek.TUESDAY)
			.build();
	}

	private ParsedTaskResponseBuilder responseBuilder() {
		return new ParsedTaskResponseBuilder();
	}

	private static class FakeOpenAiClient extends OpenAiClient {

		private final ParsedTaskResponse response;

		private FakeOpenAiClient(ParsedTaskResponse response) {
			this.response = response;
		}

		@Override
		public ParsedTaskResponse parseTask(String message) {
			return response;
		}
	}

	private static class ParsedTaskResponseBuilder {

		private String title = "과제";
		private TaskCategory category = TaskCategory.ASSIGNMENT;
		private Integer durationMinutes = 60;
		private LocalDate preferredDate;
		private DayOfWeek preferredDayOfWeek;
		private DateRange preferredDateRange;
		private PreferredTimeRange preferredTimeRange = PreferredTimeRange.ANYTIME;
		private LocalDate deadline;
		private TaskPriority priority = TaskPriority.NORMAL;
		private String description = "과제할 시간 잡아줘";

		private ParsedTaskResponseBuilder title(String title) {
			this.title = title;
			return this;
		}

		private ParsedTaskResponseBuilder category(TaskCategory category) {
			this.category = category;
			return this;
		}

		private ParsedTaskResponseBuilder durationMinutes(Integer durationMinutes) {
			this.durationMinutes = durationMinutes;
			return this;
		}

		private ParsedTaskResponseBuilder preferredDate(LocalDate preferredDate) {
			this.preferredDate = preferredDate;
			return this;
		}

		private ParsedTaskResponseBuilder preferredDayOfWeek(DayOfWeek preferredDayOfWeek) {
			this.preferredDayOfWeek = preferredDayOfWeek;
			return this;
		}

		private ParsedTaskResponseBuilder preferredDateRange(DateRange preferredDateRange) {
			this.preferredDateRange = preferredDateRange;
			return this;
		}

		private ParsedTaskResponseBuilder preferredTimeRange(PreferredTimeRange preferredTimeRange) {
			this.preferredTimeRange = preferredTimeRange;
			return this;
		}

		private ParsedTaskResponseBuilder priority(TaskPriority priority) {
			this.priority = priority;
			return this;
		}

		private ParsedTaskResponseBuilder description(String description) {
			this.description = description;
			return this;
		}

		private ParsedTaskResponse build() {
			return new ParsedTaskResponse(
				title,
				category,
				durationMinutes,
				preferredDate,
				preferredDayOfWeek,
				preferredDateRange,
				preferredTimeRange,
				deadline,
				priority,
				description
			);
		}
	}
}
