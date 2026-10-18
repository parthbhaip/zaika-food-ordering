package com.zaika.admin;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantRepository.StatusCount;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.NotFoundException;
import com.zaika.config.AppProperties;
import com.zaika.notification.AfterCommit;
import com.zaika.notification.MailService;
import com.zaika.notification.Notification;
import com.zaika.notification.NotificationService;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;

/**
 * Restaurant moderation for the admin: the list with status counts, the review page, and the four decisions. Each
 * decision locks the restaurant row, checks the current status, changes it ({@link Restaurant#changeStatus}) and,
 * once the change is committed, emails the owner. A mail failure is logged and never undoes the decision.
 */
@Service
public class RestaurantModerationService {

	public static final int PAGE_SIZE = 20;

	private final RestaurantRepository restaurants;

	private final MenuItemRepository dishes;

	private final OrderRepository orders;

	private final MailService mail;

	private final AppProperties app;

	private final NotificationService notifications;

	RestaurantModerationService(RestaurantRepository restaurants, MenuItemRepository dishes, OrderRepository orders,
			MailService mail, AppProperties app, NotificationService notifications) {
		this.restaurants = restaurants;
		this.dishes = dishes;
		this.orders = orders;
		this.mail = mail;
		this.app = app;
		this.notifications = notifications;
	}

