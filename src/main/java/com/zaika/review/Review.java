package com.zaika.review;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import com.zaika.catalog.Restaurant;
import com.zaika.common.BaseEntity;
import com.zaika.order.Order;
import com.zaika.user.User;

/**
 * A customer's review of one delivered order: 1-5 stars and an optional comment of up to {@link #MAX_TEXT}
 * characters. Never edited after posting. The restaurant's owner may reply once; an admin may hide it (and show it
 * again). Hidden reviews leave the public list and the restaurant's rating.
 */
@Entity
@Table(name = "reviews")
public class Review extends BaseEntity {

	/** Longest comment or reply. */
	public static final int MAX_TEXT = 500;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "order_id", nullable = false, updatable = false)
	private Order order;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "restaurant_id", nullable = false, updatable = false)
	private Restaurant restaurant;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "customer_id", nullable = false, updatable = false)
	private User customer;

	@Column(nullable = false, updatable = false)
	private int stars;

	@Column(length = MAX_TEXT, updatable = false)
	private String comment;

	@Column(length = MAX_TEXT)
	private String reply;

	@Column(name = "replied_at")
	private LocalDateTime repliedAt;

	@Column(nullable = false)
	private boolean hidden;

	@Column(name = "hidden_at")
	private LocalDateTime hiddenAt;

	protected Review() {
	}

	/** {@code comment} is stripped; blank means none. */
	public Review(Order order, int stars, String comment) {
		if (stars < 1 || stars > 5) {
			throw new IllegalArgumentException("stars must be 1-5: " + stars);
		}
		this.order = Objects.requireNonNull(order, "order");
		this.restaurant = order.getRestaurant();
		this.customer = order.getCustomer();
		this.stars = stars;
		this.comment = comment == null || comment.isBlank() ? null : comment.strip();
	}

	/** The owner's one reply. */
	public void reply(String text, LocalDateTime at) {
		if (reply != null) {
			throw new IllegalStateException("Review " + getId() + " already has a reply");
		}
		this.reply = Objects.requireNonNull(text, "text").strip();
		this.repliedAt = Objects.requireNonNull(at, "at");
	}

	void setHidden(boolean hidden, LocalDateTime at) {
		this.hidden = hidden;
		this.hiddenAt = hidden ? at : null;
	}

	/** How the public list names the customer: "Riya S." (first name and the initial of the last). */
	public String getAuthorName() {
		String[] parts = customer.getName().strip().split("\\s+");
		if (parts.length == 1) {
			return parts[0];
		}
		int initial = Character.toUpperCase(parts[parts.length - 1].codePointAt(0));
		return parts[0] + " " + Character.toString(initial) + ".";
	}

	public Order getOrder() {
		return order;
	}

	public Restaurant getRestaurant() {
		return restaurant;
	}

	public User getCustomer() {
		return customer;
	}

	public int getStars() {
		return stars;
	}

	public String getComment() {
		return comment;
	}

	public String getReply() {
		return reply;
	}

	public LocalDateTime getRepliedAt() {
		return repliedAt;
	}

	public boolean isHidden() {
		return hidden;
	}

	public LocalDateTime getHiddenAt() {
		return hiddenAt;
	}

}
