package com.zaika.api;

import com.zaika.user.Address;
import com.zaika.user.AddressLabel;

/**
 * A saved delivery address.
 *
 * @param defaultAddress checkout picks this one first; exactly one address is the default while the book is not empty
 * @param oneLine "Flat 4B, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008", as the website shows it
 */
public record AddressResponse(long id, AddressLabel label, String house, String street, String landmark, String city,
		String pincode, boolean defaultAddress, String oneLine) {

	static AddressResponse of(Address a) {
		return new AddressResponse(a.getId(), a.getLabel(), a.getHouse(), a.getStreet(), a.getLandmark(), a.getCity(),
				a.getPincode(), a.isDefaultAddress(), a.getOneLine());
	}

}
