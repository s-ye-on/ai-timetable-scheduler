package me.timetablescheduler.domain.user.reader;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.stereotype.Component;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserReader {
	private final UserRepository userRepository;

	public User read(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public User readForUpdate(Long userId) {
		try {
			return userRepository.findByIdForUpdate(userId)
				.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
		} catch (PessimisticLockingFailureException e) {
			throw new UserException(ExceptionCode.CONFLICT_USER_OPERATION);
		}
	}
}
