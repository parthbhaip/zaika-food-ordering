package com.zaika.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * The single source of "now": a {@link Clock} in the app zone (Asia/Kolkata), also used by JPA auditing for
 * {@code createdAt}/{@code updatedAt}. Reads {@code app.zone} directly rather than {@link AppProperties} so a
 * {@code @DataJpaTest} can {@code @Import(TimeConfig.class)} without binding the whole properties record.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class TimeConfig {

	@Bean
	Clock clock(@Value("${app.zone}") ZoneId zone) {
		return Clock.system(zone);
	}

	@Bean
	DateTimeProvider auditingDateTimeProvider(Clock clock) {
		return () -> Optional.of(LocalDateTime.now(clock));
	}

}
