package me.timetablescheduler.global.exception;

public class TimetableSlotException extends ApiException {
	public TimetableSlotException(ExceptionCode code) {
		super(code);
	}
	public TimetableSlotException(ExceptionCode code, String message) {
		super(code, message);
	}
}
