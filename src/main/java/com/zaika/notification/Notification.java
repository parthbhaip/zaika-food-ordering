package com.zaika.notification;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.zaika.common.BaseEntity;
import com.zaika.user.User;

/**
 * One in-app message for one account: what happened (title, a sentence or two) and where to look (an app path such as
 * {@code /orders/ZK-26-000123/track}). Unread until the account opens it or marks everything read.
 */
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

	public static final int MAX_TITLE = 120;

	public static final int MAX_BODY = 400;

	public static final int MAX_LINK = 200;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false, updatable = false)
	private User user;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20, updatable = false)
	private Kind kind;

	@Column(nullable = false, length = MAX_TITLE, updatable = false)
	private String title;

	@Column(nullable = false, length = MAX_BODY, updatable = false)
	private String body;

	@Column(nullable = false, length = MAX_LINK, updatable = false)
	private String link;

	@Column(name = "read_at")
	private LocalDateTime readAt;

	protected Notification() {
	}

	Notification(User user, Kind kind, String title, String body, String link) {
		if (link == null || !link.startsWith("/") || link.startsWith("//")) {
			throw new IllegalArgumentException("A notification links to a page of this site: " + link);
		}
		this.user = user;
		this.kind = kind;
		this.title = cut(title, MAX_TITLE);
		this.body = cut(body, MAX_BODY);
		this.link = link;
	}

	/** {@code text} as a sentence: a full stop is added unless it already ends with one (or ! or ?). */
	public static String sentence(String text) {
		String t = text == null ? "" : text.strip();
		return t.isEmpty() || t.endsWith(".") || t.endsWith("!") || t.endsWith("?") ? t : t + ".";
	}

	private static String cut(String text, int max) {
		String stripped = text.strip();
		return stripped.length() <= max ? stripped : stripped.substring(0, max - 1).strip() + "…";
	}

	void markRead(LocalDateTime at) {
		if (readAt == null) {
			readAt = at;
		}
	}

	public Kind getKind() {
		return kind;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public String getLink() {
		return link;
	}

	public LocalDateTime getReadAt() {
		return readAt;
	}

	public boolean isUnread() {
		return readAt == null;
	}

	/** What a notification is about; picks its icon from the sprite. */
	public enum Kind {

		ORDER("receipt"), DELIVERY("bike"), RESTAURANT("store"), REVIEW("star"), ACCOUNT("user");

		private final String icon;

		Kind(String icon) {
			this.icon = icon;
		}

		public String getIcon() {
			return icon;
		}

	}

}
