package com.zaika.security;

import java.time.LocalDateTime;

/** API sign-in refused: wrong email or password, locked after too many wrong passwords, or a switched-off account. */
public class ApiSignInException extends RuntimeException {

	public enum Reason {
		WRONG_PASSWORD, LOCKED, DISABLED
	}

	private final Reason reason;

	private final LocalDateTime lockedUntil;

	ApiSignInException(Reason reason, LocalDateTime lockedUntil) {
		super("API sign-in refused: " + reason);
		this.reason = reason;
		this.lockedUntil = lockedUntil;
	}

	public Reason getReason() {
		return reason;
	}

	/** When password sign-in unlocks ({@link Reason#LOCKED} only), Asia/Kolkata. */
	public LocalDateTime getLockedUntil() {
		return lockedUntil;
	}

}
