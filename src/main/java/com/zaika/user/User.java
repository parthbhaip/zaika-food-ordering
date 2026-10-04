package com.zaika.user;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.zaika.common.BaseEntity;

/**
 * An account of any role. The email is the login name and is always stored trimmed and lower-case
 * ({@link #normalizeEmail}). Only the BCrypt hash of the password is kept.
 */
@Entity
@Table(name = "users")
public class User extends BaseEntity {

	/** 10-digit Indian mobile number starting with 6, 7, 8 or 9 (no +91 or spaces). */
	public static final String PHONE_PATTERN = "[6-9][0-9]{9}";

	@NotBlank
	@Size(max = 80)
	@Column(nullable = false, length = 80)
	private String name;

	@NotBlank
	@Email
	@Size(max = 254)
	@Column(nullable = false, length = 254, unique = true)
	private String email;

	@NotBlank
	@Pattern(regexp = PHONE_PATTERN)
	@Column(nullable = false, length = 10)
	private String phone;

	@NotBlank
	@Column(name = "password_hash", nullable = false, length = 100)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Role role;

	@Column(nullable = false)
	private boolean enabled = true;

	@Column(name = "failed_login_count", nullable = false)
	private int failedLoginCount;

	@Column(name = "locked_until")
	private LocalDateTime lockedUntil;

	protected User() {
	}

	public User(String name, String email, String phone, String passwordHash, Role role) {
		this.name = name;
		this.email = normalizeEmail(email);
		this.phone = phone;
		this.passwordHash = passwordHash;
		this.role = Objects.requireNonNull(role, "role");
	}

	/** The stored form of an email address: trimmed, lower-case. {@code null} stays {@code null}. */
	public static String normalizeEmail(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	/** True while a lock set after too many failed logins is still in force at {@code now}. */
	public boolean isLockedAt(LocalDateTime now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}

	public String getName() {
		return name;
	}

	public String getEmail() {
		return email;
	}

	public String getPhone() {
		return phone;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Role getRole() {
		return role;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public int getFailedLoginCount() {
		return failedLoginCount;
	}

	public LocalDateTime getLockedUntil() {
		return lockedUntil;
	}

}