	/** One page of the list (owners loaded) and how many restaurants each status has, all statuses included. */
	@Transactional(readOnly = true)
	public Queue<Restaurant, RestaurantStatus> list(ModerationFilter<RestaurantStatus> filter) {
		String pattern = filter.getQ() == null ? null : RestaurantBrowseService.likePattern(filter.getQ());
		Page<Restaurant> page = restaurants.findForModeration(filter.getStatus(), pattern,
				PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
		return Queue.of(page, RestaurantStatus.values(), restaurants.countPerStatus()
			.stream()
			.collect(Collectors.toMap(StatusCount::status, StatusCount::restaurants)));
	}

	/** The review page: the restaurant (owner and cuisines loaded), its live dishes and the orders it has had. */
	@Transactional(readOnly = true)
	public Detail detail(String slug) {
		Restaurant r = restaurants.findWithOwnerBySlug(slug)
			.orElseThrow(() -> new NotFoundException("No restaurant " + slug));
		return new Detail(r, dishes.countByRestaurantIdAndArchivedFalse(r.getId()),
				orders.countByRestaurantIdAndStatusIn(r.getId(), OrderStatus.PLACED_AND_LIVE));
	}

	/**
	 * Applies {@code decision} to the restaurant and emails its owner after commit.
	 *
	 * @param reason required (and stored) for {@link Decision#REJECT} and {@link Decision#SUSPEND}, ignored otherwise
	 * @return the restaurant's name, for the confirmation message
	 * @throws NotFoundException if there is no such restaurant
	 * @throws ModerationException if the decision does not fit the current status (e.g. it was already approved)
	 */
	@Transactional
	public String decide(String slug, Decision decision, String reason) {
		Restaurant r = restaurants.findForUpdateBySlug(slug)
			.orElseThrow(() -> new NotFoundException("No restaurant " + slug));
		if (!decision.from.contains(r.getStatus())) {
			throw new ModerationException(refusal(r, decision));
		}
		r.changeStatus(decision.target, decision.target.needsReason() ? reason : null);
		notifications.notify(r.getOwner(), Notification.Kind.RESTAURANT, subject(r, decision), note(r, decision),
				decision == Decision.REJECT ? "/owner/restaurant" : "/owner");
		AfterCommit.mail(mail, r.getOwner().getEmail(), subject(r, decision), message(r, decision),
				"Moderation email (" + decision + ") for restaurant " + r.getId());
		return r.getName();
	}

	private static String refusal(Restaurant r, Decision decision) {
		String name = r.getName();
		return switch (r.getStatus()) {
			case APPROVED -> name + " is already live, so there is nothing to " + decision.verb + ".";
			case PENDING -> name + " is still waiting for review. Approve or reject it first.";
			case REJECTED -> name + " was rejected. Approve it once the owner has fixed the details.";
			case SUSPENDED -> name + " is suspended. Reactivate it instead.";
		};
	}

	private String subject(Restaurant r, Decision decision) {
		return switch (decision) {
			case APPROVE -> r.getName() + " is live on " + app.brandName();
			case REJECT -> "We couldn't approve " + r.getName() + " yet";
			case SUSPEND -> r.getName() + " is suspended on " + app.brandName();
			case REACTIVATE -> r.getName() + " is back on " + app.brandName();
		};
	}

	/** The in-app version of the email: one or two sentences. */
	private String note(Restaurant r, Decision decision) {
		return switch (decision) {
			case APPROVE -> "Customers can find it and order now. Manage orders and your menu from this panel.";
			case REJECT -> "Reason: " + Notification.sentence(r.getStatusReason()) + " Fix the details in your restaurant profile, then "
					+ "write to partners@zaika.test for another review.";
			case SUSPEND -> "Customers can't see it or order until it is reactivated. Reason: " + Notification.sentence(r.getStatusReason());
			case REACTIVATE -> "Customers can find it and order from it again.";
		};
	}

	private String message(Restaurant r, Decision decision) {
		String brand = app.brandName();
		String page = app.baseUrl() + "/r/" + r.getSlug();
		String panel = app.baseUrl() + "/owner";
		String body = switch (decision) {
			case APPROVE -> """
					Good news: %s is approved and live on %s. Customers can find it and order from it now:

					%s

					Take orders, update your menu and switch new orders on or off from your restaurant panel:

					%s
					""".formatted(r.getName(), brand, page, panel);
			case REJECT -> """
					We reviewed %s and couldn't approve it yet.

					Reason: %s

					Fix the details in your restaurant profile:

					%s

					Then reply to partners@zaika.test and we'll review it again.
					""".formatted(r.getName(), r.getStatusReason(), app.baseUrl() + "/owner/restaurant");
			case SUSPEND -> """
					We have suspended %s on %s. Customers can't see it or order from it until it is reactivated. \
					Orders already placed should still be prepared and handed over.

					Reason: %s

					To get back online, reply to partners@zaika.test. Your restaurant panel stays open:

					%s
					""".formatted(r.getName(), brand, r.getStatusReason(), panel);
			case REACTIVATE -> """
					%s is live on %s again. Customers can find it and order from it now:

					%s

					Your restaurant panel: %s
					""".formatted(r.getName(), brand, page, panel);
		};
		return "Hi " + r.getOwner().firstName() + ",\n\n" + body + "\nTeam " + brand + "\n";
	}

	/** A moderation decision: the statuses it applies to and the status it leads to. */
	public enum Decision {

		APPROVE(Set.of(RestaurantStatus.PENDING, RestaurantStatus.REJECTED), RestaurantStatus.APPROVED, "approve"),
		REJECT(Set.of(RestaurantStatus.PENDING), RestaurantStatus.REJECTED, "reject"),
		SUSPEND(Set.of(RestaurantStatus.APPROVED), RestaurantStatus.SUSPENDED, "suspend"),
		REACTIVATE(Set.of(RestaurantStatus.SUSPENDED), RestaurantStatus.APPROVED, "reactivate");

		private final Set<RestaurantStatus> from;

		private final RestaurantStatus target;

		private final String verb;

		Decision(Set<RestaurantStatus> from, RestaurantStatus target, String verb) {
			this.from = from;
			this.target = target;
			this.verb = verb;
		}

		/** True when this decision can be made on a restaurant in {@code status} (which buttons the page shows). */
		public boolean appliesTo(RestaurantStatus status) {
			return from.contains(status);
		}

	}

	/** The review page: owner and cuisines loaded. {@code orders} counts placed, not cancelled or rejected orders. */
	public record Detail(Restaurant restaurant, long dishes, long orders) {
	}

}
