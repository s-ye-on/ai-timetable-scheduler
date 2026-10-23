package me.timetablescheduler.domain.timetable;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum TimetableColor {
	BLUE, MINT, PEACH, LAVENDER, ROSE, LEMON, LIME, AQUA, APRICOT, SLATE;

	@JsonCreator
	public static TimetableColor fromJson(String value) {
		// 숫자를 enum 순번으로 해석하거나 알 수 없는 색상을 기본색으로 바꾸지 않는다.
		return valueOf(value);
	}
}
