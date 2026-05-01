package me.timetablescheduler.domain.llm.dto;

import org.springframework.cglib.core.Local;

import java.time.LocalDate;

public record DateRage(
	LocalDate startDate,
	LocalDate endDate
) {
	public DateRage {
		if (startDate == null || endDate == null) {
			throw new IllegalArgumentException("시작 시간과 종료 시간이 필요합니다");
		}

		if (startDate.isAfter(endDate)) {
			throw new IllegalArgumentException("시작 시간은 종료 시간과 같거나 작아야 합니다");
		}
	}
}
