package com.zaika.api;

import com.zaika.user.RegistrationForm;

/**
 * Body of {@code POST /api/v1/auth/register}. Checked with the website's sign-up rules ({@link RegistrationForm}),
 * so both say the same thing; the app asks for the password once.
 */
public record RegisterRequest(String name, String email, String phone, String password, Boolean acceptTerms) {

	RegistrationForm toForm() {
		RegistrationForm form = new RegistrationForm();
		form.setName(name);
		form.setEmail(email);
		form.setPhone(phone);
		form.setPassword(password);
		form.setConfirmPassword(password);
		form.setAcceptTerms(Boolean.TRUE.equals(acceptTerms));
		return form;
	}

}
