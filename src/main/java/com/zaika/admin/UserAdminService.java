package com.zaika.admin;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.common.NotFoundException;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.security.LiveSessions;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;
import com.zaika.user.UserRepository.RoleCount;

/**
 * Accounts for the admin: the list with role counts, one account with its orders, and switching an account on or
 * off. Roles never change here and no account is ever made an admin. An admin cannot disable their own account or
 * the last enabled admin, and a disabled account is signed out everywhere once the change is committed.
 */
@Service
public class UserAdminService {

	public static final int PAGE_SIZE = 20;

	public static final int ORDERS_PAGE_SIZE = 10;

	private final UserRepository users;

	private final OrderRepository orders;

	private final RestaurantRepository restaurants;

	private final DeliveryPartnerProfileRepository riders;

	private final LiveSessions sessions;

	private final Clock clock;

	UserAdminService(UserRepository users, OrderRepository orders, RestaurantRepository restaurants,
			DeliveryPartnerProfileRepository riders, LiveSessions sessions, Clock clock) {
		this.clock = clock;
		this.users = users;
		this.orders = orders;
		this.restaurants = restaurants;
		this.riders = riders;
		this.sessions = sessions;
	}

	/** One page of the list and how many accounts each role has. */
	@Transactional(readOnly = true)
	public Queue<User, Role> list(ModerationFilter<Role> filter) {
		String pattern = filter.getQ() == null ? null : RestaurantBrowseService.likePattern(filter.getQ());
		Page<User> page = users.findForAdmin(filter.getStatus(), pattern,
				PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
		return Queue.of(page, Role.values(),
				users.countPerRole().stream().collect(Collectors.toMap(RoleCount::role, RoleCount::users)));
	}

	/**
	 * One account and a page (1-based) of the orders it placed as a customer, newest first, restaurants loaded.
	 *
	 * @param adminId the admin looking, to tell whether the account may be disabled
	 */
	@Transactional(readOnly = true)
	public Detail detail(long adminId, long userId, int page) {
		User user = users.findById(userId).orElseThrow(() -> new NotFoundException("No user " + userId));
		Page<Order> placed = orders.findByCustomerIdOrderByCreatedAtDescIdDesc(userId,
				PageRequest.of(Math.max(page, 1) - 1, ORDERS_PAGE_SIZE));
		String restaurant = user.getRole() == Role.RESTAURANT_OWNER
				? restaurants.findByOwnerId(userId).map(Restaurant::getSlug).orElse(null) : null;
		boolean rider = user.getRole() == Role.DELIVERY_PARTNER && riders.existsByUserId(userId);
		LocalDateTime lockedUntil = user.isLockedAt(LocalDateTime.now(clock)) ? user.getLockedUntil() : null;
		return new Detail(user, placed, restaurant, rider, lockedUntil,
				refusal(adminId, user, users.countByRoleAndEnabledTrue(Role.ADMIN)));
	}

	/**
	 * Switches the account on or off. Disabling also takes a rider offline and, after commit, ends every session of
	 * the account. Asking for the state the account is already in changes nothing.
	 *
	 * @return the account's name, for the confirmation message
	 * @throws NotFoundException   if there is no such account
	 * @throws ModerationException if it is the admin's own account or the last enabled admin
	 */
	@Transactional
	public String setEnabled(long adminId, long userId, boolean enabled) {
		User user = lock(userId, enabled);
		if (!enabled) {
			String refusal = refusal(adminId, user, users.countByRoleAndEnabledTrue(Role.ADMIN));
			if (refusal != null) {
				throw new ModerationException(refusal);
			}
		}
		if (user.isEnabled() == enabled) {
			return user.getName();
		}
		user.setEnabled(enabled);
		if (!enabled) {
			riders.findForUpdateByUserId(userId).filter(p -> p.isOnline()).ifPresent(p -> p.setOnline(false));
			AfterCommit.run(() -> sessions.endAll(userId));
		}
		return user.getName();
	}

	/**
	 * The account with a row lock. Disabling an admin first locks every admin row (in id order, the target among
	 * them), so the last-admin count cannot change underneath.
	 */
	private User lock(long userId, boolean enabled) {
		Role role = users.findRoleById(userId).orElseThrow(() -> new NotFoundException("No user " + userId));
		if (!enabled && role == Role.ADMIN) {
			List<User> admins = users.findAllForUpdateByRole(Role.ADMIN);
			return admins.stream().filter(a -> a.getId() == userId).findFirst().orElseThrow();
		}
		return users.findForUpdate(userId).orElseThrow(() -> new NotFoundException("No user " + userId));
	}

	/** Why {@code adminId} may not disable {@code user}, or null when they may. */
	private static String refusal(long adminId, User user, long enabledAdmins) {
		if (user.getId() == adminId) {
			return "This is your own account, so you can't disable it.";
		}
		if (user.getRole() == Role.ADMIN && user.isEnabled() && enabledAdmins <= 1) {
			return user.getName() + " is the only active admin, so the account can't be disabled.";
		}
		return null;
	}

	/**
	 * One account for the admin: its orders as a customer, its restaurant's slug (owners) and whether it has a rider
	 * profile (riders). {@code lockedUntil} is set while sign-in is locked after wrong passwords; {@code cantDisable}
	 * says why the account may not be disabled, or is null.
	 */
	public record Detail(User user, Page<Order> orders, String restaurantSlug, boolean rider,
			LocalDateTime lockedUntil, String cantDisable) {
	}

}
