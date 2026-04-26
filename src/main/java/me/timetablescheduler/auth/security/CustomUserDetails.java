package me.timetablescheduler.auth.security;

import lombok.Getter;
import me.timetablescheduler.domain.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

// Security 전용 객체
// Security 전용 객체와 도메인 객체를 분리
@Getter
public class CustomUserDetails implements UserDetails {
	// 엔티티를 통째로 들고 있으면
	// - Security 객체가 도메인 엔티티에 좀 더 강하게 묶음
	// - 지연 로딩, 예상치 못한 참조, 테스트 시 결합도가 커질 수 있음
	// - 인증 객체가 필요한 최소 정보보다 더 많은걸 품게 됨
	// 그래서 더 엄격하게 가면 엔티티를 통째로 들고있지 않고 필요한 값만 복사해서 보관하는 방식도 사용함
	private final Long id;
	private final String email;
	private final String password;
	private final boolean enabled;
	private final Collection<? extends GrantedAuthority> authorities;

	private CustomUserDetails(
		Long id,
		String email,
		String password,
		boolean enabled,
		Collection<? extends GrantedAuthority> authorities
	) {
		this.id = id;
		this.email = email;
		this.password = password;
		this.enabled = enabled;
		this.authorities = authorities;
	}

	public static CustomUserDetails from(User user) {
		return new CustomUserDetails(
			user.getId(),
			user.getEmail(),
			user.getPassword(),
			true, /// todo : 휴면/탈퇴 정책 생길 시 수정
			createAuthorities(user)
		);
	}

	private static Collection<? extends GrantedAuthority> createAuthorities(User user) {
		return List.of();
	}

	/// todo : 권한 생기면 수정
	// Getter가 만들어주지만, 코드에 구현 의도를 보여주기 위해 오버라이드 만듬
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		// Role.USER , Role.ADMIN -> ROLE_USER, ROLE_ADMIN
//		String roleName = "ROLE_" + user.getRole().name();
//		return List.of(new SimpleGrantedAuthority(roleName));
		// 아직 권한을 만들지 않았음
		return authorities;
	}

	@Override
	public String getPassword() {
		return password; // 이미 BCrypt로 인코딩된 값
	}

	@Override
	public String getUsername() {
		// 로그인 ID로 "이메일"을 쓰기로 하자 로그인할 떄 필요한 ID
		return email;
	}

	@Override
	public boolean isAccountNonExpired() {
		return true; // 필요하면 Status 기반으로 제어 가능
	}

	@Override
	public boolean isAccountNonLocked() {
		return true; // 추후 잠금 기능 넣고 싶으면 여기서 제어
	}

	@Override
	public boolean isCredentialsNonExpired() {
		return true; // 비밀번호 만료 정책 사용 시 수정
	}

	/// todo : 탈퇴 정책 생기면 마저 구현
	@Override
	public boolean isEnabled() {
		// 탈퇴(비활성화) 된 유저는 로그인 못하게 하려면 여기서 체크
		return enabled;
	}
}
