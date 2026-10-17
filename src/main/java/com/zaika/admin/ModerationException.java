package com.zaika.admin;

/** An admin decision that does not fit the current state (already approved, your own account...); the message is for the admin. */
public class ModerationException extends RuntimeException {

	ModerationException(String message) {
		super(message);
	}

}
