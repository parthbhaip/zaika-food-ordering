package com.zaika.api;

import com.zaika.user.User;

/** The signed-in account, as {@code GET /api/v1/me} and the sign-in answers show it. Never the password hash. */
public record MeResponse(long id, String name, String email, String phone, String role) {

	static MeResponse of(User user) {
		return new MeResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getRole().name());
	}

}
