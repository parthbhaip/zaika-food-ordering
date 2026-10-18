package com.zaika.review;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A restaurant owner's one public reply to a review. */
public class ReplyForm {

	@NotBlank(message = "Write your reply.")
	@Size(max = Review.MAX_TEXT, message = "Keep your reply under 500 characters.")
	private String reply;

	public String getReply() {
		return reply;
	}

	public void setReply(String reply) {
		this.reply = reply;
	}

}
