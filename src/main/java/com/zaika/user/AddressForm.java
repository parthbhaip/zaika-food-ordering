package com.zaika.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Add or edit a saved address. The PIN code is bound without spaces. */
public class AddressForm {

	@NotNull(message = "Choose Home, Work or Other.")
	private AddressLabel label = AddressLabel.HOME;

	@NotBlank(message = "Enter your house, flat or building.")
	@Size(max = 120, message = "Keep this under 120 characters.")
	private String house;

	@NotBlank(message = "Enter the street or area.")
	@Size(max = 160, message = "Keep this under 160 characters.")
	private String street;

	@Size(max = 120, message = "Keep the landmark under 120 characters.")
	private String landmark;

	@NotBlank(message = "Enter the city.")
	@Size(max = 60, message = "Keep the city under 60 characters.")
	private String city = "Kolhapur";

	@NotBlank(message = "Enter the 6-digit PIN code.")
	@Pattern(regexp = Address.PINCODE_PATTERN, message = "Enter a valid 6-digit PIN code, like 416008.")
	private String pincode;

	private boolean makeDefault;

	/** The form pre-filled with a saved address, for editing. */
	static AddressForm of(Address address) {
		AddressForm form = new AddressForm();
		form.label = address.getLabel();
		form.house = address.getHouse();
		form.street = address.getStreet();
		form.landmark = address.getLandmark();
		form.city = address.getCity();
		form.pincode = address.getPincode();
		form.makeDefault = address.isDefaultAddress();
		return form;
	}

	public AddressLabel getLabel() {
		return label;
	}

	public void setLabel(AddressLabel label) {
		this.label = label;
	}

	public String getHouse() {
		return house;
	}

	public void setHouse(String house) {
		this.house = house;
	}

	public String getStreet() {
		return street;
	}

	public void setStreet(String street) {
		this.street = street;
	}

	public String getLandmark() {
		return landmark;
	}

	public void setLandmark(String landmark) {
		this.landmark = landmark;
	}

	public String getCity() {
		return city;
	}

	public void setCity(String city) {
		this.city = city;
	}

	public String getPincode() {
		return pincode;
	}

	public void setPincode(String pincode) {
		this.pincode = pincode == null ? null : pincode.replaceAll("\s", "");
	}

	public boolean isMakeDefault() {
		return makeDefault;
	}

	public void setMakeDefault(boolean makeDefault) {
		this.makeDefault = makeDefault;
	}

}
