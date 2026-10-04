package com.zaika.user;

/** What an account may do. Stored by name in {@code users.role}; Spring Security authorities are {@code ROLE_<name>}. */
public enum Role {
	CUSTOMER, RESTAURANT_OWNER, DELIVERY_PARTNER, ADMIN
}
