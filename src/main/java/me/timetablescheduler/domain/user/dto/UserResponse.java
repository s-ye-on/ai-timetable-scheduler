package me.timetablescheduler.domain.user.dto;

public sealed interface UserResponse
	permits UserResponse.Login, UserResponse.Join, UserResponse.Read {
	record Login(
		Long id,
		String name,
		String message
	) implements UserResponse {
		public Login withMessage(String message) {
			return new Login(id, name, message);
		}
	}

	record Join(
		String name,
		String email
	) implements UserResponse {
	}

	record Read(
		String name,
		String email
	) implements UserResponse {
	}
}
