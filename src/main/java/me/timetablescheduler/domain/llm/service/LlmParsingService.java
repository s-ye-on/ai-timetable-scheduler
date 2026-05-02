package me.timetablescheduler.domain.llm.service;

import lombok.RequiredArgsConstructor;
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
	private static final int FALLBACK_DURATION_MINUTES = 60; // LLM 파싱 실패 -> fallback

	private final OpenAiClient openAiClient;

	public ParsedTaskResponse parseTask(LlmParseRequest request) {
		ParsedTaskResponse parsed = openAiClient.parseTask(request.message());

		// NPE 방지
		if(parsed == null) {
			throw new LlmException(ExceptionCode.INVALID_LLM_PARSE_RESULT);
		}

		ParsedTaskResponse normalized = normalize(parsed, request.message());
		validate(normalized);

		return normalized;
	}

	// 검증
	private void validate(ParsedTaskResponse response) {
		if (response.title() == null || response.title().isBlank()) {
			throw new IllegalArgumentException("제목이 필요합니다");
		}

		if (response.category() == null) {
			throw new IllegalArgumentException("카테고리가 필요합니다");
		}

		if (response.durationMinutes() == null || response.durationMinutes() <= 0) {
			throw new IllegalArgumentException("소요 시간이 필요합니다.");
		}

		if (response.durationMinutes() % TIME_SLOT_MINUTES != 0) {
			throw new IllegalArgumentException("소요 시간은 30분 단위여야 합니다.");
		}

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
			throw new IllegalArgumentException(
				"preferredDate, preferredDayOfWeek, preferredDateRange 중 정확히 하나만 필요합니다."
			);
		}
	}

	// 값 보정
	private ParsedTaskResponse normalize(ParsedTaskResponse parsed, String originalMessage) {
		int duration = parsed.durationMinutes() == null
			? FALLBACK_DURATION_MINUTES
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
