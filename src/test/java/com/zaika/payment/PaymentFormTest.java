package com.zaika.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

/** The payment page's format checks (UPI ID, card number with Luhn, expiry against this month, CVV, name), masking, and the mock gateway. */
class PaymentFormTest {

	static final YearMonth OCT_2026 = YearMonth.of(2026, 10);

	static PaymentForm upi(String id) {
		PaymentForm form = new PaymentForm();
		form.setUpiId(id);
		return form;
	}

	static PaymentForm card(String number, String expiry, String cvv, String name) {
		PaymentForm form = new PaymentForm();
		form.setChannel(PaymentChannel.CARD);
		form.setCardNumber(number);
		form.setCardExpiry(expiry);
		form.setCardCvv(cvv);
		form.setCardName(name);
		return form;
	}

	/** field -> message for every error. */
	static Map<String, String> errors(PaymentForm form) {
		Errors errors = new BeanPropertyBindingResult(form, "form");
		form.validate(errors, OCT_2026);
		Map<String, String> byField = new TreeMap<>();
		errors.getFieldErrors().forEach(e -> byField.put(e.getField(), e.getDefaultMessage()));
		return byField;
	}

	@Test
	void upiIdsLookLikeNameAtHandle() {
		for (String ok : new String[] { "riya@upi", "riya.shinde-92@zaikabank", " RS_01@okzaika " }) {
			assertThat(errors(upi(ok))).as(ok).isEmpty();
		}
		assertThat(errors(upi("  "))).containsExactly(Map.entry("upiId", "Enter your UPI ID."));
		for (String bad : new String[] { "riya", "riya@", "@upi", "r@upi", "riya@up i", "riya@@upi", "riya@9bank",
				"riya@upi.com" }) {
			assertThat(errors(upi(bad))).as(bad).containsExactly(Map.entry("upiId", "Enter a UPI ID like name@bank."));
		}
	}

	@Test
	void aValidCardPassesWithSpacesOrDashes() {
		assertThat(errors(card("4111 1111 1111 1111", "12/30", "123", "Riya Shinde"))).isEmpty();
		assertThat(errors(card("4111-1111-1111-1111", "1030", "1234", "R Shinde"))).isEmpty();
		assertThat(errors(card("5555555555554444", "10 / 26", "999", "Riya"))).isEmpty(); // this month still works
	}

	@Test
	void cardNumbersMustPassTheLuhnCheckAndLength() {
		String wrong = "That card number does not look right. Check the digits.";
		assertThat(errors(card("4111 1111 1111 1112", "12/30", "123", "Riya"))).containsExactly(Map.entry("cardNumber", wrong));
		assertThat(errors(card("4111 1111 111", "12/30", "123", "Riya"))).containsExactly(Map.entry("cardNumber", wrong));
		assertThat(errors(card("4111 1111 1111 111a", "12/30", "123", "Riya"))).containsExactly(Map.entry("cardNumber", wrong));
		assertThat(errors(card("41111111111111111111", "12/30", "123", "Riya"))).containsExactly(Map.entry("cardNumber", wrong));
		assertThat(PaymentForm.luhn("79927398713")).isTrue();
		assertThat(PaymentForm.luhn("79927398710")).isFalse();
	}

	@Test
	void expiryIsMonthSlashYearAndNotInThePast() {
		assertThat(errors(card("4111111111111111", "09/26", "123", "Riya")))
			.containsExactly(Map.entry("cardExpiry", "This card has expired. Use another card or pay by UPI."));
		for (String bad : new String[] { "13/30", "00/30", "1/30", "12/2030", "12-30", "soon" }) {
			assertThat(errors(card("4111111111111111", bad, "123", "Riya"))).as(bad)
				.containsExactly(Map.entry("cardExpiry", "Enter the expiry date as MM/YY."));
		}
	}

	@Test
	void everyMissingCardFieldHasItsOwnMessage() {
		assertThat(errors(card(" ", null, "", null))).containsExactly(
				Map.entry("cardCvv", "Enter the CVV."),
				Map.entry("cardExpiry", "Enter the expiry date."),
				Map.entry("cardName", "Enter the name on the card."),
				Map.entry("cardNumber", "Enter the card number."));
		assertThat(errors(card("4111111111111111", "12/30", "12", "R".repeat(61)))).containsExactly(
				Map.entry("cardCvv", "The CVV is the 3 or 4 digits on the back of the card."),
				Map.entry("cardName", "Keep the name to 60 characters or fewer."));
	}

	@Test
	void onlyTheChosenChannelIsChecked() {
		PaymentForm form = card(null, null, null, null);
		form.setChannel(PaymentChannel.UPI);
		form.setUpiId("riya@upi");
		assertThat(errors(form)).isEmpty();
		form.setChannel(null);
		assertThat(form.getChannel()).isEqualTo(PaymentChannel.UPI);
	}

	@Test
	void onlyAMaskedInstrumentLeavesTheForm() {
		assertThat(upi("riya.shinde@upi").maskedInstrument()).isEqualTo("ri••@upi");
		assertThat(upi("rs@upi").maskedInstrument()).isEqualTo("rs••@upi");
		assertThat(card("4111 1111 1111 1111", "12/30", "123", "Riya").maskedInstrument()).isEqualTo("Card ending 1111");
	}

	@Test
	void tabsMapToChannels() {
		assertThat(PaymentChannel.ofTab("card")).isEqualTo(PaymentChannel.CARD);
		assertThat(PaymentChannel.ofTab("upi")).isEqualTo(PaymentChannel.UPI);
		assertThat(PaymentChannel.ofTab(null)).isEqualTo(PaymentChannel.UPI);
		assertThat(PaymentChannel.ofTab("CARD")).isEqualTo(PaymentChannel.UPI);
		assertThat(PaymentChannel.CARD.getTab()).isEqualTo("card");
	}

	@Test
	void theMockGatewayApprovesUnlessAskedToDecline() {
		MockPaymentGateway gateway = new MockPaymentGateway();
		var ok = gateway.charge(new PaymentGateway.Charge("ZK-26-000001", new BigDecimal("530.00"), PaymentChannel.UPI,
				"ri••@upi", false));
		var again = gateway.charge(new PaymentGateway.Charge("ZK-26-000001", new BigDecimal("530.00"),
				PaymentChannel.UPI, "ri••@upi", false));
		assertThat(ok.succeeded()).isTrue();
		assertThat(ok.reference()).matches("MOCK-[0-9A-F]{16}").isNotEqualTo(again.reference());
		assertThat(ok.failureReason()).isNull();

		var upiDeclined = gateway.charge(new PaymentGateway.Charge("ZK-26-000001", new BigDecimal("530.00"),
				PaymentChannel.UPI, "ri••@upi", true));
		var cardDeclined = gateway.charge(new PaymentGateway.Charge("ZK-26-000001", new BigDecimal("530.00"),
				PaymentChannel.CARD, "Card ending 1111", true));
		assertThat(upiDeclined.succeeded()).isFalse();
		assertThat(upiDeclined.reference()).isNull();
		assertThat(upiDeclined.failureReason()).isEqualTo(MockPaymentGateway.UPI_DECLINED);
		assertThat(cardDeclined.failureReason()).isEqualTo(MockPaymentGateway.CARD_DECLINED);
		assertThat(gateway.name()).isEqualTo("MOCK");
	}

}
