package me.timetablescheduler.domain.user.reader;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserReader {
	private final UserRepository userRepository;

	public User read(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}
}
