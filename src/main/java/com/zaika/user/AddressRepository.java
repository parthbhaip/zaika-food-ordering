package com.zaika.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressRepository extends JpaRepository<Address, Long> {

	/** The address book: default first, then newest first. */
	List<Address> findByUserIdOrderByDefaultAddressDescIdDesc(long userId);

	/** Ownership check in the query itself: another user's id finds nothing. */
	Optional<Address> findByIdAndUserId(long id, long userId);

	long countByUserId(long userId);

}
