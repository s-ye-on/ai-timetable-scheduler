package me.timetablescheduler.global.exception;

public class RecommendationException extends ApiException {
	public RecommendationException(ExceptionCode exceptionCode) {
		super(exceptionCode);
	}
}
