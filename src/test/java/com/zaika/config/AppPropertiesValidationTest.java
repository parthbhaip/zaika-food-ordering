package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** AppProperties rejects bad values at startup instead of failing later at checkout. */
class AppPropertiesValidationTest {

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(AppProperties.class)
	static class Config {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class)
		.withPropertyValues("app.brand-name=Zaika", "app.zone=Asia/Kolkata", "app.upload-dir=./uploads",
				"app.seed-demo=true", "app.platform-fee=5", "app.gst-rate=0.05", "app.free-delivery-threshold=499",
				"app.rider-base-payout=30.5", "app.rider-delivery-fee-share=0.8", "app.base-url=https://zaika.example.in/");

	@Test
	void validValuesBindAndMoneyIsScaledToTwoDecimals() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			AppProperties app = context.getBean(AppProperties.class);
			assertThat(app.seedDemo()).isTrue();
			assertThat(app.platformFee()).isEqualTo(new BigDecimal("5.00"));
			assertThat(app.freeDeliveryThreshold()).isEqualTo(new BigDecimal("499.00"));
			assertThat(app.riderBasePayout()).isEqualTo(new BigDecimal("30.50"));
			assertThat(app.riderDeliveryFeeShare()).isEqualByComparingTo("0.80");
			assertThat(app.baseUrl()).isEqualTo("https://zaika.example.in");
		});
	}

	@Test
	void blankBrandNameFailsStartup() {
		assertFails("app.brand-name= ", "brandName");
	}

	@Test
	void missingUploadDirFailsStartup() {
		assertFails("app.upload-dir=", "uploadDir");
	}

	@Test
	void negativePlatformFeeFailsStartup() {
		assertFails("app.platform-fee=-1", "platformFee");
	}

	@Test
	void moneyWithMoreThanTwoDecimalsFailsStartup() {
		assertFails("app.rider-base-payout=30.555", "riderBasePayout");
	}

	@Test
	void riderShareAboveTheWholeDeliveryFeeFailsStartup() {
		assertFails("app.rider-delivery-fee-share=1.5", "riderDeliveryFeeShare");
		assertFails("app.rider-delivery-fee-share=-0.1", "riderDeliveryFeeShare");
	}

	@Test
	void gstRateAboveOneHundredPercentFailsStartup() {
		assertFails("app.gst-rate=5", "gstRate");
	}

	@Test
	void baseUrlWithoutSchemeFailsStartup() {
		assertFails("app.base-url=zaika.example.in", "baseUrl");
	}

	@Test
	void unknownZoneFailsStartup() {
		assertFails("app.zone=Mars/Olympus", "app.zone");
	}

	private void assertFails(String badValue, String expectedInMessage) {
		runner.withPropertyValues(badValue).run(context -> {
			assertThat(context).hasFailed();
			assertThat(stackMessages(context.getStartupFailure())).contains(expectedInMessage);
		});
	}

	private static String stackMessages(Throwable ex) {
		StringBuilder messages = new StringBuilder();
		for (Throwable t = ex; t != null; t = t.getCause()) {
			messages.append(t.getMessage()).append('\n');
		}
		return messages.toString();
	}

}
