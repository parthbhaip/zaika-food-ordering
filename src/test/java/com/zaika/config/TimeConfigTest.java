package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.support.MutableClock;

@SpringBootTest
@ActiveProfiles("test")
class TimeConfigTest {

	@Autowired
	Clock clock;

	@Test
	void productionClockTicksInIndianStandardTime() {
		Clock system = new TimeConfig().clock(ZoneId.of("Asia/Kolkata"));
		assertThat(system.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		assertThat(system.instant()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
	}

	@Test
	void auditingUsesTheInjectedClock() {
		Clock fixed = Clock.fixed(LocalDateTime.of(2026, 1, 26, 9, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),
				ZoneId.of("Asia/Kolkata"));
		assertThat(new TimeConfig().auditingDateTimeProvider(fixed).getNow()).contains(LocalDateTime.of(2026, 1, 26, 9, 0));
	}

	@Test
	void testsRunOnTheFixedTestClock() {
		assertThat(clock).isInstanceOf(MutableClock.class);
		assertThat(LocalDateTime.now(clock)).isEqualTo(MutableClock.DEFAULT_NOW);
	}

}
