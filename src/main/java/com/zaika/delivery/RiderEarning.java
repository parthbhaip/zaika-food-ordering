package com.zaika.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;
import com.zaika.order.Order;
import com.zaika.user.User;

/**
 * What a rider earned for one delivered order: the base payout plus their share of the order's delivery fee, and the
 * cash they collected at the door (zero for an order paid online). Written once, when the order is delivered; the
 * amounts are a snapshot, so later changes to the payout settings never rewrite past earnings.
 */
@Entity
@Table(name = "rider_earnings")
public class RiderEarning extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "rider_id", nullable = false, updatable = false)
	private User rider;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false, unique = true)
	private Order order;

	@Column(name = "base_payout", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal basePayout;

	@Column(name = "fee_share", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal feeShare;

	@Column(nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal amount;

	@Column(name = "cash_collected", nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal cashCollected;

	@Column(name = "earned_at", nullable = false, updatable = false)
	private LocalDateTime earnedAt;

	protected RiderEarning() {
	}

	/** {@code amount} = {@code basePayout} + {@code feeShare}; every amount to the paisa. */
	RiderEarning(User rider, Order order, BigDecimal basePayout, BigDecimal feeShare, BigDecimal cashCollected,
			LocalDateTime earnedAt) {
		this.rider = Objects.requireNonNull(rider, "rider");
		this.order = Objects.requireNonNull(order, "order");
		this.basePayout = money(basePayout);
		this.feeShare = money(feeShare);
		this.amount = this.basePayout.add(this.feeShare);
		this.cashCollected = money(cashCollected);
		this.earnedAt = Objects.requireNonNull(earnedAt, "earnedAt");
	}

	private static BigDecimal money(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.HALF_UP);
	}

	public User getRider() {
		return rider;
	}

	public Order getOrder() {
		return order;
	}

	public BigDecimal getBasePayout() {
		return basePayout;
	}

	public BigDecimal getFeeShare() {
		return feeShare;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getCashCollected() {
		return cashCollected;
	}

	public LocalDateTime getEarnedAt() {
		return earnedAt;
	}

}
