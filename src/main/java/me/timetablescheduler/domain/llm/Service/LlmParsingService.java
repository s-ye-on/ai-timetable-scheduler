package me.timetablescheduler.domain.llm.Service;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.llm.dto.LlmParseRequest;
import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.llm.external.OpenAiClient;
import org.springframework.stereotype.Service;

// LLM 호출
// 응답 검증
// 기본값 보정
// Task 생성에 쓸 수 있는 형태로 정리
@Service
@RequiredArgsConstructor
public class LlmParsingService {

	private final OpenAiClient openAiClient;

	public ParsedTaskResponse parseTask(LlmParseRequest request) {
		ParsedTaskResponse parsed = openAiClient.parseTask(request.message());

		return validateAndNormalize(parsed);
	}

	private ParsedTaskResponse validateAndNormalize(ParsedTaskResponse parsed) {
		int duration = parsed.durationMinutes() == null ? 60 : parsed.durationMinutes();

		return new ParsedTaskResponse(
			parsed.title(),
			parsed.taskType(),
			parsed.preferredDayOfWeek(),
			parsed.preferredTimeLabel(),
			parsed.preferredDate(),
			duration,
			parsed.description()
		);
	}
}
