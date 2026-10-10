package com.zaika.delivery;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface DeliveryPartnerProfileRepository extends JpaRepository<DeliveryPartnerProfile, Long> {

	/** The rider's profile with their account, for the rider panel. */
	@EntityGraph(attributePaths = "user")
	Optional<DeliveryPartnerProfile> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

	/** The rider's profile with a row lock: one rider's claims (two tabs, a double tap) run one after the other. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from DeliveryPartnerProfile p where p.user.id = :userId")
	Optional<DeliveryPartnerProfile> findForUpdateByUserId(long userId);

}
