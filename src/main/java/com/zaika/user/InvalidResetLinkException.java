package com.zaika.user;

/** A password reset link that is unknown, already used, expired, or belongs to a switched-off account. */
public class InvalidResetLinkException extends RuntimeException {

	InvalidResetLinkException() {
		super("Password reset link is not usable");
	}

}
