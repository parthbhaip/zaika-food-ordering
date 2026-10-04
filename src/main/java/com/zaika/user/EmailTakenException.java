package com.zaika.user;

/** Sign-up with an email that already has an account (any role, any letter case). */
public class EmailTakenException extends RuntimeException {

	public EmailTakenException() {
		super("Email already registered");
	}

}
