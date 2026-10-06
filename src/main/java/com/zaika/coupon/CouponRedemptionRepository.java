package com.zaika.coupon;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

	long countByCouponId(long couponId);

	long countByCouponIdAndUserId(long couponId, long userId);

	/** Uses idx_coupon_redemptions_order. */
	long deleteByOrderId(long orderId);

}
