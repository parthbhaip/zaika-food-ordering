package com.zaika.notification;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/** One account's notifications, newest first (idx_notifications_user_created). */
	@Query(value = "select n from Notification n where n.user.id = :userId order by n.createdAt desc, n.id desc",
			countQuery = "select count(n) from Notification n where n.user.id = :userId")
	Page<Notification> findForUser(long userId, Pageable pageable);

	/** The bell's number (idx_notifications_user_read). */
	long countByUserIdAndReadAtIsNull(long userId);

	Optional<Notification> findByIdAndUserId(long id, long userId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Notification n set n.readAt = :now, n.version = n.version + 1 "
			+ "where n.user.id = :userId and n.readAt is null")
	int markAllRead(long userId, LocalDateTime now);

}
