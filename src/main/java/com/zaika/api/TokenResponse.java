package com.zaika.api;

import java.time.Instant;

import com.zaika.security.ApiAuthService;

/**
 * A fresh access token: send it as {@code Authorization: Bearer <accessToken>}. There is no refresh token; sign in
 * again once it expires ({@code expiresIn} seconds from now).
 */
public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt, MeResponse user) {

	static TokenResponse of(ApiAuthService.SignedIn signedIn) {
		return new TokenResponse(signedIn.accessToken(), "Bearer", ApiAuthService.TOKEN_LIFETIME.toSeconds(),
				signedIn.expiresAt(), MeResponse.of(signedIn.user()));
	}

}
