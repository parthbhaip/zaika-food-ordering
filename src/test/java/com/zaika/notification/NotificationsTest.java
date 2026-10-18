package com.zaika.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaika.admin.OrderAdminService;
import com.zaika.admin.RestaurantModerationService;
import com.zaika.admin.RiderModerationService;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.delivery.DeliveryPartnerProfile;
import com.zaika.delivery.DeliveryPartnerProfileRepository;
import com.zaika.delivery.VehicleType;
import com.zaika.order.Order;
import com.zaika.order.OrderActor;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTrackingService;
import com.zaika.order.OrderWorkflow;
import com.zaika.order.PaymentMethod;
import com.zaika.payment.PaymentService;
import com.zaika.review.Review;
import com.zaika.review.ReviewService;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * Notifications: created on every order status change for the people on the order, on restaurant and rider
 * decisions and on reviews; the header bell with its unread count and 30-second poll; {@code /notifications} with
 * paging, opening one and "Mark all as read"; and the emails for order placed, order delivered and an order Zaika
 * support cancelled (read from the log-only mail sender, which masks phones and codes). Same context as
 * OrderPagesTest; the test clock says Sunday 4 Oct 2026, 19:30 IST.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:orderpages;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
		"spring.jpa.properties.hibernate.generate_statistics=true" }) // same context as OrderPagesTest (cached, not a new one)
