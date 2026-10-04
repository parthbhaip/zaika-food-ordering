package com.zaika.common;

/**
 * Thrown by services when a record does not exist, or exists but belongs to someone else and must stay hidden.
 * Rendered as the branded 404 page by {@link ErrorPageAdvice}. The message is for logs only, never shown.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message);
	}

}
