package com.zaika.payment;

import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.validation.Errors;

/**
 * The payment page's form: a UPI ID, or card number, expiry, CVV and name, checked for format only (the test-mode
 * gateway never sees them). {@code simulateFailure} is set by the page's "Simulate failure" button. Only the fields of
 * the chosen {@code channel} are checked. Nothing here is stored except {@link #maskedInstrument()}; the page never
 * echoes the CVV back.
 */
public class PaymentForm {

	/** name@handle: what UPI IDs look like. */
	static final Pattern UPI_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{1,63}@[A-Za-z][A-Za-z0-9]{1,31}");

	/** MM/YY, MM / YY or MMYY. */
	static final Pattern EXPIRY = Pattern.compile("(0[1-9]|1[0-2])\\s*/?\\s*([0-9]{2})");

	static final Pattern CVV = Pattern.compile("[0-9]{3,4}");

	static final int MAX_NAME = 60;

	private PaymentChannel channel = PaymentChannel.UPI;

	private String upiId;

	private String cardNumber;

	private String cardExpiry;

	private String cardCvv;

	private String cardName;

	private boolean simulateFailure;

	/** Checks the chosen channel's fields; {@code thisMonth} (from the app clock) decides whether a card has expired. */
	public void validate(Errors errors, YearMonth thisMonth) {
		if (channel == PaymentChannel.CARD) {
			validateCard(errors, thisMonth);
			return;
		}
		if (upiId == null) {
			errors.rejectValue("upiId", "required", "Enter your UPI ID.");
		}
		else if (!UPI_ID.matcher(upiId).matches()) {
			errors.rejectValue("upiId", "format", "Enter a UPI ID like name@bank.");
		}
	}

	private void validateCard(Errors errors, YearMonth thisMonth) {
		String digits = cardDigits();
		if (digits == null) {
			errors.rejectValue("cardNumber", "required", "Enter the card number.");
		}
		else if (!digits.matches("[0-9]{12,19}") || !luhn(digits)) {
			errors.rejectValue("cardNumber", "format", "That card number does not look right. Check the digits.");
		}

		Matcher expiry = cardExpiry == null ? null : EXPIRY.matcher(cardExpiry);
		if (cardExpiry == null) {
			errors.rejectValue("cardExpiry", "required", "Enter the expiry date.");
		}
		else if (!expiry.matches()) {
			errors.rejectValue("cardExpiry", "format", "Enter the expiry date as MM/YY.");
		}
		else if (YearMonth.of(2000 + Integer.parseInt(expiry.group(2)), Integer.parseInt(expiry.group(1)))
			.isBefore(thisMonth)) {
			errors.rejectValue("cardExpiry", "expired", "This card has expired. Use another card or pay by UPI.");
		}

		if (cardCvv == null) {
			errors.rejectValue("cardCvv", "required", "Enter the CVV.");
		}
		else if (!CVV.matcher(cardCvv).matches()) {
			errors.rejectValue("cardCvv", "format", "The CVV is the 3 or 4 digits on the back of the card.");
		}

		if (cardName == null) {
			errors.rejectValue("cardName", "required", "Enter the name on the card.");
		}
		else if (cardName.length() > MAX_NAME) {
			errors.rejectValue("cardName", "size", "Keep the name to " + MAX_NAME + " characters or fewer.");
		}
	}

	/** The Luhn checksum every real card number passes; catches most typos. */
	static boolean luhn(String digits) {
		int sum = 0;
		for (int i = 0; i < digits.length(); i++) {
			int d = digits.charAt(digits.length() - 1 - i) - '0';
			if (i % 2 == 1) {
				d *= 2;
				if (d > 9) {
					d -= 9;
				}
			}
			sum += d;
		}
		return sum % 10 == 0;
	}

	/** What pays, safe to store and show: "Card ending 1111" or "ri••@upi". Call after {@link #validate}. */
	public String maskedInstrument() {
		if (channel == PaymentChannel.CARD) {
			String digits = cardDigits();
			return "Card ending " + digits.substring(digits.length() - 4);
		}
		int at = upiId.indexOf('@');
		return upiId.substring(0, Math.min(2, at)) + "••" + upiId.substring(at);
	}

	/** The card number without spaces or dashes, or {@code null}. */
	private String cardDigits() {
		return cardNumber == null ? null : cardNumber.replaceAll("[\\s-]", "");
	}

	private static String clean(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	public PaymentChannel getChannel() {
		return channel;
	}

	public void setChannel(PaymentChannel channel) {
		this.channel = channel == null ? PaymentChannel.UPI : channel;
	}

	public String getUpiId() {
		return upiId;
	}

	public void setUpiId(String upiId) {
		this.upiId = clean(upiId);
	}

	public String getCardNumber() {
		return cardNumber;
	}

	public void setCardNumber(String cardNumber) {
		this.cardNumber = clean(cardNumber);
	}

	public String getCardExpiry() {
		return cardExpiry;
	}

	public void setCardExpiry(String cardExpiry) {
		this.cardExpiry = clean(cardExpiry);
	}

	public String getCardCvv() {
		return cardCvv;
	}

	public void setCardCvv(String cardCvv) {
		this.cardCvv = clean(cardCvv);
	}

	public String getCardName() {
		return cardName;
	}

	public void setCardName(String cardName) {
		this.cardName = clean(cardName);
	}

	public boolean isSimulateFailure() {
		return simulateFailure;
	}

	public void setSimulateFailure(boolean simulateFailure) {
		this.simulateFailure = simulateFailure;
	}

}
