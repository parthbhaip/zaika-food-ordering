package com.zaika.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.zaika.order.Order;

/** Why the admin cancels an order. The customer reads it on their order page. */
public class AdminCancelForm {

	@NotBlank(message = "Write a reason. The customer sees it on their order.")
	@Size(max = Order.MAX_REASON, message = "Keep the reason under 500 characters.")
	private String reason;

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

}
