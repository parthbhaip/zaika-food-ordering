package com.zaika.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** "Forgot password": the email to send a reset link to. */
public class ForgotPasswordForm {

	@NotBlank(message = "Enter the email you signed up with.")
	@Email(message = "Enter a valid email address, like asha@example.com.")
	@Size(max = 254, message = "That email address is too long.")
	private String email;

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.strip();
	}

}
