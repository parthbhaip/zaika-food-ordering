package com.zaika.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.support.MutableClock;

/** BaseEntity on a real (H2, Flyway-built) table: generated id, audit timestamps from the app clock, optimistic locking. */
@SpringBootTest
@ActiveProfiles("test")
class BaseEntityTest {

	@Autowired
	EntityManager em;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MutableClock clock;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	@Test
	void auditingStampsCreateAndUpdateTimesFromTheAppClock() {
		Long id = persist("Masala dosa");

		AuditProbe created = load(id);
		assertThat(created.getId()).isNotNull();
		assertThat(created.getCreatedAt()).isEqualTo(MutableClock.DEFAULT_NOW);
		assertThat(created.getUpdatedAt()).isEqualTo(MutableClock.DEFAULT_NOW);
		assertThat(created.getVersion()).isZero();

		clock.advance(Duration.ofMinutes(15));
		tx.executeWithoutResult(status -> em.find(AuditProbe.class, id).rename("Rava dosa"));

		AuditProbe updated = load(id);
		assertThat(updated.getName()).isEqualTo("Rava dosa");
		assertThat(updated.getCreatedAt()).isEqualTo(MutableClock.DEFAULT_NOW);
		assertThat(updated.getUpdatedAt()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(15));
		assertThat(updated.getVersion()).isEqualTo(1L);
	}

	@Test
	void staleCopyCannotOverwriteANewerVersion() {
		Long id = persist("Misal pav");
		AuditProbe stale = load(id);
		tx.executeWithoutResult(status -> em.find(AuditProbe.class, id).rename("Kolhapuri misal"));

		stale.rename("Puneri misal");
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> em.merge(stale)))
			.isInstanceOf(OptimisticLockException.class);
		assertThat(load(id).getName()).isEqualTo("Kolhapuri misal");
	}

	@Test
	void entitiesAreEqualByTypeAndId() {
		Long id = persist("Tambda rassa");
		AuditProbe first = load(id);
		AuditProbe second = load(id);
		AuditProbe other = load(persist("Pandhra rassa"));

		assertThat(first).isNotSameAs(second).isEqualTo(second).hasSameHashCodeAs(second);
		assertThat(first).isNotEqualTo(other);
		assertThat(new AuditProbe("Vada pav")).isNotEqualTo(new AuditProbe("Vada pav"));
	}

	private Long persist(String name) {
		return tx.execute(status -> {
			AuditProbe probe = new AuditProbe(name);
			em.persist(probe);
			return probe.getId();
		});
	}

	private AuditProbe load(Long id) {
		return tx.execute(status -> em.find(AuditProbe.class, id));
	}

}
