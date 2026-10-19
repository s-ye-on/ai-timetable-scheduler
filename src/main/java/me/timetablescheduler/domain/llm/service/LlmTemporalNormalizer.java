package me.timetablescheduler.domain.llm.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;

/** LLM의 불변 추출 결과를 요청 시각 기준으로 정규화한다. 추천·충돌 판단은 하지 않는다. */
final class LlmTemporalNormalizer {
    private static final Pattern WEEKDAY = Pattern.compile("[월화수목금토일]요일");
    private static final Pattern WEEK = Pattern.compile("(이번|다음)\\s*주");
    private static final Pattern WEEK_RANGE = Pattern.compile("(?:이번|다음)\\s*주\\s*(?:안에|안|내에|내|이내|동안|까지|만)");
    private static final Pattern ABSOLUTE_DATE = Pattern.compile("\\d{4}[-./]\\d{1,2}[-./]\\d{1,2}|(?:\\d{1,2}\\s*월\\s*)?\\d{1,2}\\s*일");
    private static final Pattern DATE_PERIOD = Pattern.compile("(?:이번|다음)\\s*주\\s*(?:안에|내에|이내|동안|까지|만)"
        + "|(?:요일|\\d{1,2}\\s*일)\\s*(?:부터|까지)|허용\\s*기간|날짜\\s*(?:범위|기간)");
    private static final Pattern NIGHT_ONE = Pattern.compile("오늘\\s*밤\\s*(?:(?:반드시|꼭|무조건)\\s*)?(?:한|1)\\s*시(?!간)");
    private static final Pattern RECURRENCE = Pattern.compile("매\\s*(?:주|일|월|년)|격주|반복\\s*(?:일정|해|하|등록|설정)");
    private static final Map<String, DayOfWeek> DAYS = Map.of(
        "월요일", DayOfWeek.MONDAY, "화요일", DayOfWeek.TUESDAY, "수요일", DayOfWeek.WEDNESDAY,
        "목요일", DayOfWeek.THURSDAY, "금요일", DayOfWeek.FRIDAY, "토요일", DayOfWeek.SATURDAY, "일요일", DayOfWeek.SUNDAY);

    void validateSupportedRequest(String source) {
        if (RECURRENCE.matcher(source).find()) {
            throw new LlmException(ExceptionCode.UNSUPPORTED_LLM_RECURRENCE);
        }
    }

    ParsedTaskResponse normalize(ParsedTaskResponse raw, String source, LocalDateTime requestedAt) {
        LocalDate date = raw.preferredDate();
        DayOfWeek day = raw.preferredDayOfWeek();
        DateRange range = raw.preferredDateRange();
        PreferredTimeRange band = normalizeTimeRange(raw, source);
        LocalDate today = requestedAt.toLocalDate();
        DayOfWeek sourceDay = requestedWeekday(source);
        Matcher weekMatcher = WEEK.matcher(source);
        String week = weekMatcher.find() ? weekMatcher.group(1) : null;
        if (weekMatcher.find()) throw clarification();
        boolean weekRange = WEEK_RANGE.matcher(source).find();
        boolean absolute = hasRequestedAbsoluteDate(source);

        if (weekRange) {
            LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if ("다음".equals(week)) monday = monday.plusWeeks(1);
            DateRange calendarWeek = new DateRange(monday, monday.plusDays(6));
            // 별도 기간이 추출됐다면 교집합만 허용한다. 명시된 허용 기간을 넓히지 않는다.
            range = intersect(range, calendarWeek);
        }

        if (NIGHT_ONE.matcher(source).find()) {
            if (absolute || week != null || sourceDay != null || !LocalTime.of(1, 0).equals(raw.preferredStartTime())) {
                throw clarification();
            }
            LocalDate target = today.atTime(1, 0).isAfter(requestedAt) ? today : today.plusDays(1);
            if (date != null && !date.equals(today) && !date.equals(target)) throw clarification();
            date = target;
            day = null;
        } else if (sourceDay != null && !absolute) {
            validateExtractedWeekday(raw, sourceDay);
            if (weekRange || (range != null && week == null)) {
                // G05: 요일 선호를 특정 날짜 D로 바꾸면 D 이전의 허용 날짜를 잃는다.
                date = null;
                day = sourceDay;
            } else {
                if (week != null) {
                    LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                    if ("다음".equals(week)) monday = monday.plusWeeks(1);
                    date = monday.plusDays(sourceDay.getValue() - 1);
                    // G39: 주간 날짜 표현만으로 LLM이 추가한 기간은 필수 기간이 아니다.
                    if (!DATE_PERIOD.matcher(source).find()) range = null;
                } else {
                    date = today.with(TemporalAdjusters.nextOrSame(sourceDay));
                    if (raw.preferredStartTime() != null && !date.atTime(raw.preferredStartTime()).isAfter(requestedAt)) {
                        date = date.plusWeeks(1);
                    }
                }
                day = null;
            }
        } else if (sourceDay == null && !absolute) {
            // 날짜만 명시된 상대 표현도 동일한 요청 기준일을 사용한다.
            Matcher relative = Pattern.compile("오늘|내일|모레").matcher(source);
            String expression = null;
            while (relative.find()) {
                if (source.substring(relative.end()).matches("(?s)^\\s*(?:까지|마감).*$")) continue;
                if (expression != null) throw clarification();
                expression = relative.group();
            }
            if (expression != null) {
                date = today.plusDays(expression.equals("내일") ? 1 : expression.equals("모레") ? 2 : 0);
                day = null;
            }
        } else if (sourceDay != null && date != null && date.getDayOfWeek() != sourceDay) {
            throw clarification();
        }

        validateBounds(raw, date, range);
        return new ParsedTaskResponse(raw.title(), raw.category(), raw.durationMinutes(), date, day, range,
            band, raw.deadline(), raw.priority(), raw.description(), raw.dateRequired(), raw.timeRequired(),
            raw.preferredStartTime(), raw.clarificationReason());
    }

