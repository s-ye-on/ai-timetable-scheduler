package me.timetablescheduler.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.preference.Preference;
import me.timetablescheduler.domain.preference.type.DeadlineTiming;
import me.timetablescheduler.domain.preference.type.ScheduleDensity;
import me.timetablescheduler.domain.recommendation.policy.RecommendationPolicy;
import me.timetablescheduler.domain.recommendation.type.*;
import me.timetablescheduler.domain.task.Task;
import me.timetablescheduler.domain.task.type.*;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;
import me.timetablescheduler.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(classes = {LlmParsingService.class, LlmNaturalLanguagePolicyTest.Fixtures.class})
class LlmNaturalLanguagePolicyTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    @Autowired private LlmParsingService service;
    @Autowired private Fixture fixture;
    @Autowired private RequestClock clock;

    @BeforeEach
    void 기준_시각을_고정한다() {
        clock.set("2026-10-19T10:00:00");
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 0), false, false, null);
    }

    @Test
    void G01_단독_목요일은_특정_미래_날짜로_정규화하고_원본은_보존한다() {
        ParsedTaskResponse raw = fixture.raw;
        var result = 분석("목요일 저녁 7시에 알고리즘 공부 2시간");
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(result.preferredDayOfWeek()).isNull();
        assertThat(result.preferredStartTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(result.preferredTimeRange()).isEqualTo(PreferredTimeRange.EVENING);
        assertThat(result.dateRequired()).isFalse();
        assertThat(result.timeRequired()).isFalse();
        assertThat(raw.preferredDate()).isNull();
        assertThat(raw.preferredDayOfWeek()).isEqualTo(DayOfWeek.THURSDAY);
        assertThat(raw.preferredTimeRange()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-22T18:59:59,2026-10-22", "2026-10-22T19:00:00,2026-10-29",
        "2026-10-22T19:00:01,2026-10-29", "2026-12-31T19:00:00,2027-01-07"
    })
    void G01_정확한_시각_경계와_연말에도_엄격히_미래인_목요일을_사용한다(String now, LocalDate expected) {
        clock.set(now);
        assertThat(분석("목요일 저녁 7시에 공부 1시간").preferredDate()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"이번 주,2026-10-22", "다음 주,2026-10-29"})
    void G01_명시한_주간_조건은_시각이_지나도_다른_주로_옮기지_않는다(String week, LocalDate expected) {
        clock.set("2026-10-22T20:00:00");
        assertThat(분석(week + " 목요일 저녁 7시에 공부 1시간").preferredDate()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "반드시 목요일 저녁 7시에만 가능해",
        "이번 주 안에 화요일 점심이면 좋겠어"
    })
    void G02_G05_활동명_누락은_일반_확인보다_전용_400_오류가_우선한다(String source) {
        fixture.raw = 응답(null, null, DayOfWeek.THURSDAY, null, null, null, true, true, "활동명이 필요합니다.");
        assertThatThrownBy(() -> 분석(source)).isInstanceOf(LlmException.class).satisfies(error -> {
            var code = ((LlmException) error).getExceptionCode();
            assertThat(code).isEqualTo(ExceptionCode.MISSING_LLM_TITLE);
            assertThat(code.getStatus().value()).isEqualTo(400);
            assertThat(code.getMessage()).isEqualTo("어떤 일정을 계획하고 계신가요? 일정 이름을 포함하여 알려주세요.");
        });
    }

    @Test
    void 활동은_있지만_모호한_응답은_카테고리_누락보다_확인을_먼저_요청한다() {
        fixture.raw = new ParsedTaskResponse("공부", null, null, null, null, null, null,
            null, null, null, false, false, null, "오전 오후 확인");
        확인_오류("목요일 7시에 공부");
    }

    @ParameterizedTest
    @CsvSource({"09:00,MORNING", "11:59,MORNING", "12:00,LUNCH", "13:59,LUNCH",
        "14:00,AFTERNOON", "17:59,AFTERNOON", "18:00,EVENING", "19:10,EVENING", "21:59,EVENING"})
    void G04_정확한_시각을_그대로_보존하며_기존_시간대_경계를_사용한다(LocalTime time, PreferredTimeRange range) {
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, time, false, false, null);
        var result = 분석("목요일 " + time + "에 공부하고 싶어");
        assertThat(result.preferredStartTime()).isEqualTo(time);
        assertThat(result.preferredTimeRange()).isEqualTo(range);
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 22));
    }

    @ParameterizedTest
    @CsvSource({"00:00", "01:00", "08:59", "22:00", "23:59"})
    void G04_기존_시간대_밖의_시각은_ANYTIME으로_만들지_않는다(LocalTime time) {
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, time, false, false, null);
        assertThat(분석("목요일 " + time + "에 공부").preferredTimeRange()).isNull();
    }

    @Test
    void G04_원문의_명시_시간대와_시각의_모순을_추론으로_숨기지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 0), false, false, null);
        확인_오류("목요일 점심 19시에 공부 1시간");
    }

    @Test
    void G05_지난_화요일_선호와_월요일부터_일요일까지의_필수_기간을_보존한다() {
        clock.set("2026-10-21T10:00:00");
        fixture.raw = 응답("공부", null, DayOfWeek.TUESDAY, null, PreferredTimeRange.LUNCH, null, false, false, null);
        var parsed = 분석("이번 주 안에 화요일 점심에 공부 1시간이면 좋겠어");
        assertThat(parsed.preferredDate()).isNull();
        assertThat(parsed.preferredDayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
        assertThat(parsed.preferredDateRange()).isEqualTo(new DateRange(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)));
        assertThat(parsed.dateRequired()).isFalse();
        var dates = 정책().resolveCandidateDates(Task_생성(parsed), LocalDate.of(2026, 10, 21));
        assertThat(dates).containsExactly(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22),
            LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25));
    }

    @ParameterizedTest
    @CsvSource({"2026-10-19T00:00:00,2026-10-19", "2026-10-19T00:59:59,2026-10-19",
        "2026-10-19T01:00:00,2026-10-20", "2026-10-19T01:00:01,2026-10-20",
        "2026-10-19T23:59:59,2026-10-20", "2026-10-31T23:59:59,2026-11-01",
        "2026-12-31T23:59:59,2027-01-01"})
    void G37_오늘_밤_한_시는_요청_이후이며_자정과_월말_연말을_계산한다(String now, LocalDate expected) {
        clock.set(now);
        fixture.raw = 응답("공부", clock.current.atZone(SEOUL).toLocalDate(), null, null, null,
            LocalTime.of(1, 0), false, false, null);
        var result = 분석("오늘 밤 한 시에 공부 1시간");
        assertThat(result.preferredDate()).isEqualTo(expected);
        assertThat(result.preferredStartTime()).isEqualTo(LocalTime.of(1, 0));
        assertThat(result.preferredTimeRange()).isNull();
        assertThat(result.timeRequired()).isFalse();
        assertThat(clock.reads.get()).isEqualTo(1);
    }

    @Test
    void G37_선호_한_시는_낮_대안이_가능하지만_필수_한_시는_다른_시각을_추천하지_않는다() {
        fixture.raw = 응답("공부", LocalDate.of(2026, 10, 19), null, null, null, LocalTime.of(1, 0), false, false, null);
        var soft = 분석("오늘 밤 한 시에 공부 1시간");
        var preference = 선호_생성();
        var candidates = 정책().generateCandidates(Task_생성(soft), preference, List.of(), List.of());
        assertThat(candidates).isNotEmpty().allSatisfy(c -> {
            assertThat(c.startAt().toLocalDate()).isAfterOrEqualTo(LocalDate.of(2026, 10, 20));
            assertThat(c.startAt().getHour()).isGreaterThanOrEqualTo(9);
        });
        fixture.raw = 응답("공부", LocalDate.of(2026, 10, 19), null, null, null, LocalTime.of(1, 0), false, true, null);
        var hard = 분석("오늘 밤 반드시 한 시에만 공부 1시간");
        assertThat(정책().generateCandidates(Task_생성(hard), preference, List.of(), List.of())).isEmpty();
    }

    @Test
    void G39_다음_주_월요일은_특정_날짜이며_암묵적인_주간_기간을_추가하지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.MONDAY, null, PreferredTimeRange.LUNCH, null, false, false, null);
        var result = 분석("다음 주 월요일 점심에 공부 1시간");
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(result.preferredDayOfWeek()).isNull();
        assertThat(result.preferredDateRange()).isNull();
        var dates = 정책().resolveCandidateDates(Task_생성(result), LocalDate.of(2026, 10, 19));
        assertThat(dates).hasSize(14);
        assertThat(dates.getLast()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @ParameterizedTest
    @CsvSource({"2026-10-25T23:59:59,2026-10-26", "2026-10-26T00:00:00,2026-11-02",
        "2026-12-31T23:59:59,2027-01-04"})
    void G39_일요일_월요일_자정과_연말의_다음_주를_계산한다(String now, LocalDate expected) {
        clock.set(now);
        fixture.raw = 응답("공부", null, DayOfWeek.MONDAY, null, PreferredTimeRange.LUNCH, null, false, false, null);
        assertThat(분석("다음 주 월요일 점심에 공부 1시간").preferredDate()).isEqualTo(expected);
    }

    @Test
    void G39_LLM이_임의로_만든_다음_주_허용_기간은_필수로_추가하지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.MONDAY,
            new DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
            PreferredTimeRange.LUNCH, null, false, false, null);
        assertThat(분석("다음 주 월요일 점심에 공부 1시간").preferredDateRange()).isNull();
    }

    @Test
    void G05_명시한_허용_기간과_마감일을_정규화가_완화하지_않는다() {
        fixture.raw = new ParsedTaskResponse("공부", TaskCategory.STUDY, 60, null, DayOfWeek.TUESDAY,
            new DateRange(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 23)), PreferredTimeRange.LUNCH,
            LocalDate.of(2026, 10, 22), TaskPriority.NORMAL, null, false, false, null, null);
        var result = 분석("이번 주 안에 20일부터 23일까지 화요일 점심에 공부 1시간, 마감은 22일");
        assertThat(result.preferredDateRange()).isEqualTo(fixture.raw.preferredDateRange());
        assertThat(result.deadline()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(정책().resolveCandidateDates(Task_생성(result), LocalDate.of(2026, 10, 19)))
            .containsExactly(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22));
    }

    @Test
    void G02_활동을_보완한_필수_날짜와_시각은_다른_후보로_완화하지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 0), true, true, null);
        var result = 분석("반드시 목요일 저녁 7시에만 공부 1시간 가능해");
        assertThat(result.dateRequired()).isTrue();
        assertThat(result.timeRequired()).isTrue();
        var candidates = 정책().generateCandidates(Task_생성(result), 선호_생성(), List.of(), List.of());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().startAt()).isEqualTo(LocalDate.of(2026, 10, 22).atTime(19, 0));
    }

    @Test
    void 명시_이번_주_필수_목요일이_이미_지났으면_다음_주_후보를_만들지_않는다() {
        clock.set("2026-10-23T10:00:00");
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 0), true, true, null);
        var result = 분석("이번 주 목요일 저녁 7시에만 공부 1시간");
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(정책().generateCandidates(Task_생성(result), 선호_생성(), List.of(), List.of())).isEmpty();
    }

    @Test
    void 원문과_다른_요일이나_유실된_요일을_정규화로_숨기지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.TUESDAY, null, null, LocalTime.of(19, 0), false, false, null);
        확인_오류("목요일 저녁 7시에 공부");
        fixture.raw = 응답("공부", null, null, null, null, LocalTime.of(19, 0), false, false, null);
        확인_오류("목요일 저녁 7시에 공부");
    }

    @Test
    void 명시_날짜와_요일이_모순되면_다음_목요일로_덮어쓰지_않는다() {
        fixture.raw = 응답("공부", LocalDate.of(2026, 10, 21), null, null, null, LocalTime.of(19, 0), false, false, null);
        확인_오류("2026-10-21 목요일 저녁 7시에 공부");
    }

    @Test
    void 반복문_공부라는_활동을_반복_일정으로_오인하지_않는다() {
        fixture.raw = 응답("반복문 공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 0), false, false, null);
        assertThat(분석("목요일 저녁 7시에 반복문 공부 1시간").title()).isEqualTo("반복문 공부");
    }

    @Test
    void 마감_상대_날짜를_시작_선호_날짜로_추가하지_않는다() {
        fixture.raw = new ParsedTaskResponse("과제", TaskCategory.ASSIGNMENT, 60, null, null, null, null,
            LocalDate.of(2026, 10, 20), TaskPriority.NORMAL, null, false, false, null, null);
        var result = 분석("내일까지 과제 1시간 할 시간 추천해 줘");
        assertThat(result.preferredDate()).isNull();
        assertThat(result.deadline()).isEqualTo(LocalDate.of(2026, 10, 20));
    }

    @Test
    void G01_숫자_마감일이_있어도_단독_요일의_특정_날짜를_계산한다() {
        fixture.raw = new ParsedTaskResponse("공부", TaskCategory.STUDY, 60, null, DayOfWeek.THURSDAY,
            null, null, LocalDate.of(2026, 10, 30), null, null, false, false, LocalTime.of(19, 0), null);
        var result = 분석("목요일 저녁 7시에 공부 1시간, 마감은 30일");
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 22));
        assertThat(result.preferredDayOfWeek()).isNull();
        assertThat(result.deadline()).isEqualTo(LocalDate.of(2026, 10, 30));
    }

    @Test
    void G39_소요_시간의_동안을_날짜_허용_기간으로_오인하지_않는다() {
        fixture.raw = 응답("공부", null, DayOfWeek.MONDAY,
            new DateRange(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 1)),
            PreferredTimeRange.LUNCH, null, false, false, null);
        var result = 분석("다음 주 월요일 점심에 1시간 동안 공부");
        assertThat(result.preferredDate()).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(result.preferredDateRange()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"매주 목요일 저녁 7시에 공부", "매일 공부 1시간"})
    void 반복_요청은_LLM이_성공으로_응답해도_미지원으로_안내한다(String source) {
        assertThatThrownBy(() -> 분석(source)).isInstanceOf(LlmException.class).satisfies(e -> {
            var code = ((LlmException) e).getExceptionCode();
            assertThat(code).isEqualTo(ExceptionCode.UNSUPPORTED_LLM_RECURRENCE);
            assertThat(code.getStatus().value()).isEqualTo(400);
            assertThat(code.getMessage()).contains("반복 일정은 현재 지원하지 않습니다");
        });
    }

    @Test
    void 모호한_오전_오후는_LLM이_성공으로_응답해도_확인을_요청한다() {
        확인_오류("목요일 7시에 공부 1시간");
    }

    @Test
    void G04_자동_시간대의_30점은_전체_구간에_적용하고_정확_시각은_별도로_분류한다() {
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(19, 10), false, false, null);
        var result = 분석("목요일 19시 10분에 공부하고 싶어");
        var slots = 정책().generateCandidates(Task_생성(result), 선호_생성(), List.of(), List.of());
        var exact = slots.stream().filter(c -> c.startAt().equals(LocalDate.of(2026, 10, 22).atTime(19, 10))).findFirst().orElseThrow();
        assertThat(exact.score()).isEqualTo(89);
        assertThat(exact.matchType()).isEqualTo(RecommendationMatchType.EXACT);
        assertThat(slots).anySatisfy(c -> {
            assertThat(c.startAt()).isEqualTo(LocalDate.of(2026, 10, 22).atTime(19, 30));
            assertThat(c.score()).isEqualTo(89);
            assertThat(c.matchType()).isEqualTo(RecommendationMatchType.TIME_CHANGED);
        });
        fixture.raw = 응답("공부", null, DayOfWeek.THURSDAY, null, null, LocalTime.of(21, 30), false, false, null);
        var late = 분석("목요일 21시 30분에 공부 1시간");
        var latePreference = Preference.create(사용자(), PreferredTimeRange.ANYTIME, LocalTime.of(9, 0),
            LocalTime.of(23, 0), 0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED);
        var over = 정책().generateCandidates(Task_생성(late), latePreference, List.of(), List.of()).stream()
            .filter(c -> c.startAt().equals(LocalDate.of(2026, 10, 22).atTime(21, 30))).findFirst().orElseThrow();
        assertThat(over.score()).isEqualTo(59);
    }

    private ParsedTaskResponse 분석(String source) { return service.parseTask(new LlmParseRequest(source)); }
    private void 확인_오류(String source) {
        assertThatThrownBy(() -> 분석(source)).isInstanceOf(LlmException.class)
            .satisfies(e -> assertThat(((LlmException) e).getExceptionCode()).isEqualTo(ExceptionCode.LLM_CLARIFICATION_REQUIRED));
    }
    private RecommendationPolicy 정책() { return new RecommendationPolicy(Clock.fixed(clock.current, SEOUL)); }
    private User 사용자() { return new User("정책", "policy@example.com", "test-only-hash"); }
    private Preference 선호_생성() {
        return Preference.create(사용자(), PreferredTimeRange.ANYTIME, LocalTime.of(9, 0), LocalTime.of(22, 0),
            0, ScheduleDensity.BALANCED, DeadlineTiming.BALANCED);
    }
    private Task Task_생성(ParsedTaskResponse p) {
        return Task.create(사용자(), p.title(), p.category(), p.durationMinutes(), p.preferredDate(), p.preferredDayOfWeek(),
            p.preferredDateRange() == null ? null : p.preferredDateRange().startDate(),
            p.preferredDateRange() == null ? null : p.preferredDateRange().endDate(), p.preferredTimeRange(),
            p.deadline(), p.priority(), p.description(), p.dateRequired(), p.timeRequired(), p.preferredStartTime());
    }
    private ParsedTaskResponse 응답(String title, LocalDate date, DayOfWeek day, DateRange range,
        PreferredTimeRange band, LocalTime time, Boolean dateHard, Boolean timeHard, String clarification) {
        return new ParsedTaskResponse(title, TaskCategory.STUDY, 60, date, day, range, band, null,
            TaskPriority.NORMAL, null, dateHard, timeHard, time, clarification);
    }

    static class Fixture { ParsedTaskResponse raw; }
    static class RequestClock extends Clock {
        Instant current;
        AtomicInteger reads = new AtomicInteger();
        void set(String local) { current = LocalDateTime.parse(local).atZone(SEOUL).toInstant(); reads.set(0); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(current, zone); }
        public Instant instant() { reads.incrementAndGet(); return current; }
    }
    @TestConfiguration
    static class Fixtures {
        @Bean Fixture fixture() { return new Fixture(); }
        @Bean RequestClock clock() { return new RequestClock(); }
        @Bean OpenAiClient openAiClient(Fixture fixture) {
            return Mockito.mock(OpenAiClient.class, invocation -> invocation.getMethod().getName().equals("parseTask")
                ? fixture.raw : Answers.RETURNS_DEFAULTS.answer(invocation));
        }
    }
}
