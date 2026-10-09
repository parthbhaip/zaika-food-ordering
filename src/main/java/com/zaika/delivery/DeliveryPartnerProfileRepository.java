package com.zaika.delivery;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryPartnerProfileRepository extends JpaRepository<DeliveryPartnerProfile, Long> {

	/** The rider's profile with their account, for the rider panel. */
	@EntityGraph(attributePaths = "user")
	Optional<DeliveryPartnerProfile> findByUserId(Long userId);

	boolean existsByUserId(Long userId);

}
