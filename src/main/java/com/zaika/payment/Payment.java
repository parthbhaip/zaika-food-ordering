package com.zaika.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;
import com.zaika.order.Order;

/**
 * One online payment attempt for an order, as the gateway answered it. Failed attempts are kept too. Holds only a
 * masked description of the card or UPI ID, never the number or the CVV. Written once, never changed.
 */
@Entity
@Table(name = "payments")
public class Payment extends BaseEntity {

	public enum Status {
		SUCCEEDED, FAILED
	}

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false)
	private Order order;

	@Column(nullable = false, length = 20, updatable = false)
	private String gateway;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private PaymentChannel channel;

	@Column(nullable = false, precision = 10, scale = 2, updatable = false)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10, updatable = false)
	private Status status;

	@Column(nullable = false, length = 60, updatable = false)
	private String instrument;

	@Column(name = "gateway_reference", length = 40, updatable = false)
	private String gatewayReference;

	@Column(name = "failure_reason", length = 200, updatable = false)
	private String failureReason;

	protected Payment() {
	}

	/** Records {@code charge} for {@code order} as {@code gateway} answered it. */
	Payment(Order order, String gateway, PaymentGateway.Charge charge, PaymentGateway.Result result) {
		this.order = Objects.requireNonNull(order, "order");
		this.gateway = Objects.requireNonNull(gateway, "gateway");
		this.channel = charge.channel();
		this.amount = charge.amount().setScale(2, RoundingMode.HALF_UP);
		this.instrument = charge.instrument();
		this.status = result.succeeded() ? Status.SUCCEEDED : Status.FAILED;
		this.gatewayReference = result.reference();
		this.failureReason = result.failureReason();
	}

	public Order getOrder() {
		return order;
	}

	public String getGateway() {
		return gateway;
	}

	public PaymentChannel getChannel() {
		return channel;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Status getStatus() {
		return status;
	}

	public String getInstrument() {
		return instrument;
	}

	public String getGatewayReference() {
		return gatewayReference;
	}

	public String getFailureReason() {
		return failureReason;
	}

}
