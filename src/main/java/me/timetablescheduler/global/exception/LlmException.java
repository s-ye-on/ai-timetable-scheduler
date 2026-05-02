package me.timetablescheduler.global.exception;

public class LlmException extends ApiException {
	public LlmException(ExceptionCode exceptionCode, String message) {
		super(exceptionCode, message);
	}

	public LlmException(ExceptionCode code) {
		super(code);
	}
}
