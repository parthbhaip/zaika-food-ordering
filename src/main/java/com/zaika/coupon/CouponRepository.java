package com.zaika.coupon;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/** By its stored (normalized) code. */
	Optional<Coupon> findByCode(String code);

	/** With a row lock, so two orders cannot both take the last use of a limited coupon. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findForUpdate(long id);

	/**
	 * Coupons a customer could use at this restaurant now: active, started, not expired, not fully claimed, and
	 * either platform-wide or this restaurant's own (those first), then by code.
	 */
	@Query("""
			select c from Coupon c
			where c.active = true and c.validFrom <= :now and (c.validUntil is null or c.validUntil > :now)
			and (c.restaurant is null or c.restaurant.id = :restaurantId)
			and (c.totalLimit is null or c.totalLimit > (select count(r) from CouponRedemption r where r.coupon = c))
			order by case when c.restaurant is null then 1 else 0 end, c.code""")
	List<Coupon> findOffers(long restaurantId, LocalDateTime now);

	/** The admin list, newest first, with each coupon's restaurant. */
	@EntityGraph(attributePaths = "restaurant")
	Page<Coupon> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

	/** With its restaurant, for the admin's edit page. */
	@EntityGraph(attributePaths = "restaurant")
	Optional<Coupon> findWithRestaurantById(long id);

}
