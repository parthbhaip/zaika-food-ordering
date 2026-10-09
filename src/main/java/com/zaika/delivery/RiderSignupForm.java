package com.zaika.delivery;

import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.validation.Errors;

import com.zaika.user.RegistrationForm;
import com.zaika.user.User;

/**
 * "Become a delivery partner": the rider's account (name, mobile, email, password) and their vehicle and preferred
 * area in one form. The phone and the number plate are bound in their stored form.
 */
public class RiderSignupForm {

	/** The Kolhapur areas riders can pick as their base: where our restaurants are. */
	public static final List<String> AREAS = List.of("Kadamwadi", "Kasaba Bawada", "Kawala Naka", "Laxmipuri",
			"Mahadwar Road", "Nagala Park", "Rajarampuri", "Rankala", "Ruikar Colony", "Sambhaji Nagar", "Shahupuri",
			"Shivaji Peth", "Tarabai Park");

	@NotBlank(message = "Tell us your name.")
	@Size(max = 80, message = "Keep your name under 80 characters.")
	private String name;

	@NotBlank(message = "Enter your mobile number.")
	@Pattern(regexp = User.PHONE_PATTERN, message = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.")
	private String phone;

	@NotBlank(message = "Enter your email address.")
	@Email(message = "Enter a valid email address, like asha@example.com.")
	@Size(max = 254, message = "That email address is too long.")
	private String email;

	@Pattern(regexp = RegistrationForm.PASSWORD_PATTERN, message = RegistrationForm.PASSWORD_RULE)
	private String password = "";

	private String confirmPassword = "";

	@NotNull(message = "Choose the vehicle you'll ride.")
	private VehicleType vehicleType;

	private String vehicleNumber;

	@NotBlank(message = "Choose the area you'd like to ride in.")
	private String preferredArea;

	@AssertTrue(message = "Please confirm and accept the partner terms to sign up.")
	private boolean acceptTerms;

	/** Rules across fields: password confirmation, a number plate for motor vehicles, an area from the list. */
	public void check(Errors errors) {
		RegistrationForm.checkNewPassword(errors, "password", password, "confirmPassword", confirmPassword);
		if (vehicleType != null && vehicleType.hasNumberPlate()) {
			if (vehicleNumber == null) {
				errors.rejectValue("vehicleNumber", "required", "Enter your vehicle's number plate.");
			}
			else if (!vehicleNumber.matches(DeliveryPartnerProfile.NUMBER_PLATE_PATTERN)) {
				errors.rejectValue("vehicleNumber", "invalid",
						"Enter the number as it is on your plate, like MH 09 AB 1234.");
			}
		}
		if (!errors.hasFieldErrors("preferredArea") && !AREAS.contains(preferredArea)) {
			errors.rejectValue("preferredArea", "unknown", "Choose an area from the list.");
		}
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getPhone() {
		return phone;
	}

	public void setPhone(String phone) {
		this.phone = User.normalizePhone(phone);
	}

	public String getEmail() {
		return email;
	}

	public void setEmail(String email) {
		this.email = email == null ? null : email.strip();
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

	public VehicleType getVehicleType() {
		return vehicleType;
	}

	public void setVehicleType(VehicleType vehicleType) {
		this.vehicleType = vehicleType;
	}

	public String getVehicleNumber() {
		return vehicleNumber;
	}

	public void setVehicleNumber(String vehicleNumber) {
		this.vehicleNumber = DeliveryPartnerProfile.normalizeNumberPlate(vehicleNumber);
	}

	public String getPreferredArea() {
		return preferredArea;
	}

	public void setPreferredArea(String preferredArea) {
		this.preferredArea = preferredArea;
	}

	public boolean isAcceptTerms() {
		return acceptTerms;
	}

	public void setAcceptTerms(boolean acceptTerms) {
		this.acceptTerms = acceptTerms;
	}

}
