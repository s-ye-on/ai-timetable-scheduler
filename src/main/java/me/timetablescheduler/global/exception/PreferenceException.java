package me.timetablescheduler.global.exception;

public class PreferenceException extends ApiException {
	public PreferenceException(ExceptionCode exceptionCode) {
		super(exceptionCode);
	}
}
