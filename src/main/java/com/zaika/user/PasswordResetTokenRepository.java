package com.zaika.user;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

	/** The token with its account, for showing the reset form. */
	@Query("select t from PasswordResetToken t join fetch t.user where t.tokenHash = :tokenHash")
	Optional<PasswordResetToken> findByTokenHash(String tokenHash);

	/** Same, with a row lock: two submits of one link are serialized, so only the first can spend it. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from PasswordResetToken t join fetch t.user where t.tokenHash = :tokenHash")
	Optional<PasswordResetToken> findForUpdateByTokenHash(String tokenHash);

	/** Spends every open link of an account, e.g. after its password was reset with one of them. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update PasswordResetToken t set t.usedAt = :now where t.user.id = :userId and t.usedAt is null")
	int markAllUsed(long userId, LocalDateTime now);

}
