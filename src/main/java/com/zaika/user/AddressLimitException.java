package com.zaika.user;

/** The address book is full ({@link AddressService#MAX_ADDRESSES}). */
public class AddressLimitException extends RuntimeException {

	AddressLimitException() {
		super("Address book is full");
	}

}
