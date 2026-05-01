package me.timetablescheduler.domain.llm.external;

import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import org.springframework.stereotype.Component;

@Component
public class OpenAiClient {

	public ParsedTaskResponse parseTask(String message) {
		// 1차 구현에서는 여기서 OpenAI API 호출
		// 지금은 임시 Mock으로 시작
		return new ParsedTaskResponse(
			"밥약속",
			"APPOINTMENT",
			null,
			"LUNCH",
			null,
			60,
			message
		);
	}
}
