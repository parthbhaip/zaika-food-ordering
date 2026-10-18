package com.zaika.order;

/** Why an order cannot go back into the cart; the message is shown to the customer. */
public class ReorderException extends RuntimeException {

	public ReorderException(String message) {
		super(message);
	}

}
