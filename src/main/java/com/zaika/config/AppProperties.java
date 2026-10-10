package com.zaika.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated {@code app.*} settings. Startup fails with a clear message if any value is missing or invalid.
 *
 * @param brandName             name shown in titles, emails and the header
 * @param zone                  the single time zone the app runs in (Asia/Kolkata)
 * @param uploadDir             directory for user uploads, outside the classpath
 * @param seedDemo              load demo restaurants, menus and accounts on startup
 * @param platformFee           flat fee per order, INR
 * @param gstRate               GST as a fraction, 0.05 = 5%
 * @param freeDeliveryThreshold food subtotal above which delivery is free, INR
 * @param riderBasePayout       rider earning per delivered order before extras, INR
 * @param riderDeliveryFeeShare the part of the order's delivery fee the rider also earns, as a fraction (0.80 = 80%)
 * @param baseUrl               public address of the site (scheme, host, optional port and path, no trailing slash);
 *                              links in emails start with it
 */
@Validated
@ConfigurationProperties("app")
public record AppProperties(
		@NotBlank String brandName,
		@NotNull ZoneId zone,
		@NotBlank String uploadDir,
		boolean seedDemo,
		@NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal platformFee,
		@NotNull @DecimalMin("0.00") @DecimalMax("1.00") @Digits(integer = 1, fraction = 4) BigDecimal gstRate,
		@NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal freeDeliveryThreshold,
		@NotNull @DecimalMin("0.00") @Digits(integer = 6, fraction = 2) BigDecimal riderBasePayout,
		@NotNull @DecimalMin("0.00") @DecimalMax("1.00") @Digits(integer = 1, fraction = 2) BigDecimal riderDeliveryFeeShare,
		@NotBlank @Pattern(regexp = "https?://[^\\s/]+(/\\S*)?",
				message = "must be an http(s) address like https://zaika.example.in") String baseUrl) {

	public AppProperties {
		platformFee = money(platformFee);
		freeDeliveryThreshold = money(freeDeliveryThreshold);
		riderBasePayout = money(riderBasePayout);
		baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
	}

	/** Money is always scale 2. Null is left for validation to report. Over-precise values are already rejected by @Digits. */
	private static BigDecimal money(BigDecimal amount) {
		return amount == null || amount.scale() > 2 ? amount : amount.setScale(2, RoundingMode.HALF_UP);
	}

}
