package com.zaika.delivery;

/** Something a rider asked for that the delivery rules do not allow, with a message for the rider. */
public class DeliveryRuleException extends RuntimeException {

	DeliveryRuleException(String message) {
		super(message);
	}

}
