package com.zaika.order;

import java.util.Objects;

import com.zaika.user.Role;

/**
 * Who asks {@link OrderWorkflow} for a status change: a signed-in account (with the role it acts in) or the system
 * itself (payment confirmation, expiry of unpaid orders). {@code userId} is {@code null} only for {@link Kind#SYSTEM}.
 */
public record OrderActor(Kind kind, Long userId) {

	public enum Kind {
		CUSTOMER, RESTAURANT, RIDER, ADMIN, SYSTEM
	}

	public OrderActor {
		Objects.requireNonNull(kind, "kind");
		if ((kind == Kind.SYSTEM) != (userId == null)) {
			throw new IllegalArgumentException("Only the system acts without an account");
		}
	}

	public static OrderActor customer(long userId) {
		return new OrderActor(Kind.CUSTOMER, userId);
	}

	public static OrderActor restaurant(long ownerId) {
		return new OrderActor(Kind.RESTAURANT, ownerId);
	}

	public static OrderActor rider(long riderId) {
		return new OrderActor(Kind.RIDER, riderId);
	}

	public static OrderActor admin(long adminId) {
		return new OrderActor(Kind.ADMIN, adminId);
	}

	public static OrderActor system() {
		return new OrderActor(Kind.SYSTEM, null);
	}

	/** The actor for a signed-in account, by its role. */
	public static OrderActor of(long userId, Role role) {
		return switch (role) {
			case CUSTOMER -> customer(userId);
			case RESTAURANT_OWNER -> restaurant(userId);
			case DELIVERY_PARTNER -> rider(userId);
			case ADMIN -> admin(userId);
		};
	}

}
