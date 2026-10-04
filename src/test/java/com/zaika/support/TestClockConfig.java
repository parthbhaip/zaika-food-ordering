package com.zaika.support;

import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Picked up by component scanning in every {@code @SpringBootTest}: replaces the system clock with a
 * {@link MutableClock} fixed at {@link MutableClock#DEFAULT_NOW}. Slice tests ({@code @DataJpaTest}, ...) do not
 * scan it; they {@code @Import(TestClockConfig.class)} alongside {@code TimeConfig} when they need a clock.
 */
@Configuration(proxyBeanMethods = false)
public class TestClockConfig {

	@Bean
	@Primary
	MutableClock testClock(@Value("${app.zone}") ZoneId zone) {
		return new MutableClock(zone);
	}

}
