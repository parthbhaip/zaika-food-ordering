package com.zaika.common;

/**
 * Thrown by services when the signed-in user may not act on a record they are allowed to know about.
 * Rendered as the branded 403 page by {@link ErrorPageAdvice}. The message is for logs only, never shown.
 */
public class ForbiddenException extends RuntimeException {

	public ForbiddenException(String message) {
		super(message);
	}

}
