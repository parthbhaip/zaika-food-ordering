package com.zaika.notification;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.NotFoundException;
import com.zaika.notification.Notification.Kind;
import com.zaika.user.User;

/**
 * The in-app inbox behind the header bell and {@code /notifications}. Other features call {@link #notify} inside their
 * own transaction, so a notification exists exactly when the change it reports was committed. Every lookup is by
 * account: someone else's notification is a 404.
 */
@Service
public class NotificationService {

	public static final int PAGE_SIZE = 20;

	private final NotificationRepository notifications;

	private final Clock clock;

	NotificationService(NotificationRepository notifications, Clock clock) {
		this.notifications = notifications;
		this.clock = clock;
	}

	/** Adds a notification for {@code user}, linking to {@code link} (a path of this site). */
	@Transactional
	public Notification notify(User user, Kind kind, String title, String body, String link) {
		return notifications.save(new Notification(user, kind, title, body, link));
	}

	@Transactional(readOnly = true)
	public long unreadCount(long userId) {
		return notifications.countByUserIdAndReadAtIsNull(userId);
	}

	/** Page {@code page} (0-based) of the account's notifications, newest first. */
	@Transactional(readOnly = true)
	public Page<Notification> page(long userId, int page) {
		return notifications.findForUser(userId, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
	}

	/** Marks every unread notification of the account read; returns how many there were. */
	@Transactional
	public int markAllRead(long userId) {
		return notifications.markAllRead(userId, LocalDateTime.now(clock));
	}

	/**
	 * Marks the account's notification read and returns where it points.
	 *
	 * @throws NotFoundException for an unknown id or another account's notification
	 */
	@Transactional
	public String open(long userId, long id) {
		Notification n = notifications.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new NotFoundException("Notification " + id + " for user " + userId));
		n.markRead(LocalDateTime.now(clock));
		return n.getLink();
	}

}
