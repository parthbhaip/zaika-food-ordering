package com.zaika.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Why a restaurant is rejected or suspended, or a rider suspended. They read it in the email and on their panel. */
public class ModerationReasonForm {

	public static final int MAX_REASON = 500;

	@NotBlank(message = "Write a reason. They see it in the email and on their panel.")
	@Size(max = MAX_REASON, message = "Keep the reason under 500 characters.")
	private String reason;

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

}
