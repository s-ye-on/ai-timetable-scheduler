package me.timetablescheduler.domain.llm.external;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import me.timetablescheduler.domain.llm.config.OpenAiProperties;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.global.exception.LlmException;
import me.timetablescheduler.domain.llm.service.LlmParsingService;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.global.exception.ExceptionCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 프롬프트 전달과 예시의 계약·Java 처리를 검증한다. 실제 LLM 의미 정확도 평가는 아니다. */
class OpenAiIntentPromptTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 21);
    private static final String MISSING_TITLE = "어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요.";
    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private LlmParsingService service;

    @BeforeEach
    void 테스트_준비() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        OpenAiClient client = new OpenAiClient(
            new OpenAiProperties("test-only-key", "gpt-4o-mini", "https://openai.test/v1"), mapper, builder);
        service = new LlmParsingService(client, CLOCK);
    }

    @ParameterizedTest
    @MethodSource("대조_예시")
    void 실제_요청에_전달한_대조_예시는_필수_선호와_활동명_계약을_만족한다(Fixture fixture) {
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.path("model").asString()).isEqualTo("gpt-4o-mini");
                assertThat(body.path("input").asString()).isEqualTo(fixture.input());
                assertThat(body.at("/text/format/strict").asBoolean()).isTrue();
                String instructions = body.path("instructions").asString();
                assertThat(instructions).contains("Asia/Seoul 기준 2026-10-19");
                JsonNode examples = 예시_읽기(instructions);
                assertThat(examples.size()).isEqualTo(6);
                JsonNode output = 예시_선택(examples, fixture.input());
                Set<String> keys = new HashSet<>(output.propertyNames());
                Set<String> required = new HashSet<>();
                body.at("/text/format/schema/required").forEach(n -> required.add(n.asString()));
                assertThat(keys).isEqualTo(required);
                assertThat(mapper.treeToValue(output, ParsedTaskResponse.class)).isEqualTo(fixture.response());
            })
            .andRespond(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                JsonNode output = 예시_선택(예시_읽기(body.path("instructions").asString()), fixture.input());
                return withSuccess(응답_감싸기(output.toString()), MediaType.APPLICATION_JSON).createResponse(request);
            });
        처리_검증(fixture);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("회귀_사례")
    void 실측_실패와_반대_조건의_정상_Stub_응답을_Java가_보존한다(Fixture fixture) {
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andExpect(jsonPath("$.input").value(fixture.input()))
            .andRespond(withSuccess(응답_감싸기(mapper.writeValueAsString(fixture.response())), MediaType.APPLICATION_JSON));
        처리_검증(fixture);
        server.verify();
    }

    @ParameterizedTest
    @MethodSource("기간_및_필수조건_사례")
    void 실제_프롬프트의_기간과_필수조건_예시는_독립_기대값과_일치하고_Java가_보존한다(ParsedTaskResponse expected) {
        String input = expected.description();
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                String instructions = body.path("instructions").asString();
                assertThat(instructions).contains("Asia/Seoul 기준 2026-10-19");
                JsonNode examples = 기간_예시_읽기(instructions);
                JsonNode output = 예시_선택(examples, input);
                Set<String> required = new HashSet<>();
                body.at("/text/format/schema/required").forEach(n -> required.add(n.asString()));
                assertThat(new HashSet<>(output.propertyNames())).isEqualTo(required);
                assertThat(mapper.treeToValue(output, ParsedTaskResponse.class)).isEqualTo(expected);
            })
            .andRespond(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                JsonNode output = 예시_선택(기간_예시_읽기(body.path("instructions").asString()), input);
                return withSuccess(응답_감싸기(output.toString()), MediaType.APPLICATION_JSON).createResponse(request);
            });
        ParsedTaskResponse actual = service.parseTask(new LlmParseRequest(input));
        assertThat(actual.preferredDate()).isEqualTo(expected.preferredDate());
        assertThat(actual.preferredDateRange()).isEqualTo(expected.preferredDateRange());
        assertThat(actual.dateRequired()).isEqualTo(expected.dateRequired());
        assertThat(actual.timeRequired()).isEqualTo(expected.timeRequired());
        assertThat(actual.preferredTimeRange()).isEqualTo(expected.preferredTimeRange());
        assertThat(actual.durationMinutes()).isEqualTo(expected.durationMinutes());
        assertThat(actual.title()).isEqualTo("공부");
        assertThat(actual.clarificationReason()).isNull();
        server.verify();
    }

    private JsonNode 기간_예시_읽기(String instructions) {
        int start = instructions.indexOf("<constraint_examples>");
        int end = instructions.indexOf("</constraint_examples>");
        assertThat(start).as("선호 날짜·허용 기간·필수 조건의 대조 예시가 실제 프롬프트에 있어야 한다").isNotNegative();
        assertThat(end).isGreaterThan(start);
        return mapper.readTree(instructions.substring(start + "<constraint_examples>".length(), end));
    }

    static Stream<ParsedTaskResponse> 기간_및_필수조건_사례() {
        return Stream.of(
            기간_사례("모레 오후에 공부 한 시간 반", 90, LocalDate.of(2026, 10, 21), null, PreferredTimeRange.AFTERNOON, false, false),
            기간_사례("2026년 11월 2일부터 5일 사이에만 공부 1시간", 60, null,
                new me.timetablescheduler.domain.llm.dto.DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5)), null, false, false),
            기간_사례("2026년 11월 4일 오후에만 공부 2시간", 120, LocalDate.of(2026, 11, 4), null, PreferredTimeRange.AFTERNOON, false, true),
            기간_사례("2026년 11월 4일에만 오후에 공부 2시간", 120, LocalDate.of(2026, 11, 4), null, PreferredTimeRange.AFTERNOON, true, false),
            기간_사례("2026년 11월 4일 하루 안에 공부 1시간", 60, null,
                new me.timetablescheduler.domain.llm.dto.DateRange(LocalDate.of(2026, 11, 4), LocalDate.of(2026, 11, 4)), null, false, false),
            기간_사례("2026년 11월 4일이면 좋겠고 2일부터 5일 안에 공부 1시간", 60, LocalDate.of(2026, 11, 4),
                new me.timetablescheduler.domain.llm.dto.DateRange(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 5)), null, false, false)
        );
    }

    private static ParsedTaskResponse 기간_사례(String input, int duration, LocalDate date,
        me.timetablescheduler.domain.llm.dto.DateRange range, PreferredTimeRange band, boolean dateRequired, boolean timeRequired) {
        return new ParsedTaskResponse("공부", TaskCategory.STUDY, duration, date, null, range, band,
            null, null, input, dateRequired, timeRequired, null, null);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', nullValues = "NULL", value = {
        "한 시간 뒤에 공부 2시간|EXACT|120|NULL",
        "한 시간 뒤부터 공부할래|EXACT|NULL|NULL",
        "한 시간 뒤부터 가능한 공부 시간 추천해 줘|NOT_BEFORE|NULL|UNSUPPORTED_LLM_RELATIVE_START_RANGE",
        "한 시간 뒤부터 가능한 시간 추천해 줘|NOT_BEFORE|NULL|MISSING_LLM_TITLE"
    })
    void 상대_시작_예시는_실제_16필드_요청과_Java_정책에_연결된다(String input,
        me.timetablescheduler.domain.llm.dto.RelativeStartMode mode, Integer duration, String errorCode) {
        String clarification = "MISSING_LLM_TITLE".equals(errorCode) ? MISSING_TITLE
            : errorCode != null ? "최소 시작 시각 조건은 지원하지 않아요. 원하는 시작 시각을 지정해 주세요." : null;
        var expected = new ParsedTaskResponse("MISSING_LLM_TITLE".equals(errorCode) ? null : "공부",
            "MISSING_LLM_TITLE".equals(errorCode) ? null : TaskCategory.STUDY, duration, null, null, null,
            null, null, null, input, mode.name().equals("EXACT"), mode.name().equals("EXACT"), null,
            clarification, mode, 60);
        server.expect(requestTo("https://openai.test/v1/responses"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                String instructions = body.path("instructions").asString();
                int start = instructions.indexOf("<relative_start_examples>") + "<relative_start_examples>".length();
                int end = instructions.indexOf("</relative_start_examples>");
                assertThat(end).isGreaterThan(start);
                JsonNode output = 예시_선택(mapper.readTree(instructions.substring(start, end)), input);
                assertThat(mapper.treeToValue(output, ParsedTaskResponse.class)).isEqualTo(expected);
                assertThat(output.size()).isEqualTo(16);
                assertThat(body.at("/text/format/schema/required").toString()).contains("relativeStartMode", "startOffsetMinutes");
                assertThat(body.at("/text/format/schema/properties/relativeStartMode/anyOf/0/enum").toString()).contains("EXACT", "NOT_BEFORE");
                assertThat(body.at("/text/format/schema/properties").has("secondsRequired")).isFalse();
                assertThat(body.at("/text/format/strict").asBoolean()).isTrue();
            })
            .andRespond(withSuccess(응답_감싸기(mapper.writeValueAsString(expected)), MediaType.APPLICATION_JSON));
        if (errorCode != null) {
            assertThatThrownBy(() -> service.parseTask(new LlmParseRequest(input)))
                .isInstanceOfSatisfying(LlmException.class, error -> assertThat(error.getExceptionCode().name()).isEqualTo(errorCode));
        } else {
            var result = service.parseTask(new LlmParseRequest(input));
            assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 19));
            assertThat(result.preferredStartTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(result.durationMinutes()).isEqualTo(duration == null ? 60 : duration);
            assertThat(result.dateRequired()).isTrue();
            assertThat(result.timeRequired()).isTrue();
        }
        server.verify();
    }

    private void 처리_검증(Fixture fixture) {
        if (fixture.error() != null) {
            assertThatThrownBy(() -> service.parseTask(new LlmParseRequest(fixture.input())))
                .isInstanceOfSatisfying(LlmException.class, error -> {
                    assertThat(error.getExceptionCode()).isEqualTo(fixture.error());
                    assertThat(error.getExceptionCode().getStatus().value()).isEqualTo(400);
                    if (fixture.error() == ExceptionCode.MISSING_LLM_TITLE)
                        assertThat(error.getExceptionCode().getMessage()).isEqualTo(MISSING_TITLE);
                });
            return;
        }
        ParsedTaskResponse actual = service.parseTask(new LlmParseRequest(fixture.input()));
        ParsedTaskResponse raw = fixture.response();
        assertThat(actual.title()).isEqualTo(raw.title());
        assertThat(actual.category()).isEqualTo(raw.category());
        assertThat(actual.dateRequired()).isEqualTo(raw.dateRequired());
        assertThat(actual.timeRequired()).isEqualTo(raw.timeRequired());
        assertThat(actual.preferredStartTime()).isEqualTo(raw.preferredStartTime());
        assertThat(actual.preferredDate()).isEqualTo(fixture.date());
        assertThat(actual.preferredDayOfWeek()).isNull();
        assertThat(actual.preferredDateRange()).isNull();
        assertThat(actual.durationMinutes()).isEqualTo(raw.durationMinutes() == null ? 60 : raw.durationMinutes());
        assertThat(actual.description()).isEqualTo(fixture.input());
        assertThat(actual.clarificationReason()).isNull();
        assertThat(actual.preferredTimeRange()).isEqualTo(
            raw.preferredStartTime() != null && raw.preferredStartTime().getHour() >= 18
                ? PreferredTimeRange.EVENING : raw.preferredTimeRange());
    }

    private JsonNode 예시_읽기(String instructions) {
        int start = instructions.indexOf("<contrast_examples>");
        int end = instructions.indexOf("</contrast_examples>");
        assertThat(start).as("필수/선호·활동명 대조 예시가 실제 전송 프롬프트에 있어야 한다").isNotNegative();
        assertThat(end).isGreaterThan(start);
        return mapper.readTree(instructions.substring(start + "<contrast_examples>".length(), end));
    }

    private JsonNode 예시_선택(JsonNode examples, String input) {
        for (JsonNode example : examples) if (input.equals(example.path("input").asString())) return example.path("output");
        throw new AssertionError("대조 예시에 필요한 입력이 없습니다: " + input);
    }

    private String 응답_감싸기(String text) {
        return mapper.writeValueAsString(Map.of("output", List.of(Map.of("content", List.of(Map.of("text", text))))));
    }

    static Stream<Fixture> 대조_예시() {
        return Stream.of(
            사례("수요일 저녁 8시에 운동 1시간 하고 싶어", "운동", TaskCategory.PERSONAL, 60, DayOfWeek.WEDNESDAY, PreferredTimeRange.EVENING, "20:00", false, false, null),
            사례("수요일 저녁 8시에만 운동 1시간 할 수 있어", "운동", TaskCategory.PERSONAL, 60, DayOfWeek.WEDNESDAY, PreferredTimeRange.EVENING, "20:00", false, true, null),
            사례("수요일에만 저녁 8시에 운동 1시간 하고 싶어", "운동", TaskCategory.PERSONAL, 60, DayOfWeek.WEDNESDAY, PreferredTimeRange.EVENING, "20:00", true, false, null),
            사례("공부 한 시간 할래", "공부", TaskCategory.STUDY, 60, null, null, null, false, false, null),
            사례("과제 한 시간 할래", "과제", TaskCategory.ASSIGNMENT, 60, null, null, null, false, false, null),
            사례("반드시 수요일 저녁 8시에만 가능해", null, null, null, DayOfWeek.WEDNESDAY, PreferredTimeRange.EVENING, "20:00", true, true, MISSING_TITLE));
    }

    static Stream<Fixture> 회귀_사례() {
        return Stream.of(
            사례("목요일 저녁 7시에 알고리즘 공부 2시간", "알고리즘 공부", TaskCategory.STUDY, 120, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, "19:00", false, false, null),
            사례("목요일 저녁 7시에만 알고리즘 공부 2시간 가능해", "알고리즘 공부", TaskCategory.STUDY, 120, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, "19:00", false, true, null),
            사례("목요일에만 저녁 7시에 공부 1시간", "공부", TaskCategory.STUDY, 60, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, "19:00", true, false, null),
            사례("반드시 목요일 저녁 7시에만 공부 1시간 가능해", "공부", TaskCategory.STUDY, 60, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, "19:00", true, true, null),
            사례("목요일 19시 10분에 공부하고 싶어", "공부", TaskCategory.STUDY, null, DayOfWeek.THURSDAY, null, "19:10", false, false, null),
            사례("과제할 시간 1시간 추천해 줘", "과제", TaskCategory.ASSIGNMENT, 60, null, null, null, false, false, null),
            사례("공부 30분 추천해 줘", "공부", TaskCategory.STUDY, 30, null, null, null, false, false, null),
            사례("목요일 저녁에 공부 1시간", "공부", TaskCategory.STUDY, 60, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, null, false, false, null),
            사례("목요일 저녁에만 공부 1시간", "공부", TaskCategory.STUDY, 60, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, null, false, true, null),
            사례("7시에 공부하고 싶어", "공부", TaskCategory.STUDY, null, null, null, null, false, false, "오전인지 오후인지 알려주세요."),
            사례("7시에만 공부할 수 있어", "공부", TaskCategory.STUDY, null, null, null, null, false, true, "오전인지 오후인지 알려주세요."),
            사례("반드시 목요일 저녁 7시에만 가능해", null, null, null, DayOfWeek.THURSDAY, PreferredTimeRange.EVENING, "19:00", true, true, MISSING_TITLE));
    }

    private static Fixture 사례(String input, String title, TaskCategory category, Integer duration,
        DayOfWeek day, PreferredTimeRange band, String time, boolean dateRequired, boolean timeRequired, String clarification) {
        ParsedTaskResponse response = new ParsedTaskResponse(title, category, duration, null, day, null, band,
            null, null, input, dateRequired, timeRequired, time == null ? null : LocalTime.parse(time), clarification);
        ExceptionCode error = title == null ? ExceptionCode.MISSING_LLM_TITLE
            : clarification != null ? ExceptionCode.LLM_CLARIFICATION_REQUIRED : null;
        LocalDate date = day == null ? null : CLOCK.instant().atZone(CLOCK.getZone()).toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.nextOrSame(day));
        return new Fixture(input, response, date, error);
    }

    record Fixture(String input, ParsedTaskResponse response, LocalDate date, ExceptionCode error) {}
}
