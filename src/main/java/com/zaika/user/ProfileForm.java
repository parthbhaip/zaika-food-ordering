package com.zaika.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Name and mobile number on /account. The email is the sign-in name and is not edited here. */
public class ProfileForm {

	@NotBlank(message = "Tell us your name.")
	@Size(max = 80, message = "Keep your name under 80 characters.")
	private String name;

	@NotBlank(message = "Enter your mobile number.")
	@Pattern(regexp = User.PHONE_PATTERN, message = "Enter a 10-digit mobile number starting with 6, 7, 8 or 9.")
	private String phone;

	static ProfileForm of(User user) {
		ProfileForm form = new ProfileForm();
		form.name = user.getName();
		form.phone = user.getPhone();
		return form;
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

}
