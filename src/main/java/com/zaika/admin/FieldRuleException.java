package com.zaika.admin;

/** An admin form value the data does not allow (a code already taken, a name in use); shown on {@code field}. */
class FieldRuleException extends RuntimeException {

	private final String field;

	FieldRuleException(String field, String message) {
		super(message);
		this.field = field;
	}

	String getField() {
		return field;
	}

}
