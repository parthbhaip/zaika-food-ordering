package com.zaika.owner;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.validation.Errors;

import com.zaika.user.RegistrationForm;
import com.zaika.user.User;

/**
 * "Register your restaurant": the owner's account plus the restaurant's details, cuisines, logo, hours and fees
 * ({@link RestaurantProfileForm}; sign-up has no cover photo) in one form. Phones are bound in their stored form.
 */
public class RestaurantSignupForm extends RestaurantProfileForm {

	// ----------------------------------------------------------------------- the owner's account

	@NotBlank(message = "Tell us your name.")
	@Size(max = 80, message = "Keep your name under 80 characters.")
	private String ownerName;

	@NotBlank(message = "Enter your email address.")
	@Email(message = "Enter a valid email address, like asha@example.com.")
	@Size(max = 254, message = "That email address is too long.")
	private String email;

	@NotBlank(message = "Enter your mobile number.")
	@Pattern(regexp = User.PHONE_PATTERN, message = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.")
	private String ownerPhone;

	@Pattern(regexp = RegistrationForm.PASSWORD_PATTERN, message = RegistrationForm.PASSWORD_RULE)
	private String password = "";

	private String confirmPassword = "";

	@AssertTrue(message = "Please accept the partner terms to register.")
	private boolean acceptTerms;

	/** Rules across fields: the password confirmation and BCrypt's length limit, a known prep time. */
	public void check(Errors errors) {
		RegistrationForm.checkNewPassword(errors, "password", password, "confirmPassword", confirmPassword);
		if (!errors.hasFieldErrors("avgPrepMinutes") && !PREP_MINUTES.contains(getAvgPrepMinutes())) {
			errors.rejectValue("avgPrepMinutes", "unknown", "Choose a preparation time.");
		}
	}

	public String getOwnerName() {
		return ownerName;
	}

	public void setOwnerName(String ownerName) {
		this.ownerName = ownerName;
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.strip();
	}

	public String getOwnerPhone() {
		return ownerPhone;
	}

	public void setOwnerPhone(String ownerPhone) {
		this.ownerPhone = User.normalizePhone(ownerPhone);
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