@ActiveProfiles("test")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class NotificationsTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	OrderRepository orderRepository;

	@Autowired
	OrderWorkflow workflow;

	@Autowired
	OrderTrackingService tracking;

	@Autowired
	OrderAdminService orderAdmin;

	@Autowired
	PaymentService payments;

	@Autowired
	RestaurantModerationService restaurantModeration;

	@Autowired
	RiderModerationService riderModeration;

	@Autowired
	DeliveryPartnerProfileRepository profiles;

	@Autowired
	ReviewService reviews;

	@Autowired
	NotificationService notifications;

	@Autowired
	NotificationRepository notificationRepository;

	@Autowired
	MenuItemRepository items;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService details;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	MutableClock clock;

	User customer;

	Restaurant kitchen;

	User owner;

	MenuItem thali; // ₹199: one costs 199 + 30 + 5 + 9.95 GST = ₹243.95

	@BeforeEach
	void setUp() {
		customer = orders.customer();
		String slug = "notify-" + UUID.randomUUID().toString().substring(0, 8);
		kitchen = fixtures.restaurant(slug, "Tambda <Rassa> House").dishes("Mutton Thali").save();
		owner = users.findByEmailIgnoreCase(slug + "@fixtures.zaika.test").orElseThrow();
		thali = items.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(kitchen.getId()).get(0);
	}

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	UserDetails as(User u) {
		return details.loadUserByUsername(u.getEmail());
	}

	List<Notification> of(User u) {
		return notificationRepository.findForUser(u.getId(), PageRequest.of(0, 100)).getContent();
	}

	/** Titles newest first. */
	List<String> titles(User u) {
		return of(u).stream().map(Notification::getTitle).toList();
	}

	String page(User u, String url) throws Exception {
		return mvc.perform(get(url).with(user(as(u)))).andExpect(status().isOk()).andReturn().getResponse()
			.getContentAsString();
	}

	void move(Order order, OrderStatus target, OrderActor actor) {
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findForUpdateByCode(order.getCode())
			.orElseThrow(), target, actor));
	}

	User rider() {
		return orders.rider();
	}

	// ---------------------------------------------------------------- order status changes

	@Test
	void everyStepOfAnOrderTellsTheCustomerAndTheKitchenHearsAboutTheNewOrder(CapturedOutput output) {
		Order order = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.COD);
		String code = orderRepository.findById(order.getId()).orElseThrow().getCode();
		User rider = rider();
		move(order, OrderStatus.PLACED, OrderActor.system());
		OrderActor restaurant = OrderActor.restaurant(owner.getId());
		move(order, OrderStatus.ACCEPTED, restaurant);
		move(order, OrderStatus.PREPARING, restaurant);
		tx.executeWithoutResult(s -> orderRepository.findForUpdateByCode(code).orElseThrow()
			.assignRider(users.findById(rider.getId()).orElseThrow()));
		move(order, OrderStatus.READY_FOR_PICKUP, restaurant);
		move(order, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(rider.getId()));
		move(order, OrderStatus.DELIVERED, OrderActor.rider(rider.getId()));

		assertThat(titles(customer)).containsExactly("Delivered. Enjoy your meal!", "On the way", "Packed and ready",
				"Your food is being prepared", "Tambda <Rassa> House accepted your order", "Order placed");
		List<Notification> mine = of(customer);
		assertThat(mine).allMatch(Notification::isUnread);
		assertThat(mine.get(0).getLink()).isEqualTo("/orders/" + code + "#review");
		assertThat(mine.get(1).getBody()).isEqualTo("Sagar picked up " + code
				+ " from Tambda <Rassa> House. Keep your delivery code handy: it's on the tracking page.");
		assertThat(mine.get(4).getBody()).isEqualTo(code + " is confirmed. Expected at your door by 8:00 PM.");
		assertThat(mine.get(5).getLink()).isEqualTo("/orders/" + code + "/track");

		assertThat(titles(owner)).containsExactly("New order " + code);
		assertThat(of(owner).get(0).getBody()).isEqualTo("1 dish · ₹243.95 · Cash on delivery. Accept it to start cooking.");
		assertThat(of(owner).get(0).getLink()).isEqualTo("/owner/orders/" + code);
		assertThat(titles(rider)).containsExactly("Ready for pickup");
		assertThat(of(rider).get(0).getBody()).isEqualTo(code + " is packed at Tambda <Rassa> House, Rajarampuri. "
				+ "Head over to pick it up.");

		// emails: order placed (bill, address, tracking link) and delivered (rating link), nothing to the kitchen
		assertThat(output.getOut()).contains("subject=\"Order " + code + " placed with Tambda <Rassa> House\"",
				"1 × Mutton Thali  ₹199.00", "Delivery fee  ₹30.00", "Total  ₹243.95",
				"Cash on delivery: please keep ₹243.95 ready.",
				"Delivering to Flat 4B, Shree Apartments, Rajarampuri 5th Lane, near Ganesh Mandir, Kolhapur 416008",
				"Track your order: http://localhost:8080/orders/" + code + "/track",
				"subject=\"Delivered: your order from Tambda <Rassa> House\"",
				"Your order " + code + " from Tambda <Rassa> House was delivered at 7:30 PM. Enjoy your meal!",
				"Total ₹243.95, paid in cash.", "http://localhost:8080/orders/" + code + "#review");
		assertThat(output.getOut()).doesNotContain("to=" + owner.getEmail());
	}

	@Test
	void aRolledBackChangeLeavesNoNotificationAndSendsNoMail(CapturedOutput output) {
		Order order = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.COD);
		tx.executeWithoutResult(s -> {
			workflow.transition(orderRepository.findById(order.getId()).orElseThrow(), OrderStatus.PLACED,
					OrderActor.system());
			s.setRollbackOnly();
		});
		assertThat(of(customer)).isEmpty();
		assertThat(of(owner)).isEmpty();
		assertThat(output.getOut()).doesNotContain("to=" + customer.getEmail());
	}

	@Test
	void cancellationsAndRejectionsTellTheOtherSide() {
		// the customer cancels while PLACED: the kitchen hears about it, with the reason
		Order cancelled = orders.place(customer.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.PLACED);
		tracking.cancel(customer.getId(), cancelled.getCode(), "Ordered by mistake");
		assertThat(of(owner).get(0).getTitle()).isEqualTo("Order " + cancelled.getCode() + " cancelled");
		assertThat(of(owner).get(0).getBody())
			.isEqualTo("The customer cancelled it before you accepted it. Reason: Ordered by mistake.");

		// cancelling before payment never reached the kitchen: nothing for the owner
		long ownerCount = of(owner).size();
		orders.place(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE, OrderStatus.CANCELLED);
		assertThat(of(owner)).hasSize((int) ownerCount);

		// the kitchen rejects a paid order: the customer learns why and about the refund
		Order rejected = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE);
		tx.executeWithoutResult(s -> workflow.confirmPayment(orderRepository.findById(rejected.getId()).orElseThrow()));
		String code = orderRepository.findById(rejected.getId()).orElseThrow().getCode();
		tx.executeWithoutResult(s -> workflow.transition(orderRepository.findForUpdateByCode(code).orElseThrow(),
				OrderStatus.REJECTED, OrderActor.restaurant(owner.getId()), "Out of mutton today."));
		Notification latest = of(customer).get(0);
		assertThat(latest.getTitle()).isEqualTo("Tambda <Rassa> House couldn't take your order");
		assertThat(latest.getBody()).isEqualTo(code + " was not accepted. Reason: Out of mutton today. Your ₹243.95 "
				+ "will be refunded to your original payment method in 5-7 working days.");
		assertThat(latest.getLink()).isEqualTo("/orders/" + code);

		// an online order left unpaid for 15 minutes expires: the customer is told nothing was charged
		Order unpaid = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE);
		String unpaidCode = orderRepository.findById(unpaid.getId()).orElseThrow().getCode();
		clock.advance(Duration.ofMinutes(16));
		payments.expireUnpaid();
		latest = of(customer).get(0);
		assertThat(latest.getTitle()).isEqualTo("Payment time ran out");
		assertThat(latest.getBody()).isEqualTo(unpaidCode + " from Tambda <Rassa> House was cancelled because the "
				+ "payment wasn't completed in 15 minutes. Nothing was charged.");
	}

	@Test
	void zaikaSupportCancellingTellsAndEmailsCustomerKitchenAndRider(CapturedOutput output) {
		User admin = users.save(new User("Asha Admin", "a-" + UUID.randomUUID() + "@orders.zaika.test", "9822012345", "hash",
				Role.ADMIN));
		Order paid = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE);
		tx.executeWithoutResult(s -> workflow.confirmPayment(orderRepository.findById(paid.getId()).orElseThrow()));
		String code = orderRepository.findById(paid.getId()).orElseThrow().getCode();
		move(paid, OrderStatus.ACCEPTED, OrderActor.restaurant(owner.getId()));
		move(paid, OrderStatus.PREPARING, OrderActor.restaurant(owner.getId()));
		move(paid, OrderStatus.READY_FOR_PICKUP, OrderActor.restaurant(owner.getId()));
		User rider = rider();
		tx.executeWithoutResult(s -> orderRepository.findForUpdateByCode(code).orElseThrow()
			.assignRider(users.findById(rider.getId()).orElseThrow()));
		move(paid, OrderStatus.OUT_FOR_DELIVERY, OrderActor.rider(rider.getId()));

		orderAdmin.cancel(code, admin.getId(), "Rider had a flat tyre and the food went cold");

		Notification toCustomer = of(customer).get(0);
		assertThat(toCustomer.getTitle()).isEqualTo("Zaika support cancelled your order");
		assertThat(toCustomer.getBody()).isEqualTo(code + " from Tambda <Rassa> House was cancelled. Reason: Rider had "
				+ "a flat tyre and the food went cold. Your ₹243.95 will be refunded to your original payment method "
				+ "in 5-7 working days.");
		assertThat(of(owner).get(0).getBody()).isEqualTo(code + " was cancelled by Zaika support. Reason: Rider had a "
				+ "flat tyre and the food went cold. Stop preparing it; its delivery partner has been told.");
		assertThat(of(rider).get(0).getTitle()).isEqualTo("Delivery " + code + " cancelled");
		assertThat(of(rider).get(0).getBody()).endsWith("Take the food back to Tambda <Rassa> House.");

		String log = output.getOut();
		assertThat(log).contains("to=" + customer.getEmail() + " subject=\"Your order " + code + " was cancelled\"",
				"Reason: Rider had a flat tyre and the food went cold",
				"Your ₹243.95 will be refunded to your original payment method in 5-7 working days.",
				"Order details: http://localhost:8080/orders/" + code,
				"to=" + owner.getEmail() + " subject=\"Order " + code + " was cancelled\"",
				"Order: http://localhost:8080/owner/orders/" + code,
				"to=" + rider.getEmail() + " subject=\"Delivery " + code + " was cancelled\"",
				"Your dashboard: http://localhost:8080/partner");
	}

	@Test
	void supportCancellingAnUnpaidOrderTellsOnlyTheCustomer(CapturedOutput output) {
		User admin = users.save(new User("Asha Admin", "a-" + UUID.randomUUID() + "@orders.zaika.test", "9822012345", "hash",
				Role.ADMIN));
		Order unpaid = orders.create(customer.getId(), thali.getId(), 1, PaymentMethod.ONLINE);
		String code = orderRepository.findById(unpaid.getId()).orElseThrow().getCode();
		orderAdmin.cancel(code, admin.getId(), "Duplicate order");
		assertThat(of(customer).get(0).getBody()).endsWith("Reason: Duplicate order. Nothing was charged.");
		assertThat(of(owner)).isEmpty();
		assertThat(output.getOut()).doesNotContain("to=" + owner.getEmail());
	}

	// ---------------------------------------------------------------- approvals and reviews

	@Test
	void restaurantAndRiderDecisionsNotifyTheirAccounts() {
		String slug = "pending-" + UUID.randomUUID().toString().substring(0, 8);
		fixtures.restaurant(slug, "Kolhapuri Kitchen").status(RestaurantStatus.PENDING).save();
		User pendingOwner = users.findByEmailIgnoreCase(slug + "@fixtures.zaika.test").orElseThrow();
		restaurantModeration.decide(slug, RestaurantModerationService.Decision.REJECT, "Add a menu with prices");
		restaurantModeration.decide(slug, RestaurantModerationService.Decision.APPROVE, null);
		assertThat(titles(pendingOwner)).containsExactly("Kolhapuri Kitchen is live on Zaika",
				"We couldn't approve Kolhapuri Kitchen yet");
		assertThat(of(pendingOwner).get(1).getBody()).isEqualTo("Reason: Add a menu with prices. Fix the details in "
				+ "your restaurant profile, then write to partners@zaika.test for another review.");
		assertThat(of(pendingOwner).get(1).getLink()).isEqualTo("/owner/restaurant");
		assertThat(of(pendingOwner).get(0).getLink()).isEqualTo("/owner");

		User rider = rider();
		profiles.save(new DeliveryPartnerProfile(rider, VehicleType.BIKE, "MH09AB1234", "Rajarampuri"));
		riderModeration.decide(rider.getId(), RiderModerationService.Decision.APPROVE, null);
		riderModeration.decide(rider.getId(), RiderModerationService.Decision.SUSPEND, "Three late deliveries");
		assertThat(titles(rider)).containsExactly("Your Zaika rider account is suspended",
				"You're approved to ride with Zaika");
		assertThat(of(rider).get(0).getBody()).isEqualTo("Reason: Three late deliveries. A delivery you have already "
				+ "picked up can still be finished.");
		assertThat(of(rider)).allMatch(n -> n.getLink().equals("/partner"));
	}

	@Test
	void aNewReviewTellsTheOwnerAndAReplyTellsTheCustomer() {
		Order order = orders.place(customer.getId(), thali.getId(), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
		Review review = reviews.post(customer.getId(), order.getCode(), 4, "Rassa was <b>fiery</b>");
		assertThat(of(owner).get(0).getTitle()).isEqualTo("New 4-star review");
		assertThat(of(owner).get(0).getBody())
			.isEqualTo("Riya S. rated order " + order.getCode() + ": “Rassa was <b>fiery</b>”");
		assertThat(of(owner).get(0).getLink()).isEqualTo("/owner/reviews");

		reviews.reply(owner.getId(), review.getId(), "Thank you, come again!");
		Notification reply = of(customer).get(0);
		assertThat(reply.getTitle()).isEqualTo("Tambda <Rassa> House replied to your review");
		assertThat(reply.getBody()).isEqualTo("“Thank you, come again!”");
		assertThat(reply.getLink()).isEqualTo("/orders/" + order.getCode() + "#review");
	}

	// ---------------------------------------------------------------- bell, list, open, mark all read

	@Test
	void signedInPagesShowTheBellWithTheUnreadCountAndPollEvery30Seconds() throws Exception {
		String empty = page(customer, "/");
		assertThat(empty).contains("class=\"header-icon-btn header-bell\"", "href=\"/notifications\"",
				"<span class=\"bell-count is-empty\"><span>0</span>",
				"hx-get=\"/notifications/badge?n=0\" hx-trigger=\"every 30s\" hx-swap=\"outerHTML\"");

		notifications.notify(customer, Notification.Kind.ORDER, "Order placed", "Body", "/orders");
		notifications.notify(customer, Notification.Kind.ORDER, "Order accepted", "Body", "/orders");
		String two = page(customer, "/restaurants");
		assertThat(two).contains("<span class=\"bell-count\"><span>2</span><span class=\"visually-hidden\"> unread</span>",
				"hx-get=\"/notifications/badge?n=2\"");
		// owners and riders get the bell too; visitors do not
		assertThat(page(owner, "/owner")).contains("header-bell");
		assertThat(mvc.perform(get("/").with(anonymous())).andReturn().getResponse().getContentAsString())
			.doesNotContain("header-bell", "/notifications/badge");
	}

	@Test
	void theBadgePollAnswers204WhileTheCountIsUnchangedAndTheNewCountOtherwise() throws Exception {
		notifications.notify(customer, Notification.Kind.ORDER, "Order placed", "Body", "/orders");
		mvc.perform(get("/notifications/badge?n=1").header("HX-Request", "true").with(user(as(customer))))
			.andExpect(status().isNoContent());
		notifications.notify(customer, Notification.Kind.ORDER, "Order accepted", "Body", "/orders");
		String update = mvc.perform(get("/notifications/badge?n=1").header("HX-Request", "true")
			.with(user(as(customer)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(update).contains("id=\"notification-poll\"", "hx-get=\"/notifications/badge?n=2\"",
				"<span id=\"notification-count\" hx-swap-oob=\"innerHTML\"><span class=\"bell-count\"><span>2</span>")
			.doesNotContain("<html", "site-header");
		// 100 unread show as 99+
		for (int i = 0; i < 98; i++) {
			notifications.notify(customer, Notification.Kind.ORDER, "Update " + i, "Body", "/orders");
		}
		assertThat(mvc.perform(get("/notifications/badge?n=2").header("HX-Request", "true").with(user(as(customer))))
			.andReturn().getResponse().getContentAsString()).contains("<span>99+</span>");
		mvc.perform(get("/notifications/badge?n=0").with(anonymous())).andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void theListShowsNewestFirstMarksUnreadAndEscapesText() throws Exception {
		assertThat(page(customer, "/notifications")).contains("<title>Notifications | Zaika</title>",
				"No notifications yet").doesNotContain("Mark all as read");

		notifications.notify(customer, Notification.Kind.ORDER, "Older", "Older body", "/orders");
		clock.advance(Duration.ofMinutes(5));
		notifications.notify(customer, Notification.Kind.REVIEW, "Kitchen <b>replied</b>", "“<script>x</script>”",
				"/orders");
		String html = page(customer, "/notifications");
		assertThat(html).contains("2 unread", "Mark all as read", "Kitchen &lt;b&gt;replied&lt;/b&gt;",
				"&lt;script&gt;x&lt;/script&gt;", "<span class=\"visually-hidden\">Unread: </span>", "5 min ago",
				"icons.svg#star", "icons.svg#receipt").doesNotContain("<b>replied</b>", "<script>x");
		assertThat(html.indexOf("Kitchen &lt;b&gt;")).isLessThan(html.indexOf("Older"));
		// someone else's notifications never show
		assertThat(page(owner, "/notifications")).doesNotContain("Older body");
	}

	@Test
	void openingOneMarksItReadAndGoesWhereItPoints() throws Exception {
		Notification n = notifications.notify(customer, Notification.Kind.ORDER, "Order placed", "Body",
				"/orders/ZK-26-000001/track");
		notifications.notify(customer, Notification.Kind.ORDER, "Other", "Body", "/orders");
		mvc.perform(post("/notifications/" + n.getId() + "/open").with(csrf()).with(user(as(customer))))
			.andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/orders/ZK-26-000001/track"));
		assertThat(notificationRepository.findById(n.getId()).orElseThrow().isUnread()).isFalse();
		assertThat(notifications.unreadCount(customer.getId())).isEqualTo(1);
		assertThat(page(customer, "/notifications")).contains("1 unread");

		// another account's notification is a 404 and stays unread; anonymous and CSRF-less posts are refused
		Notification theirs = notifications.notify(owner, Notification.Kind.ORDER, "New order", "Body", "/owner/orders");
		mvc.perform(post("/notifications/" + theirs.getId() + "/open").with(csrf()).with(user(as(customer))))
			.andExpect(status().isNotFound());
		mvc.perform(post("/notifications/999999999/open").with(csrf()).with(user(as(customer))))
			.andExpect(status().isNotFound());
		mvc.perform(post("/notifications/" + theirs.getId() + "/open").with(user(as(owner))))
			.andExpect(status().isForbidden());
		mvc.perform(post("/notifications/" + theirs.getId() + "/open").with(csrf()).with(anonymous()))
			.andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
		assertThat(notificationRepository.findById(theirs.getId()).orElseThrow().isUnread()).isTrue();
	}

	@Test
	void markAllReadClearsOnlyYourOwnCount() throws Exception {
		notifications.notify(customer, Notification.Kind.ORDER, "One", "Body", "/orders");
		notifications.notify(customer, Notification.Kind.ORDER, "Two", "Body", "/orders");
		notifications.notify(owner, Notification.Kind.ORDER, "Theirs", "Body", "/owner/orders");
		mvc.perform(post("/notifications/read-all").with(csrf()).with(user(as(customer))))
			.andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/notifications"))
			.andExpect(flash().attribute("flashSuccess", "Marked 2 notifications as read."));
		assertThat(notifications.unreadCount(customer.getId())).isZero();
		assertThat(notifications.unreadCount(owner.getId())).isEqualTo(1);
		assertThat(page(customer, "/notifications")).contains("You’re all caught up.").doesNotContain("Mark all as read",
				"is-unread");
		mvc.perform(post("/notifications/read-all").with(csrf()).with(user(as(customer))))
			.andExpect(flash().attribute("flashSuccess", "You're all caught up."));
		mvc.perform(post("/notifications/read-all").with(user(as(customer)))).andExpect(status().isForbidden());
		mvc.perform(get("/notifications").with(anonymous())).andExpect(redirectedUrl("/login"));
	}

	@Test
	void theListPagesBy20WithCanonicalUrls() throws Exception {
		for (int i = 1; i <= 21; i++) {
			notifications.notify(customer, Notification.Kind.ORDER, "Update number " + i, "Body", "/orders");
			clock.advance(Duration.ofSeconds(1));
		}
		String first = page(customer, "/notifications");
		assertThat(first).contains("Update number 21", "Update number 2<", "href=\"/notifications?page=2\"")
			.doesNotContain("Update number 1<");
		assertThat(page(customer, "/notifications?page=2")).contains("Update number 1<")
			.doesNotContain("Update number 2<");
		for (String url : new String[] { "/notifications?page=1", "/notifications?page=0", "/notifications?page=x" }) {
			mvc.perform(get(url).with(user(as(customer)))).andExpect(redirectedUrl("/notifications"));
		}
		mvc.perform(get("/notifications?page=9").with(user(as(customer))))
			.andExpect(redirectedUrl("/notifications?page=2"));
		mvc.perform(get("/notifications?page=2").with(user(as(owner)))).andExpect(redirectedUrl("/notifications"));
	}

	// ---------------------------------------------------------------- the log-only mail sender

	@Test
	void theLogOnlyMailSenderMasksPhoneNumbersAndOneTimeCodes() {
		assertThat(LoggingMailService.mask("Call +91 98765 43210 or 9822012345 or 98220-12345."))
			.isEqualTo("Call ******** 10 or ******** 45 or ******** 45.");
		assertThat(LoggingMailService.mask("Your delivery code is 4821. OTP: 093411, code 0042"))
			.isEqualTo("Your delivery code is ****. OTP: ****, code ****");
		String link = "http://localhost:8080/reset-password?token=Ab-9876543210_xY9822012345";
		assertThat(LoggingMailService.mask("Open " + link)).isEqualTo("Open " + link);
		assertThat(LoggingMailService.mask("Order ZK-26-000123, total ₹1,23,456.50, 2026"))
			.isEqualTo("Order ZK-26-000123, total ₹1,23,456.50, 2026");
	}

	@Test
	void notificationsLinkOnlyToPagesOfThisSiteAndFitTheirColumns() {
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifications.notify(customer,
				Notification.Kind.ORDER, "Hi", "Body", "https://evil.example"))
			.isInstanceOf(IllegalArgumentException.class);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> notifications.notify(customer,
				Notification.Kind.ORDER, "Hi", "Body", "//evil.example"))
			.isInstanceOf(IllegalArgumentException.class);
		Notification longOne = notifications.notify(customer, Notification.Kind.REVIEW, "T".repeat(200),
				"B".repeat(600), "/orders");
		assertThat(longOne.getTitle()).hasSize(Notification.MAX_TITLE).endsWith("…");
		assertThat(longOne.getBody()).hasSize(Notification.MAX_BODY).endsWith("…");
	}

}
