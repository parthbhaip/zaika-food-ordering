package com.zaika.cart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.zaika.catalog.MenuCategory;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;
import com.zaika.config.AppProperties;
import com.zaika.user.Role;
import com.zaika.user.User;

/** Pure unit tests for the bill: platform fee 5.00, GST 5%, free delivery above 499.00 (the shipped defaults). */
class PricingServiceTest {

	private final PricingService pricing = service("5.00", "0.05", "499.00");

	private static PricingService service(String platformFee, String gstRate, String threshold) {
		return new PricingService(new AppProperties("Zaika", ZoneId.of("Asia/Kolkata"), "./uploads", false,
				new BigDecimal(platformFee), new BigDecimal(gstRate), new BigDecimal(threshold),
				new BigDecimal("30.00"), "http://localhost:8080"));
	}

	private static BigDecimal inr(String amount) {
		return new BigDecimal(amount);
	}

	/** Asserts every part of the bill, including the scale, so "16" and "16.00" are told apart. */
	private static void assertBill(PriceBreakdown bill, String items, String discount, String delivery,
			String platform, String gst, String grand) {
		assertThat(List.of(bill.itemTotal(), bill.discount(), bill.deliveryFee(), bill.platformFee(), bill.gst(),
				bill.grandTotal()))
			.extracting(BigDecimal::toPlainString)
			.containsExactly(items, discount, delivery, platform, gst, grand);
	}

	@ParameterizedTest(name = "items {0}, fee {1}, discount {2} -> grand {7}")
	@CsvSource({
			// items,  fee,   discount, discount', delivery, platform, gst,   grand
			"320.00,   30.00, 0.00,     0.00,      30.00,    5.00,     16.00, 371.00",  // a typical cart
			"320,      30,    0,        0.00,      30.00,    5.00,     16.00, 371.00",  // unscaled input comes out at 2dp
			"498.99,   30.00, 0.00,     0.00,      30.00,    5.00,     24.95, 558.94",  // a paisa below the threshold
			"499.00,   30.00, 0.00,     0.00,      30.00,    5.00,     24.95, 558.95",  // exactly at the threshold: still charged
			"499.01,   30.00, 0.00,     0.00,      0.00,     5.00,     24.95, 528.96",  // above the threshold: free
			"200.00,   0.00,  0.00,     0.00,      0.00,     5.00,     10.00, 215.00",  // kitchen with free delivery
			"600.00,   30.00, 100.00,   100.00,    0.00,     5.00,     25.00, 530.00",  // GST on (items - discount)
			"520.00,   30.00, 50.00,    50.00,     0.00,     5.00,     23.50, 498.50",  // threshold uses items before discount
			"200.00,   30.00, 500.00,   200.00,    30.00,    5.00,     0.00,  35.00",   // discount larger than the items
			"200.00,   30.00, 200.00,   200.00,    30.00,    5.00,     0.00,  35.00",   // discount equal to the items
			"200.00,   30.00, 12.345,   12.35,     30.00,    5.00,     9.38,  232.03",  // discount rounded HALF_UP
			"200.00,   30.00, 12.344,   12.34,     30.00,    5.00,     9.38,  232.04",  // discount rounded down
			"10.10,    30.00, 0.00,     0.00,      30.00,    5.00,     0.51,  45.61",   // GST 0.505 -> 0.51
			"10.30,    30.00, 0.00,     0.00,      30.00,    5.00,     0.52,  45.82",   // GST 0.515 -> 0.52
			"10.09,    30.00, 0.00,     0.00,      30.00,    5.00,     0.50,  45.59",   // GST 0.5045 -> 0.50
			"0.01,     30.00, 0.00,     0.00,      30.00,    5.00,     0.00,  35.01",   // GST 0.0005 -> 0.00
			"0.00,     30.00, 0.00,     0.00,      0.00,     0.00,     0.00,  0.00",    // nothing ordered: no fees
			"0.00,     30.00, 50.00,    0.00,      0.00,     0.00,     0.00,  0.00",    // a discount on nothing is nothing
			"0.004,    30.00, 0.00,     0.00,      0.00,     0.00,     0.00,  0.00",    // rounds to zero items
			"99999.99, 30.00, 0.00,     0.00,      0.00,     5.00,     5000.00, 105004.99", // GST 4999.9995 -> 5000.00
	})
	void pricesTheBill(String items, String fee, String discount, String expectedDiscount, String delivery,
			String platform, String gst, String grand) {
		assertBill(pricing.price(inr(items), inr(fee), inr(discount)), new BigDecimal(items).setScale(2,
				RoundingMode.HALF_UP).toPlainString(), expectedDiscount, delivery, platform, gst, grand);
	}

	@Test
	void taxableAmountIsTheItemTotalLessTheDiscount() {
		assertThat(pricing.price(inr("600.00"), inr("30.00"), inr("100.00")).taxableAmount()).isEqualByComparingTo("500.00");
		assertThat(pricing.price(inr("200.00"), inr("30.00"), inr("900.00")).taxableAmount()).isEqualByComparingTo("0");
	}

