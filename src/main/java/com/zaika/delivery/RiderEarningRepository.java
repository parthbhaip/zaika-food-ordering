package com.zaika.delivery;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RiderEarningRepository extends JpaRepository<RiderEarning, Long> {

	/** The earning for one order (uk_rider_earnings_order). */
	Optional<RiderEarning> findByOrderId(long orderId);

	/** The rider's earnings since {@code since}, amounts only (idx_rider_earnings_rider_earned). */
	@Query("""
			select new com.zaika.delivery.RiderEarningRepository$Earned(e.earnedAt, e.amount, e.cashCollected)
			from RiderEarning e where e.rider.id = :riderId and e.earnedAt >= :since""")
	List<Earned> findEarnedSince(long riderId, LocalDateTime since);

	/** A page of the rider's earnings, newest first, with each order and its restaurant. */
	@EntityGraph(attributePaths = { "order", "order.restaurant" })
	Page<RiderEarning> findByRiderIdOrderByEarnedAtDescIdDesc(long riderId, Pageable pageable);

	/** One delivery's amounts, for the period totals. */
	record Earned(LocalDateTime earnedAt, BigDecimal amount, BigDecimal cashCollected) {
	}

}
