package me.timetablescheduler.global.exception;

public class AuthException extends ApiException {
	public AuthException(ExceptionCode code) {
		super(code);
	}

	public AuthException(ExceptionCode code, String message) {
		super(code, message);
	}
}
