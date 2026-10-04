package com.zaika.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Test clock: fixed at {@link #DEFAULT_NOW} (IST) until a test moves it. Shared by every test in a cached Spring
 * context, so a test that calls {@link #setTo} or {@link #advance} must call {@link #reset} in {@code @AfterEach}.
 */
public class MutableClock extends Clock {

	/** Sunday 4 Oct 2026, 7:30 PM IST: dinner rush, most demo restaurants open. */
	public static final LocalDateTime DEFAULT_NOW = LocalDateTime.of(2026, 10, 4, 19, 30);

	private final ZoneId zone;

	private volatile Instant instant;

	public MutableClock(ZoneId zone) {
		this.zone = zone;
		reset();
	}

	public void setTo(LocalDateTime localNow) {
		this.instant = localNow.atZone(zone).toInstant();
	}

	public void advance(Duration amount) {
		this.instant = instant.plus(amount);
	}

	public void reset() {
		setTo(DEFAULT_NOW);
	}

	@Override
	public ZoneId getZone() {
		return zone;
	}

	@Override
	public Clock withZone(ZoneId other) {
		return Clock.fixed(instant, other);
	}

	@Override
	public Instant instant() {
		return instant;
	}

}
