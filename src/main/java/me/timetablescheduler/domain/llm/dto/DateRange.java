package me.timetablescheduler.domain.llm.dto;

import java.time.LocalDate;

public record DateRange(
	LocalDate startDate,
	LocalDate endDate
) {
}
