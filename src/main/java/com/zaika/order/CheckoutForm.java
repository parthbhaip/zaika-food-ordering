package com.zaika.order;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.zaika.cart.CartService;
import com.zaika.user.AddressForm;

/**
 * The checkout form. {@code addressId} is a saved address of the customer, or {@link #NEW_ADDRESS} to deliver to the
 * address typed into {@code newAddress} (saved to the address book with the order; only validated when chosen).
 * {@code idempotencyKey} is a random UUID made when the page was rendered; {@code expectedTotal} is the total the
 * customer saw, so an order is never placed for a different amount than the page showed.
 */
public class CheckoutForm {

	/** {@code addressId} for "deliver to a new address". */
	public static final long NEW_ADDRESS = 0;

	/** A UUID, as {@code UUID.toString()} writes it. */
	public static final String KEY_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@NotNull(message = "Choose where we should deliver.")
	private Long addressId;

	private AddressForm newAddress = new AddressForm();

	@Size(max = CartService.MAX_INSTRUCTIONS, message = "Keep the note for the rider to 250 characters or fewer.")
	private String deliveryNote;

	@NotNull(message = "Choose how you want to pay.")
	private PaymentMethod paymentMethod = PaymentMethod.COD;

	private String idempotencyKey;

	private BigDecimal expectedTotal;

	public boolean wantsNewAddress() {
		return addressId != null && addressId == NEW_ADDRESS;
	}

	public boolean hasValidKey() {
		return idempotencyKey != null && idempotencyKey.matches(KEY_PATTERN);
	}

	public Long getAddressId() {
		return addressId;
	}

	public void setAddressId(Long addressId) {
		this.addressId = addressId;
	}

	public AddressForm getNewAddress() {
		return newAddress;
	}

	public void setNewAddress(AddressForm newAddress) {
		this.newAddress = newAddress;
	}

	public String getDeliveryNote() {
		return deliveryNote;
	}

	public void setDeliveryNote(String deliveryNote) {
		this.deliveryNote = deliveryNote == null || deliveryNote.isBlank() ? null : deliveryNote.strip();
	}

	public PaymentMethod getPaymentMethod() {
		return paymentMethod;
	}

	public void setPaymentMethod(PaymentMethod paymentMethod) {
		this.paymentMethod = paymentMethod;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void setIdempotencyKey(String idempotencyKey) {
		this.idempotencyKey = idempotencyKey;
	}

	public BigDecimal getExpectedTotal() {
		return expectedTotal;
	}

	public void setExpectedTotal(BigDecimal expectedTotal) {
		this.expectedTotal = expectedTotal;
	}

}
