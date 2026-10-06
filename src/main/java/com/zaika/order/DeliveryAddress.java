package com.zaika.order;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import com.zaika.user.Address;
import com.zaika.user.AddressLabel;

/**
 * Where an order goes, copied from the customer's address book at checkout so later edits or deletions of the saved
 * address never move a placed order.
 */
@Embeddable
public record DeliveryAddress(
		@Enumerated(EnumType.STRING) @Column(name = "address_label", nullable = false, length = 10) AddressLabel label,
		@Column(name = "address_house", nullable = false, length = 120) String house,
		@Column(name = "address_street", nullable = false, length = 160) String street,
		@Column(name = "address_landmark", length = 120) String landmark,
		@Column(name = "address_city", nullable = false, length = 60) String city,
		@Column(name = "address_pincode", nullable = false, length = 6) String pincode) {

	public DeliveryAddress {
		Objects.requireNonNull(label, "label");
		Objects.requireNonNull(house, "house");
		Objects.requireNonNull(street, "street");
		Objects.requireNonNull(city, "city");
		Objects.requireNonNull(pincode, "pincode");
	}

	public static DeliveryAddress of(Address address) {
		return new DeliveryAddress(address.getLabel(), address.getHouse(), address.getStreet(), address.getLandmark(),
				address.getCity(), address.getPincode());
	}

	/** "Flat 4B, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008", same as the address book shows. */
	public String oneLine() {
		return Address.oneLine(house, street, landmark, city, pincode);
	}

}
