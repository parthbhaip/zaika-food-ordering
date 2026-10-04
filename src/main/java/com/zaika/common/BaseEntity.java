package com.zaika.common;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.hibernate.proxy.HibernateProxy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Common columns for every table: {@code id BIGINT AUTO_INCREMENT}, {@code created_at} and {@code updated_at}
 * ({@code DATETIME(6)}, IST, filled by JPA auditing from the app {@link java.time.Clock}) and {@code version}
 * ({@code BIGINT}, optimistic locking).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@CreatedDate
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@LastModifiedDate
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Version
	@Column(nullable = false)
	private Long version;

	public Long getId() {
		return id;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

	/**
	 * Same entity type and same database id. Unsaved entities are only equal to themselves. Comparing against an
	 * uninitialized proxy (e.g. a detached {@code getReference}) does not load it: the proxy's mapped class and its
	 * id are both known without a session ({@code Hibernate.getClass} and {@code getClassLazy} both need one).
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BaseEntity that) || entityClass(this) != entityClass(that)) {
			return false;
		}
		return getId() != null && getId().equals(that.getId());
	}

	/** Constant per type so the hash does not change when the entity is saved and gets its id. */
	@Override
	public int hashCode() {
		return entityClass(this).hashCode();
	}

	private static Class<?> entityClass(Object entity) {
		return entity instanceof HibernateProxy proxy
				? proxy.getHibernateLazyInitializer().getPersistentClass()
				: entity.getClass();
	}

}
