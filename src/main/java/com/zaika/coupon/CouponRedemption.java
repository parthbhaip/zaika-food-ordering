package com.zaika.coupon;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;
import com.zaika.order.Order;
import com.zaika.user.User;

/** One use of a coupon: written when an order is placed with it. The coupon's limits count these rows. */
@Entity
@Table(name = "coupon_redemptions")
public class CouponRedemption extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "coupon_id", nullable = false, updatable = false)
	private Coupon coupon;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Column(nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal discount;

	/** The order placed with this use; set right after the order is saved, in the same transaction. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "order_id")
	private Order order;

	protected CouponRedemption() {
	}

	CouponRedemption(Coupon coupon, User user, BigDecimal discount) {
		this.coupon = Objects.requireNonNull(coupon, "coupon");
		this.user = Objects.requireNonNull(user, "user");
		this.discount = discount.setScale(2, RoundingMode.HALF_UP);
	}

	void setOrder(Order order) {
		this.order = Objects.requireNonNull(order, "order");
	}

	public Order getOrder() {
		return order;
	}

	public Coupon getCoupon() {
		return coupon;
	}

	public User getUser() {
		return user;
	}

	public BigDecimal getDiscount() {
		return discount;
	}

}
