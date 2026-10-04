package com.zaika.user;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;

/**
 * The address book. Every method takes the signed-in user's id and only ever touches that user's rows: an id that
 * belongs to someone else is "not found", never "forbidden", so ids cannot be probed. Exactly one address is the
 * default whenever the book is not empty.
 */
@Service
@Transactional
public class AddressService {

	public static final int MAX_ADDRESSES = 10;

	private final AddressRepository addresses;

	private final UserRepository users;

	AddressService(AddressRepository addresses, UserRepository users) {
		this.addresses = addresses;
		this.users = users;
	}

	/** Default first, then newest first. */
	@Transactional(readOnly = true)
	public List<Address> list(long userId) {
		return addresses.findByUserIdOrderByDefaultAddressDescIdDesc(userId);
	}

	/** @throws NotFoundException if the address does not exist or is not this user's */
	@Transactional(readOnly = true)
	public Address get(long userId, long addressId) {
		return addresses.findByIdAndUserId(addressId, userId)
			.orElseThrow(() -> new NotFoundException("Address " + addressId + " of user " + userId));
	}

	@Transactional(readOnly = true)
	public boolean isFull(long userId) {
		return addresses.countByUserId(userId) >= MAX_ADDRESSES;
	}

	/**
	 * Saves a new address. The first one, or one marked "make default", becomes the default.
	 *
	 * @throws AddressLimitException if the user already has {@link #MAX_ADDRESSES}
	 */
	public Address create(long userId, AddressForm form) {
		// lock the user row so two parallel "Save" clicks cannot both pass the limit check
		User user = users.findForUpdate(userId).orElseThrow(() -> new NotFoundException("User " + userId));
		List<Address> book = list(userId);
		if (book.size() >= MAX_ADDRESSES) {
			throw new AddressLimitException();
		}
		Address address = new Address(user);
		address.apply(form);
		addresses.save(address);
		if (form.isMakeDefault() || book.isEmpty()) {
			makeOnlyDefault(book, address);
		}
		return address;
	}

	/** @throws NotFoundException if the address does not exist or is not this user's */
	public Address update(long userId, long addressId, AddressForm form) {
		Address address = get(userId, addressId);
		address.apply(form);
		if (form.isMakeDefault()) {
			makeOnlyDefault(list(userId), address);
		}
		return address;
	}

	/** @throws NotFoundException if the address does not exist or is not this user's */
	public void makeDefault(long userId, long addressId) {
		makeOnlyDefault(list(userId), get(userId, addressId));
	}

	/**
	 * Deletes the address. If it was the default, the newest remaining address takes over.
	 *
	 * @throws NotFoundException if the address does not exist or is not this user's
	 */
	public void delete(long userId, long addressId) {
		Address address = get(userId, addressId);
		addresses.delete(address);
		if (address.isDefaultAddress()) {
			list(userId).stream()
				.filter(other -> !other.equals(address))
				.findFirst()
				.ifPresent(next -> next.setDefaultAddress(true));
		}
	}

	private static void makeOnlyDefault(List<Address> book, Address chosen) {
		book.forEach(other -> other.setDefaultAddress(false));
		chosen.setDefaultAddress(true);
	}

}
