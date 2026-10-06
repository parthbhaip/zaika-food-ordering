package com.zaika.order;

/** How the customer pays: cash to the rider, or online (the mock gateway) before the restaurant sees the order. */
public enum PaymentMethod {

	COD("Cash on delivery"), ONLINE("Paid online");

	private final String label;

	PaymentMethod(String label) {
		this.label = label;
	}

	public String getLabel() {
		return label;
	}

}
