package com.zaika.owner;

/**
 * A menu or profile change the rules do not allow, with a message for the owner. {@code field} names the form field
 * it belongs to, or is null for a page-level message.
 */
public class OwnerRuleException extends RuntimeException {

	private final String field;

	OwnerRuleException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
