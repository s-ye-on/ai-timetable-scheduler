package me.timetablescheduler.domain.llm.dto;

import java.time.LocalDate;

public record DateRange(
	LocalDate startDate,
	LocalDate endDate
) {
	public DateRange {
		if (startDate == null || endDate == null) {
			throw new IllegalArgumentException("시작 날짜와 종료 날짜가 필요합니다");
		}

		if (startDate.isAfter(endDate)) {
			throw new IllegalArgumentException("시작 날짜는 종료 날짜보다 늦을 수 없습니다");
		}
	}
}
