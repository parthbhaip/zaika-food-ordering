package com.zaika.payment;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * The test-mode gateway: no money moves and no bank is contacted. Every attempt succeeds with a random
 * {@code MOCK-...} reference, unless the payment page asked it to simulate a decline.
 */
@Component
class MockPaymentGateway implements PaymentGateway {

	static final String NAME = "MOCK";

	static final String UPI_DECLINED = "The UPI request was declined. No money left your account.";

	static final String CARD_DECLINED = "Your bank declined the card. No money left your account.";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Result charge(Charge charge) {
		if (charge.simulateFailure()) {
			return Result.declined(charge.channel() == PaymentChannel.UPI ? UPI_DECLINED : CARD_DECLINED);
		}
		byte[] id = new byte[8];
		RANDOM.nextBytes(id);
		return Result.success("MOCK-" + HexFormat.of().withUpperCase().formatHex(id));
	}

}
