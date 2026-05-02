package me.timetablescheduler.domain.llm.external;

import me.timetablescheduler.domain.llm.dto.ParsedTaskResponse;
import me.timetablescheduler.domain.recommendation.type.PreferredTimeRange;
import me.timetablescheduler.domain.task.type.TaskCategory;
import me.timetablescheduler.domain.task.type.TaskPriority;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;

@Component
public class OpenAiClient {

	public ParsedTaskResponse parseTask(String message) {
		// 1차 구현에서는 여기서 OpenAI API 호출
		// 지금은 임시 Mock으로 시작
		return new ParsedTaskResponse(
			"밥약속",
			TaskCategory.APPOINTMENT,
			60,
			null,
			DayOfWeek.TUESDAY,
			null,
			PreferredTimeRange.LUNCH,
			null,
			TaskPriority.NORMAL,
			message
		);
	}
}
