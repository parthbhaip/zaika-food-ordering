package com.zaika.admin;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.common.NotFoundException;
import com.zaika.config.AppProperties;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.DeliveryPartnerProfileRepository.StatusCount;
import com.zaika.delivery.DeliveryService;
import com.zaika.delivery.RiderEarningRepository;
import com.zaika.delivery.RiderStatus;
import com.zaika.notification.AfterCommit;
import com.zaika.notification.MailService;
import com.zaika.notification.Notification;
import com.zaika.notification.NotificationService;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.user.User;

/**
 * Rider moderation for the admin: the list with status counts, the review page and the two decisions. Approving lets
 * the rider go online; suspending (with a reason the rider sees) takes them offline at once, but a delivery already
 * on the way can still be finished. Each decision locks the rider's profile row and, once committed, emails them.
 */
@Service
public class RiderModerationService {

	public static final int PAGE_SIZE = 20;

	private final DeliveryPartnerProfileRepository profiles;

	private final RiderEarningRepository earnings;

	private final OrderRepository orders;

	private final MailService mail;

	private final AppProperties app;

	private final NotificationService notifications;

	RiderModerationService(DeliveryPartnerProfileRepository profiles, RiderEarningRepository earnings,
			OrderRepository orders, MailService mail, AppProperties app, NotificationService notifications) {
		this.notifications = notifications;
		this.profiles = profiles;
		this.earnings = earnings;
		this.orders = orders;
		this.mail = mail;
		this.app = app;
	}

	/** One page of the list (accounts loaded) and how many riders each status has. */
	@Transactional(readOnly = true)
	public Queue<DeliveryPartnerProfile, RiderStatus> list(ModerationFilter<RiderStatus> filter) {
		String pattern = filter.getQ() == null ? null : RestaurantBrowseService.likePattern(filter.getQ());
		Page<DeliveryPartnerProfile> page = profiles.findForModeration(filter.getStatus(), pattern,
				PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
		return Queue.of(page, RiderStatus.values(),
				profiles.countPerStatus().stream().collect(Collectors.toMap(StatusCount::status, StatusCount::riders)));
	}

	/** The review page of the rider whose account id is {@code userId}. */
	@Transactional(readOnly = true)
	public Detail detail(long userId) {
		DeliveryPartnerProfile p = profiles.findByUserId(userId)
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + userId));
		Order active = orders.findByRiderIdAndStatusIn(userId, DeliveryService.ACTIVE).stream().findFirst().orElse(null);
		return new Detail(p, earnings.countByRiderId(userId), active);
	}

	/**
	 * Applies {@code decision} and emails the rider after commit.
	 *
	 * @param reason required (and stored) for {@link Decision#SUSPEND}, ignored for {@link Decision#APPROVE}
	 * @return the rider's name, for the confirmation message
	 * @throws NotFoundException   if the account has no rider profile
	 * @throws ModerationException if the decision does not fit the current status
	 */
	@Transactional
	public String decide(long userId, Decision decision, String reason) {
		DeliveryPartnerProfile p = profiles.findForUpdateByUserId(userId)
			.orElseThrow(() -> new NotFoundException("No rider profile for user " + userId));
		User rider = p.getUser();
		if (!decision.appliesTo(p.getStatus())) {
			throw new ModerationException(rider.getName() + (p.getStatus() == RiderStatus.APPROVED
					? " is already approved." : " is already suspended. Approve them to let them ride again."));
		}
		boolean wasSuspended = p.getStatus() == RiderStatus.SUSPENDED;
		p.changeStatus(decision.target, decision == Decision.SUSPEND ? reason : null);
		notifications.notify(rider, Notification.Kind.DELIVERY, subject(decision, wasSuspended),
				decision == Decision.APPROVE ? "Go online from your dashboard whenever you're ready to take orders."
						: "Reason: " + Notification.sentence(p.getStatusReason())
								+ " A delivery you have already picked up can still be finished.",
				"/partner");
		AfterCommit.mail(mail, rider.getEmail(), subject(decision, wasSuspended), message(p, decision, wasSuspended),
				"Rider email (" + decision + ") for user " + userId);
		return rider.getName();
	}

	private String subject(Decision decision, boolean wasSuspended) {
		return switch (decision) {
			case APPROVE -> wasSuspended ? "You can ride with " + app.brandName() + " again"
					: "You're approved to ride with " + app.brandName();
			case SUSPEND -> "Your " + app.brandName() + " rider account is suspended";
		};
	}

	private String message(DeliveryPartnerProfile p, Decision decision, boolean wasSuspended) {
		String brand = app.brandName();
		String panel = app.baseUrl() + "/partner";
		String body = switch (decision) {
			case APPROVE -> """
					%s Go online from your rider dashboard whenever you're ready to take orders:

					%s
					""".formatted(wasSuspended ? "Your rider account is active again."
					: "Welcome aboard: your rider account on " + brand + " is approved.", panel);
			case SUSPEND -> """
					We have suspended your rider account on %s, so you can't go online or take new orders. \
					A delivery you have already picked up can still be finished.

					Reason: %s

					To ride again, reply to partners@zaika.test. Your rider dashboard stays open:

					%s
					""".formatted(brand, p.getStatusReason(), panel);
		};
		return "Hi " + p.getUser().firstName() + ",\n\n" + body + "\nTeam " + brand + "\n";
	}

	/** A rider decision: the statuses it applies to and the status it leads to. */
	public enum Decision {

		APPROVE(Set.of(RiderStatus.PENDING, RiderStatus.SUSPENDED), RiderStatus.APPROVED),
		SUSPEND(Set.of(RiderStatus.PENDING, RiderStatus.APPROVED), RiderStatus.SUSPENDED);

		private final Set<RiderStatus> from;

		private final RiderStatus target;

		Decision(Set<RiderStatus> from, RiderStatus target) {
			this.from = from;
			this.target = target;
		}

		/** True when this decision can be made on a rider in {@code status} (which buttons the page shows). */
		public boolean appliesTo(RiderStatus status) {
			return from.contains(status);
		}

	}

	/** The review page: the profile with its account, completed deliveries and the delivery on the way, if any. */
	public record Detail(DeliveryPartnerProfile profile, long deliveries, Order active) {
	}

}
