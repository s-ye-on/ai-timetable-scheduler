package me.timetablescheduler.domain.llm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import me.timetablescheduler.domain.llm.dto.*;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.global.exception.LlmException;

class LlmRelativeStartPolicyTest {
    private static final String SECONDS_MESSAGE = "초 단위 시작 시간은 지원하지 않아요. 분 단위로 입력해 주세요.";

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "2026-10-19T10:00:00+09:00|2026-10-19T11:00",
        "2026-10-19T10:00:45+09:00|2026-10-19T11:01",
        "2026-10-19T10:00:00.000000001+09:00|2026-10-19T11:01",
        "2026-10-19T23:30:45+09:00|2026-10-20T00:31",
        "2026-10-31T23:30:00+09:00|2026-11-01T00:30",
        "2026-12-31T23:30:45+09:00|2027-01-01T00:31"
    })
    void 요청_시각을_한번만_읽고_상대_시각을_분_올림하여_날짜와_시간을_고정한다(String reference, LocalDateTime expected) {
        var reads = new AtomicInteger();
        Clock fixed = Clock.fixed(OffsetDateTime.parse(reference).toInstant(), ZoneOffset.UTC);
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return fixed.withZone(zone); }
            public Instant instant() { reads.incrementAndGet(); return fixed.instant(); }
        };
        String source = "한 시간 뒤에 공부할래";
        ParsedTaskResponse raw = 응답(source, RelativeStartMode.EXACT, 60, null, null, null, "공부", null);
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.parseTask(eq(source), any(LocalDate.class))).thenReturn(raw);
        ParsedTaskResponse result = new LlmParsingService(client, clock).parseTask(new LlmParseRequest(source));
        assertThat(result.preferredDate()).isEqualTo(expected.toLocalDate());
        assertThat(result.preferredStartTime()).isEqualTo(expected.toLocalTime());
        assertThat(result.dateRequired()).isTrue();
        assertThat(result.timeRequired()).isTrue();
        assertThat(result.durationMinutes()).isEqualTo(60);
        assertThat(result.relativeStartMode()).isEqualTo(RelativeStartMode.EXACT);
        assertThat(result.startOffsetMinutes()).isEqualTo(60);
        assertThat(raw.preferredDate()).isNull();
        assertThat(raw.preferredStartTime()).isNull();
        assertThat(reads.get()).isEqualTo(1);
        verify(client).parseTask(source, OffsetDateTime.parse(reference).toLocalDate());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"한 시간 뒤에 공부할래|60|11:00", "한 시간 뒤부터 공부할래|60|11:00", "1시간 후에 공부 1시간|60|11:00", "한 시간 반 후에 공부 1시간|90|11:30", "삼십 분 뒤에 공부할래|30|10:30", "삼십분 뒤부터 공부할래|30|10:30", "열다섯 분 후에 공부할래|15|10:15"})
    void 활동_시작_의도는_뒤에와_뒤부터_모두_정확한_시각으로_계산한다(String source, int offset, LocalTime expected) {
        var raw = 응답(source, RelativeStartMode.EXACT, offset, null, null, null, "공부", null);
        var result = 파싱(source, raw);
        assertThat(result.preferredStartTime()).isEqualTo(expected);
        assertThat(result.dateRequired()).isTrue();
        assertThat(result.timeRequired()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026년 10월 22일 저녁 7시 10분 30초에 공부 1시간", "목요일 19:10:00에 공부 1시간", "반드시 저녁7시10분30초에만 공부 1시간"})
    void 초_단위_시작시각은_LLM이_절삭하거나_확인을_반환해도_전용_안내로_거절한다(String source) {
        var raw = 응답(source, null, null, LocalTime.of(19, 10), null, null, "공부", "초 확인 필요");
        오류_검증(source, raw, "UNSUPPORTED_LLM_START_SECONDS", SECONDS_MESSAGE);
    }

    @Test
    void 최소_시작_시각은_고정_시각으로_완화하지_않고_미지원으로_거절한다() {
        String source = "한 시간 뒤부터 가능한 공부 시간 추천해 줘";
        오류_검증(source, 응답(source, RelativeStartMode.NOT_BEFORE, 60, null, null, null, "공부", "최소 시각 확인"),
            "UNSUPPORTED_LLM_RELATIVE_START_RANGE", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"한 시간 뒤부터 가능한 시간 추천해 줘", "19:10:30에만 가능해"})
    void 활동명이_없으면_미지원_조건보다_제목_누락을_먼저_안내한다(String source) {
        오류_검증(source, 응답(source, RelativeStartMode.NOT_BEFORE, 60, null, null, null, null, "확인 필요"),
            "MISSING_LLM_TITLE", null);
    }

    @ParameterizedTest
    @MethodSource("모순_및_누락_응답")
    void 추출_누락_가짜_상대조건_불완전한_간격과_모순은_거절한다(ParsedTaskResponse raw) {
        오류_검증(raw.description(), raw, "LLM_CLARIFICATION_REQUIRED", null);
    }

    static Stream<ParsedTaskResponse> 모순_및_누락_응답() {
        String source = "한 시간 뒤에 공부할래";
        return Stream.of(
            응답(source, null, null, null, null, null, "공부", null),
            응답(source, RelativeStartMode.EXACT, null, null, null, null, "공부", null),
            응답(source, null, 60, null, null, null, "공부", null),
            응답(source, RelativeStartMode.EXACT, 0, null, null, null, "공부", null),
            응답(source, RelativeStartMode.EXACT, -1, null, null, null, "공부", null),
            응답(source, RelativeStartMode.EXACT, 60, LocalTime.of(19, 0), null, null, "공부", null),
            응답("공부 1시간", RelativeStartMode.EXACT, 60, null, null, null, "공부", null),
            응답(source, RelativeStartMode.EXACT, 60, null, LocalDate.of(2026, 10, 20), null, "공부", null),
            응답(source, RelativeStartMode.EXACT, 60, null, null, LocalDate.of(2026, 10, 18), "공부", null)
        );
    }

    @Test
    void 허용_기간을_계산된_필수_날짜가_벗어나면_거절한다() {
        String source = "한 시간 뒤에 공부할래";
        var raw = 응답(source, RelativeStartMode.EXACT, 60, null, null, null, "공부", null);
        var range = new DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21));
        raw = new ParsedTaskResponse(raw.title(), raw.category(), raw.durationMinutes(), null, null, range,
            null, null, null, source, false, false, null, null, raw.relativeStartMode(), raw.startOffsetMinutes());
        오류_검증(source, raw, "LLM_CLARIFICATION_REQUIRED", null);
    }

    private static ParsedTaskResponse 응답(String source, RelativeStartMode mode, Integer offset, LocalTime time,
        LocalDate date, LocalDate deadline, String title, String clarification) {
        return new ParsedTaskResponse(title, TaskCategory.STUDY, 60, date, null, null, null, deadline, null,
            source, false, false, time, clarification, mode, offset);
    }

    private static ParsedTaskResponse 파싱(String source, ParsedTaskResponse raw) {
        OpenAiClient client = mock(OpenAiClient.class);
        when(client.parseTask(eq(source), any(LocalDate.class))).thenReturn(raw);
        return new LlmParsingService(client, Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC))
            .parseTask(new LlmParseRequest(source));
    }

    private static void 오류_검증(String source, ParsedTaskResponse raw, String code, String message) {
        assertThatThrownBy(() -> 파싱(source, raw)).isInstanceOfSatisfying(LlmException.class, error -> {
            assertThat(error.getExceptionCode().name()).isEqualTo(code);
            assertThat(error.getExceptionCode().getStatus().value()).isEqualTo(400);
            if (message != null) assertThat(error.getExceptionCode().getMessage()).isEqualTo(message);
        });
    }
}
