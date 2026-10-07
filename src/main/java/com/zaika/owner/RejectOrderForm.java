package com.zaika.owner;

import java.util.List;

import org.springframework.validation.Errors;

/**
 * The live orders board's "Reject" form: one of {@link #REASONS} (required) and optional details, which become
 * required for {@link #OTHER}. {@link #fullReason()} is what the order stores and the customer reads on their order.
 */
public class RejectOrderForm {

	public static final String OTHER = "Something else";

	public static final List<String> REASONS = List.of("Kitchen is too busy right now", "Some dishes are sold out",
			"We're closing soon", OTHER);

	public static final int MAX_DETAILS = 200;

	private String reason;

	private String details;

	/** Records the problems as field errors on {@code reason} and {@code details}. */
	public void validate(Errors errors) {
		if (reason == null || !REASONS.contains(reason)) {
			errors.rejectValue("reason", "required", "Choose a reason. The customer sees it.");
		}
		if (details != null && details.length() > MAX_DETAILS) {
			errors.rejectValue("details", "size", "Keep it to " + MAX_DETAILS + " characters or fewer.");
		}
		else if (OTHER.equals(reason) && details == null) {
			errors.rejectValue("details", "required", "Tell the customer why you can't take this order.");
		}
	}

	/** "Some dishes are sold out", "Some dishes are sold out: no mutton left", or for {@link #OTHER} the details. */
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
