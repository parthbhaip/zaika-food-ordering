package com.zaika.coupon;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

	long countByCouponId(long couponId);

	long countByCouponIdAndUserId(long couponId, long userId);

	/** Uses idx_coupon_redemptions_order. */
	long deleteByOrderId(long orderId);

	/** How a coupon has been used: uses, distinct customers and the discount given in all. */
	record Usage(Long couponId, Long uses, Long customers, BigDecimal discount) {
	}

	/** Usage of these coupons (coupons never used are missing). Uses idx_coupon_redemptions_coupon_user. */
	@Query("""
			select new com.zaika.coupon.CouponRedemptionRepository$Usage(r.coupon.id, count(r), count(distinct r.user.id), sum(r.discount))
			from CouponRedemption r where r.coupon.id in :couponIds group by r.coupon.id""")
	List<Usage> usageOf(Collection<Long> couponIds);

}
