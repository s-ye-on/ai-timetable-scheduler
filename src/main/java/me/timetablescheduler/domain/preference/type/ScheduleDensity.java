package me.timetablescheduler.domain.preference.type;

public enum ScheduleDensity {
	COMPACT, // 촘촘히 사용, 기존 일정과 가까운 후보 선호
	BALANCED, // 적당히 균형, 기존 일정과 적당한 간격 후보 선호
	RELAXED // 여유 있게 배치, 기존 일정과 충분히 떨어진 후보 선호
}
