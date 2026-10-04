package com.zaika.common;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Test-only entity (table from src/test/resources/db/migration) used to exercise {@link BaseEntity}. */
@Entity
@Table(name = "audit_probe")
public class AuditProbe extends BaseEntity {

	@Column(nullable = false, length = 60)
	private String name;

	protected AuditProbe() {
	}

	public AuditProbe(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public void rename(String name) {
		this.name = name;
	}

}
