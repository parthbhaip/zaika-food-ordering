package com.zaika.order;

import java.util.List;

import org.springframework.validation.Errors;

/**
 * The tracking page's "Cancel order" form: one of {@link #REASONS} (required) and optional details, which become
 * required when the reason is {@link #OTHER}. {@link #fullReason()} is what the order stores.
 */
public class CancelOrderForm {

	public static final String OTHER = "Something else";

	public static final List<String> REASONS = List.of("Ordered by mistake", "Want to change my order",
			"Wrong delivery address", "Restaurant is taking too long", OTHER);

	public static final int MAX_DETAILS = 200;

	private String reason;

	private String details;

	/** Records the problems as field errors on {@code reason} and {@code details}. */
	public void validate(Errors errors) {
		if (reason == null || !REASONS.contains(reason)) {
			errors.rejectValue("reason", "required", "Choose why you are cancelling.");
		}
		if (details != null && details.length() > MAX_DETAILS) {
			errors.rejectValue("details", "size", "Keep it to " + MAX_DETAILS + " characters or fewer.");
		}
		else if (OTHER.equals(reason) && details == null) {
			errors.rejectValue("details", "required", "Tell us why you are cancelling.");
		}
	}

	/** "Ordered by mistake", "Ordered by mistake: wrong dish", or for {@link #OTHER} just the details. */
	public String fullReason() {
		if (details == null) {
			return reason;
		}
		return OTHER.equals(reason) ? details : reason + ": " + details;
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

	public String getDetails() {
		return details;
	}

	/** Stripped; blank becomes null. */
	public void setDetails(String details) {
		this.details = details == null || details.isBlank() ? null : details.strip();
	}

}
