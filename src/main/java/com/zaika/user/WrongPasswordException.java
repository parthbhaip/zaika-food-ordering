package com.zaika.user;

/** The "current password" typed to confirm a change does not match the account's password. */
public class WrongPasswordException extends RuntimeException {

	WrongPasswordException() {
		super("Current password does not match");
	}

}
