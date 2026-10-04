package com.zaika.user;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;

/** A saved delivery address in a customer's address book. Only {@link AddressService} changes it. */
@Entity
@Table(name = "addresses")
public class Address extends BaseEntity {

	/** Indian PIN code: 6 digits, never starting with 0. */
	public static final String PINCODE_PATTERN = "[1-9][0-9]{5}";

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private AddressLabel label;

	@Column(nullable = false, length = 120)
	private String house;

	@Column(nullable = false, length = 160)
	private String street;

	@Column(length = 120)
	private String landmark;

	@Column(nullable = false, length = 60)
	private String city;

	@Column(nullable = false, length = 6)
	private String pincode;

	@Column(name = "is_default", nullable = false)
	private boolean defaultAddress;

	protected Address() {
	}

	Address(User user) {
		this.user = Objects.requireNonNull(user, "user");
	}

	/** Copies the (validated) form fields; blank optional fields are stored as null. */
	void apply(AddressForm form) {
		this.label = form.getLabel();
		this.house = form.getHouse().strip();
		this.street = form.getStreet().strip();
		this.landmark = form.getLandmark() == null || form.getLandmark().isBlank() ? null : form.getLandmark().strip();
		this.city = form.getCity().strip();
		this.pincode = form.getPincode();
	}

	/** One line for lists and checkout: "Flat 4B, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008". */
	public String getOneLine() {
		return Stream.of(house, street, landmark == null ? null : "near " + landmark, city + " " + pincode)
			.filter(Objects::nonNull)
			.collect(Collectors.joining(", "));
	}

	public AddressLabel getLabel() {
		return label;
	}

	public String getHouse() {
		return house;
	}

	public String getStreet() {
		return street;
	}

	public String getLandmark() {
		return landmark;
	}

	public String getCity() {
		return city;
	}

	public String getPincode() {
		return pincode;
	}

	public boolean isDefaultAddress() {
		return defaultAddress;
	}

	void setDefaultAddress(boolean defaultAddress) {
		this.defaultAddress = defaultAddress;
	}

}
