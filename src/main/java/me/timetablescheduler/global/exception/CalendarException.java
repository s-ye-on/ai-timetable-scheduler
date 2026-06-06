package me.timetablescheduler.global.exception;

public class CalendarException extends ApiException {
	public CalendarException(ExceptionCode exceptionCode) {
		super(exceptionCode);
	}
}
