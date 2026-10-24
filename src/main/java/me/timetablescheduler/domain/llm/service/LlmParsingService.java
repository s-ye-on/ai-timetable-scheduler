package me.timetablescheduler.domain.llm.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.llm.dto.DateRange;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskPriority;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.LlmException;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

// LLM 호출
// 응답 검증
// 기본값 보정
// Task 생성에 쓸 수 있는 형태로 정리
@Service
@RequiredArgsConstructor
public class LlmParsingService {
	private static final int TIME_SLOT_MINUTES = 30;
	private static final int DEFAULT_DURATION_MINUTES = 60; // 소요 시간 없을 때 기본값
	private static final int TASK_TITLE_MAX_LENGTH = 100;
	private static final int TASK_DESCRIPTION_MAX_LENGTH = 500;

	private final OpenAiClient openAiClient;
	private final Clock clock;
	private final LlmTemporalNormalizer temporalNormalizer = new LlmTemporalNormalizer();

	public ParsedTaskResponse parseTask(LlmParseRequest request) {
		// 가벼운 방어 코드
		if (request == null || request.message() == null || request.message().isBlank()) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_REQUEST);
		}

		LocalDateTime requestedAt = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"));
		ParsedTaskResponse parsed = openAiClient.parseTask(request.message(), requestedAt.toLocalDate());

		// NPE 방지
		if (parsed == null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		// 원본 DTO는 불변으로 유지하고, 명시 시각 유실·모순을 정규화로 숨기지 않는다.
		validateTitle(parsed);
		temporalNormalizer.validateSupportedRequest(request.message());
		validateSourceIntent(request.message(), parsed);
		validateClarification(parsed);
		validateDateCondition(parsed);
		validateDateRange(parsed.preferredDateRange());
		ParsedTaskResponse temporal = temporalNormalizer.normalize(parsed, request.message(), requestedAt);
		ParsedTaskResponse normalized = normalize(temporal, request.message());
		validate(normalized);
		validateSourceIntent(request.message(), normalized);

		return normalized;
	}

	// 검증
	private void validate(ParsedTaskResponse response) {
		validateClarification(response);
		validateRequiredFields(response);
		validateDuration(response.durationMinutes());
		validateDateCondition(response);
		validateDateRange(response.preferredDateRange());
		validateIntentCondition(response);
	}

	private void validateClarification(ParsedTaskResponse response) {
		if (response.clarificationReason() != null && !response.clarificationReason().isBlank()) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
	}

	private void validateRequiredFields(ParsedTaskResponse response) {
		validateTitle(response);

		if (response.title().length() > TASK_TITLE_MAX_LENGTH
			|| response.description() == null
			|| response.description().length() > TASK_DESCRIPTION_MAX_LENGTH) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		if (response.category() == null) {
			throw new LlmException(ExceptionCode.MISSING_LLM_CATEGORY);
		}
	}

	private void validateTitle(ParsedTaskResponse response) {
		if (response.title() == null || response.title().isBlank()) {
			throw new LlmException(ExceptionCode.MISSING_LLM_TITLE);
		}
	}

	private void validateDateCondition(ParsedTaskResponse response) {
		if (response.preferredDate() != null && response.preferredDayOfWeek() != null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DATE_CONDITION);
		}
	}

	private void validateIntentCondition(ParsedTaskResponse response) {
		if (response.dateRequired() == null || response.timeRequired() == null) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}

		boolean hasDateOrDay = response.preferredDate() != null || response.preferredDayOfWeek() != null;
		if (Boolean.TRUE.equals(response.dateRequired()) && !hasDateOrDay) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		LocalTime preferredStartTime = response.preferredStartTime();
		if (preferredStartTime != null && (preferredStartTime.getSecond() != 0 || preferredStartTime.getNano() != 0)) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		boolean hasUsableTimeRange = response.preferredTimeRange() != null
			&& response.preferredTimeRange() != PreferredTimeRange.ANYTIME;
		if (Boolean.TRUE.equals(response.timeRequired()) && preferredStartTime == null && !hasUsableTimeRange) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		if (preferredStartTime != null && hasUsableTimeRange
			&& (preferredStartTime.isBefore(response.preferredTimeRange().getStartTime())
				|| !preferredStartTime.isBefore(response.preferredTimeRange().getEndTime()))) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}
	}

	private void validateDuration(Integer durationMinutes) {
		if (durationMinutes <= 0) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DURATION);
		}

		if (durationMinutes % TIME_SLOT_MINUTES != 0) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DURATION);
		}
	}

	private void validateDateRange(DateRange dateRange) {
		if (dateRange == null) { // 항상 필요한 필드 아님
			return;
		}

		if (dateRange.startDate() == null || dateRange.endDate() == null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DATE_RANGE);
		}

		if (dateRange.startDate().isAfter(dateRange.endDate())) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DATE_RANGE);
		}
	}

	/** 원문을 다시 구조화하지 않고, 명백한 미지원 조건과 명시 시각의 유실·모순만 거절한다. */
	private void validateSourceIntent(String source, ParsedTaskResponse parsed) {
		// 숫자 의미를 다시 해석하지 않고 상대 조건의 추출 누락·가짜 필드만 검사한다.
		boolean hasRelativeSource = Pattern.compile("(?<![가-힣\\d])(?:\\d+|한|두|세|네|다섯|여섯|일곱|여덟|아홉|열|반)\\s*(?:시간(?:\\s*반)?|분)\\s*(?:뒤|후)(?=\\s|에|부터|만|$)")
			.matcher(source).find();
		boolean hasRelativeFields = parsed.relativeStartMode() != null || parsed.startOffsetMinutes() != null;
		// LLM이 추출한 간격의 한글 숫자는 Java에서 읽지 않는다. 단위·상대 표현만 확인한다.
		boolean hasRelativeUnit = Pattern.compile("(?:시간(?:\\s*반)?|분)\\s*(?:뒤|후)(?=\\s|에|부터|만|$)")
			.matcher(source).find();
		if ((hasRelativeSource && !hasRelativeFields) || (hasRelativeFields && !hasRelativeUnit)
			|| (hasRelativeFields && (parsed.relativeStartMode() == null || parsed.startOffsetMinutes() == null))) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		if (parsed.relativeStartMode() == me.timetablescheduler.domain.llm.dto.RelativeStartMode.NOT_BEFORE) {
			throw new LlmException(ExceptionCode.UNSUPPORTED_LLM_RELATIVE_START_RANGE);
		}
		// 명시적 요일 선택만 거절한다. 활동명의 /는 대상이 아니다.
		if (Pattern.compile("(?<![가-힣])[월화수목금토일](?:요일)?(?:\\s*/\\s*[월화수목금토일](?:요일)?)+\\s*중\\s*(?:하루|하나|한\\s*날)")
				.matcher(source).find()) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		if (Pattern.compile("(?:요일.{0,8}(?:제외|빼고|말고)|(?:요일|오전|오후|점심|저녁|밤)\\s*(?:또는|혹은)|(?:이번|다음)\\s*주\\s*(?:쯤|정도)|(?:되도록|가능하면)\\s*(?:이번|다음)\\s*주)")
			.matcher(source).find()) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		if (Pattern.compile("(?:요일|\\d{1,2}일)\\s*(?:에)?만").matcher(source).find()
			&& !Boolean.TRUE.equals(parsed.dateRequired())) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		if (Pattern.compile("(?:시(?:\\s*\\d{1,2}분)?|분|오전|오후|점심|저녁)\\s*(?:에)?만").matcher(source).find()
			&& !Boolean.TRUE.equals(parsed.timeRequired())) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		Matcher hours = Pattern.compile("(?<!\\d)(\\d{1,2}|열두|열한|열|아홉|여덟|일곱|여섯|다섯|네|세|두|한)\\s*시(?!간)(?:\\s*(\\d{1,2})\\s*분|\\s*(반))?").matcher(source);
		LocalTime sourceTime = null;
		while (hours.find()) {
			String suffix = source.substring(hours.end()).stripLeading();
			// 기존 시각 인식 범위를 재사용하여 붙여 쓴 시간대도 놓치지 않는다.
			if (Pattern.compile("^(?:\\d{1,2}|[영공일이삼사오육칠팔구십]+)\\s*초(?=\\s|에|부터|만|$)").matcher(suffix).find()) {
				throw new LlmException(ExceptionCode.UNSUPPORTED_LLM_START_SECONDS);
			}
			if (suffix.startsWith("까지") || suffix.startsWith("이후") || suffix.startsWith("이전")) {
				throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
			}
			String period = periodBefore(source, hours.start());
			int hour = hourNumber(hours.group(1));
			int minute = hours.group(2) != null ? Integer.parseInt(hours.group(2)) : hours.group(3) != null ? 30 : 0;
			if (hour >= 1 && hour <= 12 && period == null) {
				throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
			}
			hour = normalizePeriodHour(hour, period);
			sourceTime = verifySourceTime(parsed, sourceTime, hour, minute);
		}
		Matcher clockTimes = Pattern.compile("(?<!\\d)(\\d{1,2}):(\\d{2})(?!\\d)").matcher(source);
		while (clockTimes.find()) {
			String suffix = source.substring(clockTimes.end()).stripLeading();
			if (Pattern.compile("^:\\d{2}(?!\\d)").matcher(suffix).find()) {
				throw new LlmException(ExceptionCode.UNSUPPORTED_LLM_START_SECONDS);
			}
			if (suffix.startsWith("까지") || suffix.startsWith("이후") || suffix.startsWith("이전")) {
				throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
			}
			sourceTime = verifySourceTime(parsed, sourceTime,
				normalizePeriodHour(Integer.parseInt(clockTimes.group(1)), periodBefore(source, clockTimes.start())),
				Integer.parseInt(clockTimes.group(2)));
		}
	}

	private String periodBefore(String source, int offset) {
		String period = null;
		Matcher periods = Pattern.compile("오전|오후|아침|저녁|밤|새벽|점심").matcher(source.substring(0, offset));
		while (periods.find()) period = periods.group();
		return period;
	}

	private int normalizePeriodHour(int hour, String period) {
		if (hour < 1 || hour > 12 || period == null) return hour;
		// 밤 12시와 밤 1~5시는 자정 이후 시각으로 비교한다.
		if (period.equals("밤")) return hour == 12 ? 0 : hour <= 5 ? hour : hour + 12;
		boolean afternoon = period.equals("오후") || period.equals("저녁") || period.equals("점심");
		return hour % 12 + (afternoon ? 12 : 0);
	}

	private LocalTime verifySourceTime(ParsedTaskResponse parsed, LocalTime previous, int hour, int minute) {
		if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		LocalTime time = LocalTime.of(hour, minute);
		if ((previous != null && !previous.equals(time)) || !time.equals(parsed.preferredStartTime())) {
			throw new LlmException(ExceptionCode.LLM_CLARIFICATION_REQUIRED);
		}
		return time;
	}

	private int hourNumber(String value) {
		Map<String, Integer> korean = Map.ofEntries(Map.entry("한",1), Map.entry("두",2), Map.entry("세",3),
			Map.entry("네",4), Map.entry("다섯",5), Map.entry("여섯",6), Map.entry("일곱",7),
			Map.entry("여덟",8), Map.entry("아홉",9), Map.entry("열",10), Map.entry("열한",11), Map.entry("열두",12));
		return korean.containsKey(value) ? korean.get(value) : Integer.parseInt(value);
	}

	// 값 보정
	private ParsedTaskResponse normalize(ParsedTaskResponse parsed, String originalMessage) {
		int duration = parsed.durationMinutes() == null
			? DEFAULT_DURATION_MINUTES
			: parsed.durationMinutes();

		TaskPriority priority = parsed.priority() == null ? TaskPriority.NORMAL : parsed.priority();

		// 설명은 LLM의 요약·변경 결과가 아닌 실제 요청 원문을 저장한다.
		String description = originalMessage;

		return new ParsedTaskResponse(
			parsed.title(),
			parsed.category(),
			duration,
			parsed.preferredDate(),
			parsed.preferredDayOfWeek(),
			parsed.preferredDateRange(),
			parsed.preferredTimeRange(),
			parsed.deadline(),
			priority,
			description,
			parsed.dateRequired(),
			parsed.timeRequired(),
			parsed.preferredStartTime(),
			parsed.clarificationReason(),
			parsed.relativeStartMode(),
			parsed.startOffsetMinutes()
		);
	}
}
