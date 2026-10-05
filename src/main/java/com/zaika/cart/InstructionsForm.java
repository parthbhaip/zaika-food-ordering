package com.zaika.cart;

import jakarta.validation.constraints.Size;

/** The cart page's "Note for the restaurant". */
public class InstructionsForm {

	@Size(max = CartService.MAX_INSTRUCTIONS, message = "Keep the note to 250 characters or fewer.")
	private String instructions;

	public InstructionsForm() {
	}

	InstructionsForm(String instructions) {
		this.instructions = instructions;
	}

	public String getInstructions() {
		return instructions;
	}

	public void setInstructions(String instructions) {
		// browsers send line breaks as CRLF but count them as one character against maxlength
		this.instructions = instructions == null ? null : instructions.replace("\r\n", "\n").strip();
	}

}
