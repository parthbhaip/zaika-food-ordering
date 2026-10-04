package com.zaika.user;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import org.springframework.validation.Errors;

/** Change password on /account: the current password, then the new one twice. */
public class PasswordChangeForm {

	@NotEmpty(message = "Enter your current password.")
	private String currentPassword = "";

	@Pattern(regexp = RegistrationForm.PASSWORD_PATTERN, message = RegistrationForm.PASSWORD_RULE)
	private String newPassword = "";

	private String confirmPassword = "";

	/** Cross-field rules: length limit, confirmation, and a new password that differs from the current one. */
	public void checkPasswords(Errors errors) {
		RegistrationForm.checkNewPassword(errors, "newPassword", newPassword, "confirmPassword", confirmPassword);
		if (!newPassword.isEmpty() && newPassword.equals(currentPassword)) {
			errors.rejectValue("newPassword", "same", "Pick a password that is different from your current one.");
		}
	}

	public String getCurrentPassword() {
		return currentPassword;
	}

	public void setCurrentPassword(String currentPassword) {
		this.currentPassword = currentPassword == null ? "" : currentPassword;
	}

	public String getNewPassword() {
		return newPassword;
	}

	public void setNewPassword(String newPassword) {
		this.newPassword = newPassword == null ? "" : newPassword;
	}

	public String getConfirmPassword() {
		return confirmPassword;
	}

	public void setConfirmPassword(String confirmPassword) {
		this.confirmPassword = confirmPassword == null ? "" : confirmPassword;
	}

}
