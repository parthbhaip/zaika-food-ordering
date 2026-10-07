package com.zaika.owner;

/**
 * A menu change the rules do not allow, with a message for the owner. {@code field} names the form field it belongs
 * to, or is null for a page-level message.
 */
public class MenuRuleException extends RuntimeException {

	private final String field;

	MenuRuleException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String getField() {
		return field;
	}

}
