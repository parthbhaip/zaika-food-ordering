package com.zaika.cart;

/**
 * A toast for {@code fragments/flash}: the flash attribute after a redirect, or an out-of-band toast in an htmx
 * answer.
 *
 * @param kind success, info or error
 */
public record Toast(String kind, String message) {

	/** flashSuccess, flashInfo or flashError. */
	String flashAttribute() {
		return "flash" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
	}

}