    private DayOfWeek requestedWeekday(String source) {
        Matcher matcher = WEEKDAY.matcher(source);
        DayOfWeek result = null;
        while (matcher.find()) {
            // 마감 요일을 시작 선호 요일로 바꾸지 않는다.
            String suffix = source.substring(matcher.end());
            if (suffix.matches("(?s)^\\s*(?:까지|마감).*$")) continue;
            if (result != null) throw clarification();
            result = DAYS.get(matcher.group());
        }
        return result;
    }

    private boolean hasRequestedAbsoluteDate(String source) {
        Matcher dates = ABSOLUTE_DATE.matcher(source);
        while (dates.find()) {
            String prefix = source.substring(0, dates.start());
            String suffix = source.substring(dates.end());
            if (prefix.matches("(?s).*마감(?:일)?\\s*(?:은|이|:)?\\s*$")
                || suffix.matches("(?s)^\\s*(?:부터|까지|마감).*$")) continue;
            return true;
        }
        return false;
    }

    private void validateExtractedWeekday(ParsedTaskResponse raw, DayOfWeek sourceDay) {
        if (raw.preferredDayOfWeek() == null && raw.preferredDate() == null) throw clarification();
        if (raw.preferredDayOfWeek() != null && raw.preferredDayOfWeek() != sourceDay) throw clarification();
        if (raw.preferredDate() != null && raw.preferredDate().getDayOfWeek() != sourceDay) throw clarification();
    }

    private PreferredTimeRange normalizeTimeRange(ParsedTaskResponse raw, String source) {
        LocalTime time = raw.preferredStartTime();
        if (time == null) return raw.preferredTimeRange();
        if (time.getSecond() != 0 || time.getNano() != 0) {
            throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
        }
        PreferredTimeRange extracted = raw.preferredTimeRange();
        if (extracted != null && extracted != PreferredTimeRange.ANYTIME && !containsStart(extracted, time)) {
            throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
        }
        // LLM이 모순된 명시 시간대를 누락한 경우도, 자동 추론으로 숨기지 않는다.
        Matcher explicitBand = Pattern.compile("(아침|점심|저녁)\\s*(?:(?:반드시|꼭)\\s*)?(?:\\d|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열)").matcher(source);
        if (explicitBand.find()) {
            PreferredTimeRange requiredBand = switch (explicitBand.group(1)) {
                case "아침" -> PreferredTimeRange.MORNING;
                case "점심" -> PreferredTimeRange.LUNCH;
                default -> PreferredTimeRange.EVENING;
            };
            if (!containsStart(requiredBand, time)) throw clarification();
        }
        for (PreferredTimeRange candidate : PreferredTimeRange.values()) {
            if (candidate != PreferredTimeRange.ANYTIME && containsStart(candidate, time)) return candidate;
        }
        return null;
    }

    private boolean containsStart(PreferredTimeRange range, LocalTime time) {
        return !time.isBefore(range.getStartTime()) && time.isBefore(range.getEndTime());
    }

    private DateRange intersect(DateRange first, DateRange second) {
        if (first == null) return second;
        LocalDate start = first.startDate().isAfter(second.startDate()) ? first.startDate() : second.startDate();
        LocalDate end = first.endDate().isBefore(second.endDate()) ? first.endDate() : second.endDate();
        if (start.isAfter(end)) throw clarification();
        return new DateRange(start, end);
    }

    private void validateBounds(ParsedTaskResponse raw, LocalDate date, DateRange range) {
        if (range != null && raw.deadline() != null && range.startDate().isAfter(raw.deadline())) throw clarification();
        if (Boolean.TRUE.equals(raw.dateRequired()) && date != null
            && ((range != null && (date.isBefore(range.startDate()) || date.isAfter(range.endDate())))
                || (raw.deadline() != null && date.isAfter(raw.deadline())))) throw clarification();
    }

    private LlmException clarification() { return new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED); }
}
