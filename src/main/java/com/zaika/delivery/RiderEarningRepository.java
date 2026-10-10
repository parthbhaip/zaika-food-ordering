package com.zaika.delivery;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {

	/** The earning for one order (uk_rider_earnings_order). */
	Optional<RiderEarning> findByOrderId(long orderId);

}
