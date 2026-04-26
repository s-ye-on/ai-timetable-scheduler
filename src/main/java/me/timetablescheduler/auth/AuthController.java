package me.timetablescheduler.auth;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import me.timetablescheduler.auth.dto.AuthRequest;
import me.timetablescheduler.auth.dto.AuthResponse;
import me.timetablescheduler.auth.dto.UserProfileResponse;
import me.timetablescheduler.auth.security.CustomUserDetails;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
	private final AuthService authService;

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	public AuthResponse register(@Valid @RequestBody AuthRequest.Register request) {
		return authService.register(request);
	}

	@PostMapping("/login")
	public AuthResponse login(@Valid @RequestBody AuthRequest.Login request) {
		return authService.login(request);
	}

	@PostMapping("/refresh")
	public AuthResponse refresh(@Valid @RequestBody AuthRequest.TokenRefresh request) {
		return authService.refresh(request);
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void logout(@AuthenticationPrincipal CustomUserDetails userDetails) {
		authService.logout(userDetails.getUsername());
	}

	@GetMapping("/me")
	public UserProfileResponse me(@AuthenticationPrincipal CustomUserDetails userDetails) {
		return authService.getCurrentUser(userDetails.getUsername());
	}
}