	@Test
	void otherSettingsChangeTheBill() {
		// No platform fee, 18% GST, free delivery above 300.
		PricingService other = service("0.00", "0.18", "300.00");
		assertBill(other.price(inr("333.33"), inr("40.00"), inr("0")), "333.33", "0.00", "0.00", "0.00", "60.00",
				"393.33"); // 59.9994 -> 60.00
		assertBill(other.price(inr("299.99"), inr("40.00"), inr("0")), "299.99", "0.00", "40.00", "0.00", "54.00",
				"393.99"); // 53.9982 -> 54.00
		assertBill(other.price(inr("300.00"), inr("40.00"), inr("0")), "300.00", "0.00", "40.00", "0.00", "54.00",
				"394.00"); // at the threshold, not above it: fee charged

		// No GST at all.
		assertBill(service("5.00", "0.00", "499.00").price(inr("100.00"), inr("30.00"), inr("0")), "100.00", "0.00",
				"30.00", "5.00", "0.00", "135.00");

		// A threshold of zero means delivery is always free.
		assertBill(service("5.00", "0.05", "0.00").price(inr("1.00"), inr("30.00"), inr("0")), "1.00", "0.00",
				"0.00", "5.00", "0.05", "6.05");
	}

	@Test
	void everyBillAddsUpAndStaysAtTwoDecimals() {
		String[] items = { "0.00", "0.01", "0.99", "1.00", "149.50", "333.33", "498.99", "499.00", "499.01", "1234.56" };
		String[] fees = { "0.00", "0.01", "25.00", "35.50" };
		String[] discounts = { "0.00", "0.01", "33.333", "150.00", "498.99", "499.00", "5000.00" };
		int checked = 0;
		for (String i : items) {
			for (String f : fees) {
				for (String d : discounts) {
					PriceBreakdown bill = pricing.price(inr(i), inr(f), inr(d));
					assertThat(List.of(bill.itemTotal(), bill.discount(), bill.deliveryFee(), bill.platformFee(),
							bill.gst(), bill.grandTotal()))
						.as("%s / %s / %s", i, f, d)
						.allSatisfy(amount -> {
							assertThat(amount.scale()).isEqualTo(2);
							assertThat(amount.signum()).isNotNegative();
						});
					assertThat(bill.grandTotal()).as("%s / %s / %s", i, f, d)
						.isEqualTo(bill.itemTotal().subtract(bill.discount()).add(bill.deliveryFee())
							.add(bill.platformFee()).add(bill.gst()));
					assertThat(bill.discount()).isLessThanOrEqualTo(bill.itemTotal());
					assertThat(bill.deliveryFee().signum() == 0)
						.isEqualTo(bill.itemTotal().compareTo(inr("499.00")) > 0 || inr(f).signum() == 0
								|| bill.itemTotal().signum() == 0);
					checked++;
				}
			}
		}
		assertThat(checked).isEqualTo(280);
	}

	@Test
	void negativeAmountsAreRejected() {
		assertThatThrownBy(() -> pricing.price(inr("-0.01"), inr("30.00"), inr("0")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("itemTotal");
		assertThatThrownBy(() -> pricing.price(inr("100.00"), inr("-30.00"), inr("0")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("restaurantDeliveryFee");
		assertThatThrownBy(() -> pricing.price(inr("100.00"), inr("30.00"), inr("-5")))
			.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("discount");
	}

	@Test
	void missingAmountsAreRejected() {
		assertThatThrownBy(() -> pricing.price(null, inr("30.00"), inr("0"))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> pricing.price(inr("100.00"), null, inr("0"))).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> pricing.price(inr("100.00"), inr("30.00"), null))
			.isInstanceOf(NullPointerException.class);
	}

	@Test
	void aCartIsPricedWithItsRestaurantsDeliveryFee() {
		Restaurant kitchen = new Restaurant(new User("Owner", "owner@pricing.zaika.test", "9876543210", "x",
				Role.RESTAURANT_OWNER), "misal-house", "Misal House");
		kitchen.setDeliveryFee(inr("35.00"));
		MenuItem misal = new MenuItem(new MenuCategory(kitchen, "Misal", 1), "Kolhapuri Misal", inr("120.00"), true);
		CartView cart = new CartView(kitchen, true, null, List.of(new CartView.Line(misal, 2, inr("240.00"))),
				inr("240.00"), 2, List.of());

		assertBill(pricing.price(cart, inr("0")), "240.00", "0.00", "35.00", "5.00", "12.00", "292.00");
		assertBill(pricing.price(cart, inr("40.00")), "240.00", "40.00", "35.00", "5.00", "10.00", "250.00");
	}

	@Test
	void anEmptyCartCostsNothing() {
		assertBill(pricing.price(CartView.empty(List.of()), inr("50.00")), "0.00", "0.00", "0.00", "0.00", "0.00",
				"0.00");
	}

}
