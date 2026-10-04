package com.zaika.user;

import java.nio.charset.StandardCharsets;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.validation.Errors;

/** The customer sign-up form. The phone is normalized as it is bound ({@link User#normalizePhone}). */
public class RegistrationForm {

	/** 8+ characters with at least one letter and one digit. */
	public static final String PASSWORD_PATTERN = "(?=.*\\p{L})(?=.*\\d).{8,}";

	public static final String PASSWORD_RULE = "Use 8 or more characters with at least one letter and one digit.";

	/** BCrypt only reads the first 72 bytes, and Spring's encoder rejects longer input. */
	static final int PASSWORD_MAX_BYTES = 72;

	@NotBlank(message = "Tell us your name.")
	@Size(max = 80, message = "Keep your name under 80 characters.")
	private String name;

	@NotBlank(message = "Enter your email address.")
	@Email(message = "Enter a valid email address, like asha@example.com.")
	@Size(max = 254, message = "That email address is too long.")
	private String email;

	@NotBlank(message = "Enter your mobile number.")
	@Pattern(regexp = User.PHONE_PATTERN, message = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.")
	private String phone;

	@Pattern(regexp = PASSWORD_PATTERN, message = PASSWORD_RULE)
	private String password = "";

	private String confirmPassword = "";

	@AssertTrue(message = "Please accept the terms to create your account.")
	private boolean acceptTerms;

	/** Rules across fields: the confirmation matches and the password fits BCrypt's limit. */
	public void checkPasswords(Errors errors) {
		checkNewPassword(errors, "password", password, "confirmPassword", confirmPassword);
	}

	/**
	 * The shared rules for any new password beyond {@link #PASSWORD_PATTERN}: at most 72 bytes, and the
	 * confirmation field typed and equal. Sign-up, password change and reset all use it.
	 */
	public static void checkNewPassword(Errors errors, String passwordField, String password, String confirmField,
			String confirm) {
		if (password.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES) {
			errors.rejectValue(passwordField, "tooLong", "Keep it to 72 characters or fewer.");
		}
		if (confirm.isEmpty()) {
			errors.rejectValue(confirmField, "required", "Type your password again.");
		}
		else if (!confirm.equals(password)) {
			errors.rejectValue(confirmField, "mismatch", "The passwords don't match.");
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.strip();
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = User.normalizePhone(phone);
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

	public boolean isAcceptTerms() {
		return acceptTerms;
	}

	public void setAcceptTerms(boolean acceptTerms) {
		this.acceptTerms = acceptTerms;
	}

}
