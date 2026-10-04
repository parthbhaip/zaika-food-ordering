package com.zaika.user;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;

/**
 * A "forgot password" link. Only the SHA-256 hash of the emailed token is kept; the token itself lives only in the
 * email. Usable once, until {@link #getExpiresAt()}. Only {@link PasswordResetService} creates and spends it.
 */
@Entity
@Table(name = "password_reset_tokens")
public class PasswordResetToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Column(name = "token_hash", nullable = false, length = 64, unique = true, updatable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false, updatable = false)
	private LocalDateTime expiresAt;

	@Column(name = "used_at")
	private LocalDateTime usedAt;

	protected PasswordResetToken() {
	}

	PasswordResetToken(User user, String tokenHash, LocalDateTime expiresAt) {
		this.user = Objects.requireNonNull(user, "user");
		this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
		this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
	}

	/** Not used yet and not expired at {@code now}. */
	boolean isUsableAt(LocalDateTime now) {
		return usedAt == null && expiresAt.isAfter(now);
	}

	public User getUser() {
		return user;
	}

	public String getTokenHash() {
		return tokenHash;
	}

	public LocalDateTime getExpiresAt() {
		return expiresAt;
	}

	public LocalDateTime getUsedAt() {
		return usedAt;
	}

}
