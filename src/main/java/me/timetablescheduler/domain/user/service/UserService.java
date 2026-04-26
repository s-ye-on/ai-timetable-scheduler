package me.timetablescheduler.domain.user.service;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.domain.user.UserRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Transactional
public class UserService {
	// update
	// 현재 세션에 로그인한 유저 본인의 비밀번호 확인 필요 -> CustomUserDetails
	// read는 이미 로그인 되어 있을테니까 현재 세션에 로그인 되어 있는 자신을 받으면 되지 않나?
	// password는 현재 세션에 로그인한 유저 본인의 비밀번호 확인
	// delete
	private final UserRepository userRepository;

}
