package me.timetablescheduler.auth.security;

import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.user.User;
import me.timetablescheduler.domain.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

// Spring Security가 "username으로 인증용 사용자 정보를 읽을 때" 쓰는 어댑터
// 인증 프레임워크용
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	// username email 사용(로그인 ID를 말하는 것임)
	@Override
	public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
		// username에는 /login 폼에서 입력한 값이 들어옴
		User user = userRepository.findByEmail(username)
			.orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

		return CustomUserDetails.from(user);
	}
}
