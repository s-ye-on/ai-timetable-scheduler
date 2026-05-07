package me.timetablescheduler.global.exception;

public class TaskException extends ApiException {
	public TaskException(ExceptionCode code) {
		super(code);
	}

	public TaskException(ExceptionCode code, String message) {
		super(code, message);
	}
}
