package me.timetablescheduler.domain.llm.service;

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

// LLM 호출
// 응답 검증
// 기본값 보정
// Task 생성에 쓸 수 있는 형태로 정리
@Service
@RequiredArgsConstructor
public class LlmParsingService {
	private static final int TIME_SLOT_MINUTES = 30;
	private static final int DEFAULT_DURATION_MINUTES = 60; // 소요 시간 없을 때 기본값

	private final OpenAiClient openAiClient;

	public ParsedTaskResponse parseTask(LlmParseRequest request) {
		// 가벼운 방어 코드
		if (request == null || request.message() == null || request.message().isBlank()) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_REQUEST);
		}

		ParsedTaskResponse parsed = openAiClient.parseTask(request.message());

		// NPE 방지
		if (parsed == null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		ParsedTaskResponse normalized = normalize(parsed, request.message());
		validate(normalized);

		return normalized;
	}

	// 검증
	private void validate(ParsedTaskResponse response) {
		validateRequiredFields(response);
		validateDuration(response.durationMinutes());
		validateDateCondition(response);
		validateDateRange(response.preferredDateRange());
	}

	private void validateRequiredFields(ParsedTaskResponse response) {
		if (response.title() == null || response.title().isBlank()) {
			throw new LlmException(ExceptionCode.MISSING_LLM_TITLE);
		}

		if (response.category() == null) {
			throw new LlmException(ExceptionCode.MISSING_LLM_CATEGORY);
		}
	}

	private void validateDateCondition(ParsedTaskResponse response) {
		int dateConditionCount = 0;

		if (response.preferredDate() != null) {
			dateConditionCount++;
		}

		if (response.preferredDayOfWeek() != null) {
			dateConditionCount++;
		}

		if (response.preferredDateRange() != null) {
			dateConditionCount++;
		}

		if (dateConditionCount != 1) {
			throw new LlmException(ExceptionCode.INVALID_LLM_DATE_CONDITION);
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

	// 값 보정
	private ParsedTaskResponse normalize(ParsedTaskResponse parsed, String originalMessage) {
		int duration = parsed.durationMinutes() == null
			? DEFAULT_DURATION_MINUTES
			: parsed.durationMinutes();

		TaskPriority priority = parsed.priority() == null ? TaskPriority.NORMAL : parsed.priority();

		PreferredTimeRange timeRange = parsed.preferredTimeRange() == null
			? PreferredTimeRange.ANYTIME
			: parsed.preferredTimeRange();

		String description = parsed.description() == null || parsed.description().isBlank()
			? originalMessage
			: parsed.description();

		return new ParsedTaskResponse(
			parsed.title(),
			parsed.category(),
			duration,
			parsed.preferredDate(),
			parsed.preferredDayOfWeek(),
			parsed.preferredDateRange(),
			timeRange,
			parsed.deadline(),
			priority,
			description
		);
	}
}
