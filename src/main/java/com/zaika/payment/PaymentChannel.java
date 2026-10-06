package com.zaika.payment;

/** How an online payment is made on the payment page: a UPI ID or a card. */
public enum PaymentChannel {

	UPI("UPI"), CARD("Card");

	private final String label;

	PaymentChannel(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

	/** The payment page's tab for this channel ({@code ?tab=upi}). */
	public String getTab() {
		return name().toLowerCase(java.util.Locale.ROOT);
	}

	/** The channel for a {@code ?tab=} value; anything unknown is UPI, the first tab. */
	public static PaymentChannel ofTab(String tab) {
		return "card".equals(tab) ? CARD : UPI;
	}

}
