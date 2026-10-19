package com.zaika.api;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/auth/login}. */
public record LoginRequest(@NotBlank(message = "Enter your email address.") String email,
		@NotBlank(message = "Enter your password.") String password) {
}
