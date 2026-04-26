package me.timetablescheduler.auth;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import me.timetablescheduler.global.exception.AuthException;
import me.timetablescheduler.global.exception.ExceptionCode;
import me.timetablescheduler.global.exception.UserException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CurrentUserService {

	private final UserRepository userRepository;

	public User getCurrentUser() {
		String email = getCurrentUserEmail();
		return userRepository.findByEmail(email)
			.orElseThrow(() -> new UserException(ExceptionCode.NOT_FOUND_USER));
	}

	public String getCurrentUserEmail() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication.getName() == null || "anonymousUser".equals(authentication.getName())) {
			throw new AuthException(ExceptionCode.INVALID_CREDENTIALS);
		}
		return authentication.getName();
	}
}
