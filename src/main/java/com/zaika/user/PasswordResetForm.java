package com.zaika.user;

import jakarta.validation.constraints.Pattern;

import org.springframework.validation.Errors;

/** Choose a new password from a reset link. The token travels in a hidden field. */
public class PasswordResetForm {

	private String token = "";

	@Pattern(regexp = RegistrationForm.PASSWORD_PATTERN, message = RegistrationForm.PASSWORD_RULE)
	private String password = "";

	private String confirmPassword = "";

	/** The same rules as sign-up: 72-byte limit and a matching confirmation. */
	public void checkPasswords(Errors errors) {
		RegistrationForm.checkNewPassword(errors, "password", password, "confirmPassword", confirmPassword);
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token == null ? "" : token;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password == null ? "" : password;
	}

	public String getConfirmPassword() {
		return confirmPassword;
	}

	public void setConfirmPassword(String confirmPassword) {
		this.confirmPassword = confirmPassword == null ? "" : confirmPassword;
	}

}
