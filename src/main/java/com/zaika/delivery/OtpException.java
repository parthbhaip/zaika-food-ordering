package com.zaika.delivery;

/**
 * The delivery code the rider typed was refused (badly formed, wrong, or locked after too many wrong tries), with a
 * message for the code field. A wrong try is still counted: the transaction commits despite this exception.
 */
public class OtpException extends DeliveryRuleException {

	OtpException(String message) {
		super(message);
	}

}
