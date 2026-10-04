package com.zaika.common;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.hibernate.Hibernate;
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

	/** Same entity type and same database id. Unsaved entities are only equal to themselves. Proxy-safe. */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BaseEntity that) || Hibernate.getClass(this) != Hibernate.getClass(that)) {
			return false;
		}
		return getId() != null && getId().equals(that.getId());
	}

	/** Constant per type so the hash does not change when the entity is saved and gets its id. */
	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
