package me.timetablescheduler.domain.llm.dto;

/** 자연어 추출용 구분이다. Task에는 계산된 날짜와 시각만 저장한다. */
public enum RelativeStartMode {
    EXACT,
    NOT_BEFORE
}
