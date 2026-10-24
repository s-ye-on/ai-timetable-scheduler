package me.timetablescheduler.domain.llm.service;

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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmParsingServiceTest {

	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.NullAndEmptySource
	@org.junit.jupiter.params.provider.ValueSource(strings = {" ", "영화 감상", "원문을 요약한 잘못된 설명"})
	void LLM_설명이_변경되거나_누락되어도_사용자_원문을_그대로_보존한다(String description) {
		String source = "내가 내일 세계영화이야기 영화보는 것 오후 중에 시간 추천해줘";
		ParsedTaskResponse raw = 응답_빌더_생성().분류(TaskCategory.PERSONAL).설명(description).생성();
		var actual = 응답을_반환하는_서비스_생성(raw).parseTask(new LlmParseRequest(source));
		assertEquals(source, actual.description());
		assertEquals(description, raw.description());
	}

	@Test
	void 사용자_원문이_500자를_초과하면_LLM_설명이_짧아도_거절한다() {
		var service = 응답을_반환하는_서비스_생성(응답_빌더_생성().설명("짧은 설명").생성());
		var error = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("가".repeat(501))));
		assertEquals(ExceptionCode.INVALID_LLM_PARSE_RESULT, error.getExceptionCode());
	}

	@Test
	void 정상_파싱결과를_그대로_반환한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.소요_시간(90)
			.선호_날짜(LocalDate.of(2026, 5, 5))
			.선호_시간대(PreferredTimeRange.AFTERNOON)
			.중요도(TaskPriority.HIGH)
			.설명("중요한 과제")
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("5월 5일 오후에 과제 90분"));

		assertEquals("과제", result.title());
		assertEquals(TaskCategory.ASSIGNMENT, result.category());
		assertEquals(90, result.durationMinutes());
		assertEquals(LocalDate.of(2026, 5, 5), result.preferredDate());
		assertEquals(PreferredTimeRange.AFTERNOON, result.preferredTimeRange());
		assertEquals(TaskPriority.HIGH, result.priority());
		assertEquals("5월 5일 오후에 과제 90분", result.description());
	}

	@Test
	void 소요시간이_없으면_60분으로_보정한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.소요_시간(null)
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertEquals(60, result.durationMinutes());
	}

	@Test
	void 선호시간대가_없으면_null로_유지한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.선호_요일(DayOfWeek.TUESDAY)
			.선호_시간대(null)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertNull(result.preferredTimeRange());
	}

	@Test
	void 우선순위가_없으면_NORMAL로_보정한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.선호_요일(DayOfWeek.TUESDAY)
			.중요도(null)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("화요일에 과제할 시간 잡아줘"));

		assertEquals(TaskPriority.NORMAL, result.priority());
	}

	@Test
	void 설명이_없으면_원문으로_보정한다() {
		String originalMessage = "화요일 점심에 밥약속 잡아줘";
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.제목("밥약속")
			.분류(TaskCategory.APPOINTMENT)
			.선호_요일(DayOfWeek.TUESDAY)
			.선호_시간대(PreferredTimeRange.LUNCH)
			.설명(null)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest(originalMessage));

		assertEquals(originalMessage, result.description());
	}

	@Test
	void 요청이_null이면_INVALID_LLM_PARSE_REQUEST를_던진다() {
		LlmParsingService service = 응답을_반환하는_서비스_생성(유효한_응답_생성());

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(null));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_REQUEST, exception.getExceptionCode());
	}

	@Test
	void 요청_메시지가_비어있으면_INVALID_LLM_PARSE_REQUEST를_던진다() {
		LlmParsingService service = 응답을_반환하는_서비스_생성(유효한_응답_생성());

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("   ")));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_REQUEST, exception.getExceptionCode());
	}

	@Test
	void LLM_응답이_null이면_INVALID_LLM_PARSE_RESULT를_던진다() {
		LlmParsingService service = 응답을_반환하는_서비스_생성(null);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_RESULT, exception.getExceptionCode());
	}

	@Test
	void 제목이_없으면_MISSING_LLM_TITLE을_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.제목(" ")
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.MISSING_LLM_TITLE, exception.getExceptionCode());
	}

	@Test
	void 제목과_저장할_사용자_원문이_Task_컬럼_최대_길이면_허용한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.제목("가".repeat(100))
			.설명("가".repeat(500))
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("가".repeat(500)));

		assertEquals("가".repeat(100), result.title());
		assertEquals("가".repeat(500), result.description());
	}

	@Test
	void LLM_결과의_제목이_Task_컬럼_길이를_넘으면_거부한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.제목("가".repeat(101))
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_RESULT, exception.getExceptionCode());
	}

	@Test
	void 저장할_사용자_원문이_Task_컬럼_길이를_넘으면_거부한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.설명("가".repeat(501))
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("가".repeat(501))));

		assertEquals(ExceptionCode.INVALID_LLM_PARSE_RESULT, exception.getExceptionCode());
	}

	@Test
	void 카테고리가_없으면_MISSING_LLM_CATEGORY를_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.분류(null)
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.MISSING_LLM_CATEGORY, exception.getExceptionCode());
	}

	@Test
	void 소요시간이_0분이면_INVALID_LLM_DURATION을_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.소요_시간(0)
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DURATION, exception.getExceptionCode());
	}

	@Test
	void 소요시간이_30분_단위가_아니면_INVALID_LLM_DURATION을_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.소요_시간(45)
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("화요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DURATION, exception.getExceptionCode());
	}

	@Test
	void 날짜조건이_없어도_기본_탐색을_위한_파싱결과를_반환한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성().생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("과제할 시간 잡아줘"));

		assertNull(result.preferredDate());
		assertNull(result.preferredDayOfWeek());
		assertNull(result.preferredDateRange());
	}

	@Test
	void 선호요일과_허용기간을_함께_보존한다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.선호_요일(DayOfWeek.TUESDAY)
			.허용_기간(new DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)))
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("이번 주 안에 화요일이 좋아"));

		assertEquals(DayOfWeek.TUESDAY, result.preferredDayOfWeek());
		assertEquals(new DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)), result.preferredDateRange());
	}

	@Test
	void 명시적인_시작시각과_필수조건을_보존한다() {
		ParsedTaskResponse parsed = new ParsedTaskResponse(
			"알고리즘 공부",
			TaskCategory.STUDY,
			120,
			null,
			DayOfWeek.THURSDAY,
			null,
			PreferredTimeRange.EVENING,
			null,
			TaskPriority.NORMAL,
			"목요일 저녁 7시 10분에 알고리즘 공부 2시간",
			true,
			true,
			LocalTime.of(19, 10),
			null
		);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("목요일 저녁 7시 10분에 알고리즘 공부 2시간"));

		assertTrue(result.dateRequired());
		assertTrue(result.timeRequired());
		assertEquals(LocalTime.of(19, 10), result.preferredStartTime());
	}

	@Test
	void 필수여부를_판단하지_못한_LLM_결과는_확인을_요청한다() {
		ParsedTaskResponse parsed = new ParsedTaskResponse(
			"공부",
			TaskCategory.STUDY,
			60,
			null,
			DayOfWeek.THURSDAY,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"목요일 공부",
			null,
			false,
			null,
			null
		);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class,
			() -> service.parseTask(new LlmParseRequest("목요일 공부")));

		assertEquals(ExceptionCode.LLM_CLARIFICATION_REQUIRED, exception.getExceptionCode());
	}

	@Test
	void 오전오후가_모호한_시각은_확인을_요청한다() {
		ParsedTaskResponse parsed = new ParsedTaskResponse(
			"공부",
			TaskCategory.STUDY,
			60,
			null,
			DayOfWeek.THURSDAY,
			null,
			null,
			null,
			TaskPriority.NORMAL,
			"목요일 7시에만 공부",
			null,
			null,
			null,
			"오전 또는 오후 7시인지 확인이 필요합니다."
		);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class,
			() -> service.parseTask(new LlmParseRequest("목요일 7시에만 공부")));

		assertEquals(ExceptionCode.LLM_CLARIFICATION_REQUIRED, exception.getExceptionCode());
	}

	@Test
	void 원문에_명확한_시각이_있으면_LLM_결과가_같은_시각을_보존해야_한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.THURSDAY, LocalTime.of(19, 0), false, false);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		ParsedTaskResponse result = service.parseTask(new LlmParseRequest("목요일 저녁 7시에 알고리즘 공부 2시간 잡아줘"));

		assertEquals(LocalTime.of(19, 0), result.preferredStartTime());
	}

	@Test
	void 원문의_명확한_시각이_LLM_결과에서_누락되면_확인을_요청한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.THURSDAY, null, false, false);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		확인_필요_오류_검증(service, "목요일 저녁 7시에 알고리즘 공부 2시간 잡아줘");
	}

	@Test
	void 원문의_명확한_시각과_LLM_결과가_다르면_확인을_요청한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.THURSDAY, LocalTime.of(18, 0), false, false);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		확인_필요_오류_검증(service, "목요일 19시 10분에 공부하고 싶어");
	}

	@Test
	void 오전오후가_없는_7시는_LLM이_19시로_해석해도_확인을_요청한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.THURSDAY, LocalTime.of(19, 0), true, true);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		확인_필요_오류_검증(service, "반드시 목요일 7시에만 가능해");
	}

	@Test
	void 원문이_시각만_필수라고_했는데_timeRequired가_false이면_확인을_요청한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.THURSDAY, LocalTime.of(19, 0), true, false);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		확인_필요_오류_검증(service, "목요일 저녁 7시에만 가능해");
	}

	@Test
	void 원문이_요일만_필수라고_했는데_dateRequired가_false이면_확인을_요청한다() {
		ParsedTaskResponse parsed = 시각_분석_응답_생성(DayOfWeek.TUESDAY, null, false, false);
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		확인_필요_오류_검증(service, "화요일만 가능해");
	}

	@Test
	void 지원하지_않는_복수조건_제외조건_완화기간_분단위마감은_확인을_요청한다() {
		LlmParsingService service = 응답을_반환하는_서비스_생성(시각_분석_응답_생성(DayOfWeek.TUESDAY, null, false, false));

		for (String message : List.of(
			"화요일 또는 수요일에 공부하고 싶어",
			"화요일 제외하고 공부할 시간 잡아줘",
			"이번 주쯤 과제할 시간 추천해줘",
			"금요일 15시 10분까지 과제 끝낼 시간 추천해줘"
		)) {
			확인_필요_오류_검증(service, message);
		}
	}

	@Test
	void 허용된_대안표현과_날짜미지정_요청은_그대로_통과한다() {
		LlmParsingService dateAlternativeService = 응답을_반환하는_서비스_생성(
			시각_분석_응답_생성(DayOfWeek.TUESDAY, null, false, false)
		);
		LlmParsingService dateUnspecifiedService = 응답을_반환하는_서비스_생성(
			시각_분석_응답_생성(null, null, false, false)
		);

		assertDoesNotThrow(() -> dateAlternativeService.parseTask(
			new LlmParseRequest("화요일 점심이면 좋겠어. 안 되면 다른 날도 괜찮아")
		));
		assertDoesNotThrow(() -> dateUnspecifiedService.parseTask(
			new LlmParseRequest("과제할 시간 1시간 추천해 줘")
		));
	}

	@Test
	void 날짜조건이_둘_이상이면_INVALID_LLM_DATE_CONDITION을_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.선호_날짜(LocalDate.of(2026, 5, 5))
			.선호_요일(DayOfWeek.FRIDAY)
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("5월 5일 금요일에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_CONDITION, exception.getExceptionCode());
	}

	@Test
	void 날짜범위의_시작일이_없으면_INVALID_LLM_DATE_RANGE를_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.허용_기간(new DateRange(null, LocalDate.of(2026, 5, 10)))
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("이번 주 안에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_RANGE, exception.getExceptionCode());
	}

	@Test
	void 날짜범위의_시작일이_종료일보다_늦으면_INVALID_LLM_DATE_RANGE를_던진다() {
		ParsedTaskResponse parsed = 응답_빌더_생성()
			.허용_기간(new DateRange(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 4)))
			.생성();
		LlmParsingService service = 응답을_반환하는_서비스_생성(parsed);

		LlmException exception = assertThrows(LlmException.class, () -> service.parseTask(new LlmParseRequest("이번 주 안에 과제")));

		assertEquals(ExceptionCode.INVALID_LLM_DATE_RANGE, exception.getExceptionCode());
	}

	@Test
	void 명확한시각부터_시작과_제목의또는은_조건모호함으로_오인하지않는다() {
		var service=응답을_반환하는_서비스_생성(시각_분석_응답_생성(DayOfWeek.THURSDAY,LocalTime.of(19,0),false,false));
		assertEquals(LocalTime.of(19,0),service.parseTask(new LlmParseRequest("목요일 저녁 일곱시부터 알고리즘 또는 자료구조 공부 2시간")).preferredStartTime());
		assertEquals(LocalTime.of(19,0),service.parseTask(new LlmParseRequest("목요일 19:00부터 공부 2시간")).preferredStartTime());
	}

	@Test
	void 콜론시각의오후와_밤한시도_정상응답을_오류로보지않는다() {
		assertEquals(LocalTime.of(19,10),응답을_반환하는_서비스_생성(시각_분석_응답_생성(DayOfWeek.THURSDAY,LocalTime.of(19,10),false,false))
			.parseTask(new LlmParseRequest("목요일 오후 7:10에 공부")).preferredStartTime());
		assertEquals(LocalTime.of(1,0),응답을_반환하는_서비스_생성(new ParsedTaskResponse("공부", TaskCategory.STUDY, 60, null, DayOfWeek.THURSDAY, null, null, null, TaskPriority.NORMAL, "공부", false, false, LocalTime.of(1,0), null))
			.parseTask(new LlmParseRequest("목요일 밤 한 시에 공부")).preferredStartTime());
	}

	private LlmParsingService 응답을_반환하는_서비스_생성(ParsedTaskResponse response) {
		return new LlmParsingService(new FakeOpenAiClient(response), java.time.Clock.fixed(
			java.time.Instant.parse("2026-10-19T01:00:00Z"), java.time.ZoneId.of("Asia/Seoul")));
	}

	private ParsedTaskResponse 시각_분석_응답_생성(
		DayOfWeek preferredDayOfWeek,
		LocalTime preferredStartTime,
		boolean dateRequired,
		boolean timeRequired
	) {
		return new ParsedTaskResponse(
			"공부",
			TaskCategory.STUDY,
			60,
			null,
			preferredDayOfWeek,
			null,
			preferredStartTime == null ? PreferredTimeRange.LUNCH : PreferredTimeRange.EVENING,
			null,
			TaskPriority.NORMAL,
			"공부할 시간",
			dateRequired,
			timeRequired,
			preferredStartTime,
			null
		);
	}

	private void 확인_필요_오류_검증(LlmParsingService service, String message) {
		LlmException exception = assertThrows(
			LlmException.class,
			() -> service.parseTask(new LlmParseRequest(message))
		);

		assertEquals(ExceptionCode.LLM_CLARIFICATION_REQUIRED, exception.getExceptionCode());
	}

	private ParsedTaskResponse 유효한_응답_생성() {
		return 응답_빌더_생성()
			.선호_요일(DayOfWeek.TUESDAY)
			.생성();
	}

	private ParsedTaskResponseBuilder 응답_빌더_생성() {
		return new ParsedTaskResponseBuilder();
	}

	private static class FakeOpenAiClient extends OpenAiClient {

		private final ParsedTaskResponse response;

		private FakeOpenAiClient(ParsedTaskResponse response) {
			this.response = response;
		}

		@Override
		public ParsedTaskResponse parseTask(String message, java.time.LocalDate requestDate) {
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
		private PreferredTimeRange preferredTimeRange;
		private LocalDate deadline;
		private TaskPriority priority = TaskPriority.NORMAL;
		private String description = "과제할 시간 잡아줘";

		private ParsedTaskResponseBuilder 제목(String title) {
			this.title = title;
			return this;
		}

		private ParsedTaskResponseBuilder 분류(TaskCategory category) {
			this.category = category;
			return this;
		}

		private ParsedTaskResponseBuilder 소요_시간(Integer durationMinutes) {
			this.durationMinutes = durationMinutes;
			return this;
		}

		private ParsedTaskResponseBuilder 선호_날짜(LocalDate preferredDate) {
			this.preferredDate = preferredDate;
			return this;
		}

		private ParsedTaskResponseBuilder 선호_요일(DayOfWeek preferredDayOfWeek) {
			this.preferredDayOfWeek = preferredDayOfWeek;
			return this;
		}

		private ParsedTaskResponseBuilder 허용_기간(DateRange preferredDateRange) {
			this.preferredDateRange = preferredDateRange;
			return this;
		}

		private ParsedTaskResponseBuilder 선호_시간대(PreferredTimeRange preferredTimeRange) {
			this.preferredTimeRange = preferredTimeRange;
			return this;
		}

		private ParsedTaskResponseBuilder 중요도(TaskPriority priority) {
			this.priority = priority;
			return this;
		}

		private ParsedTaskResponseBuilder 설명(String description) {
			this.description = description;
			return this;
		}

		private ParsedTaskResponse 생성() {
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
