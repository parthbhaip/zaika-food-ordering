package com.zaika.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.zaika.catalog.MenuItemRepository;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.order.Order;
import com.zaika.order.OrderStatus;
import com.zaika.order.PaymentMethod;
import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.support.CatalogFixtures;
import com.zaika.support.MutableClock;
import com.zaika.support.OrderFixtures;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/**
 * T43: rating a delivered order, the restaurant's rating, reviews on the restaurant page and its reviews page, the
 * owner's one reply and the admin hiding a review. Every test uses restaurants of its own.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:reviews;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReviewsTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	CatalogFixtures fixtures;

	@Autowired
	OrderFixtures orders;

	@Autowired
	MenuItemRepository menuItems;

	@Autowired
	RestaurantRepository restaurants;

	@Autowired
	ReviewRepository reviewRows;

	@Autowired
	ReviewService reviews;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Autowired
	MutableClock clock;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@AfterEach
	void resetClock() {
		clock.reset();
	}

	// ---------------------------------------------------------------- helpers

	Restaurant restaurant(String average, int count) {
		String id = UUID.randomUUID().toString().substring(0, 8);
		return fixtures.restaurant("rv-" + id, "Kitchen " + id).rated(average, count).dishes("Misal Pav").save();
	}

	Restaurant reload(Restaurant r) {
		return restaurants.findById(r.getId()).orElseThrow();
	}

	Order delivered(User customer, Restaurant r) {
		return orders.place(customer.getId(), dish(r), 1, PaymentMethod.COD, OrderStatus.DELIVERED);
	}

	long dish(Restaurant r) {
		return menuItems.findByRestaurantIdAndArchivedFalseOrderBySortOrderAscIdAsc(r.getId()).getFirst().getId();
	}

	/** A delivered order of a new customer, rated through the service; the clock moves a minute first. */
	Review review(Restaurant r, int stars, String comment) {
		clock.advance(Duration.ofMinutes(1));
		User customer = orders.customer();
		return reviews.post(customer.getId(), delivered(customer, r).getCode(), stars, comment);
	}

	/** The review's customer, loaded (a review keeps a lazy reference). */
	User customerOf(Review rv) {
		return users.findById(rv.getCustomer().getId()).orElseThrow();
	}

	RequestPostProcessor as(User u) {
		return user(userDetails.loadUserByUsername(u.getEmail()));
	}

	RequestPostProcessor asOwner(Restaurant r) {
		return user(userDetails.loadUserByUsername(r.getSlug() + "@fixtures.zaika.test"));
	}

	RequestPostProcessor asAdmin() {
		return as(users.save(new User("Aditi Kulkarni", "admin-" + UUID.randomUUID() + "@zaika.test", "9890011223",
				"hash", Role.ADMIN)));
	}

	MockHttpServletRequestBuilder rate(Order order, String stars, String comment) {
		MockHttpServletRequestBuilder post = post("/orders/" + order.getCode() + "/review").with(csrf());
		if (stars != null) {
			post.param("stars", stars);
		}
		return comment == null ? post : post.param("comment", comment);
	}

	String html(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request)
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
	}

	static int count(String html, String needle) {
		return html.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
	}

	// ---------------------------------------------------------------- customer: rate a delivered order

	@Test
	void aDeliveredOrderOffersTheRatingFormAndALiveOneDoesNot() throws Exception {
		Restaurant r = restaurant("4.20", 10);
		User customer = orders.customer();
		Order done = delivered(customer, r);
		String page = html(get("/orders/" + done.getCode()).with(as(customer)));
		assertThat(page).contains("How was your food?")
			.contains("action=\"/orders/" + done.getCode() + "/review\" method=\"post\"")
			.contains("name=\"_csrf\"")
			.contains("<legend class=\"field-label\">Your rating</legend>")
			.contains("1 star, Poor").contains("5 stars, Loved it")
			.contains("maxlength=\"500\"");
		assertThat(count(page, "type=\"radio\" name=\"stars\"")).isEqualTo(5);

		Order live = orders.place(customer.getId(), dish(r), 1, PaymentMethod.COD, OrderStatus.PREPARING);
		assertThat(html(get("/orders/" + live.getCode()).with(as(customer))))
			.doesNotContain("How was your food?").doesNotContain("/review\"");
	}

	@Test
	void postingAReviewSavesItAndMovesTheRatingFromItsStoredAverage() throws Exception {
		Restaurant r = restaurant("4.50", 120); // seeded rating, no review rows
		User customer = orders.customer();
		Order order = delivered(customer, r);

		mvc.perform(rate(order, "1", "  Too salty <b>today</b>  ").with(as(customer)))
			.andExpect(redirectedUrl("/orders/" + order.getCode() + "#review"))
			.andExpect(flash().attribute("flashSuccess", "Thanks for rating " + r.getName() + "! Your review is now on its page."));

		Restaurant after = reload(r);
		assertThat(after.getRatingAverage()).isEqualByComparingTo("4.47"); // (4.50 x 120 + 1) / 121
		assertThat(after.getRatingCount()).isEqualTo(121);
		Review saved = reviewRows.findByOrderId(order.getId()).orElseThrow();
		assertThat(saved.getStars()).isOne();
		assertThat(saved.getComment()).isEqualTo("Too salty <b>today</b>");
		assertThat(saved.isHidden()).isFalse();

		String page = html(get("/orders/" + order.getCode()).with(as(customer)));
		assertThat(page).contains("Your review")
			.contains("aria-label=\"Rated 1 out of 5\"")
			.contains("Too salty &lt;b&gt;today&lt;/b&gt;")
			.contains("Posted 4 Oct 2026")
			.doesNotContain("How was your food?");
	}

	@Test
	void anOrderIsRatedOnlyOnce() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		User customer = orders.customer();
		Order order = delivered(customer, r);
		mvc.perform(rate(order, "5", null).with(as(customer))).andExpect(redirectedUrl("/orders/" + order.getCode() + "#review"));

		mvc.perform(rate(order, "1", "Changed my mind").with(as(customer)))
			.andExpect(redirectedUrl("/orders/" + order.getCode() + "#review"))
			.andExpect(flash().attribute("flashError", "You've already rated this order. Thanks for the feedback!"));
		// an invalid second try gets the same answer, not the form
		mvc.perform(rate(order, null, "Changed my mind").with(as(customer)))
			.andExpect(redirectedUrl("/orders/" + order.getCode()))
			.andExpect(flash().attribute("flashError", "You've already rated this order. Thanks for the feedback!"));

		Restaurant after = reload(r);
		assertThat(after.getRatingAverage()).isEqualByComparingTo("4.25"); // (12 + 5) / 4, counted once
		assertThat(after.getRatingCount()).isEqualTo(4);
		assertThat(reviewRows.findByOrderId(order.getId()).orElseThrow().getStars()).isEqualTo(5);
		assertThat(reviewRows.findAll().stream().filter(rv -> rv.getOrder().getId().equals(order.getId()))).hasSize(1);
	}

	@Test
	void ordersThatWereNotDeliveredCannotBeRated() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		User customer = orders.customer();
		for (OrderStatus status : new OrderStatus[] { OrderStatus.PLACED, OrderStatus.OUT_FOR_DELIVERY,
				OrderStatus.CANCELLED, OrderStatus.REJECTED }) {
			Order order = orders.place(customer.getId(), dish(r), 1, PaymentMethod.COD, status);
			mvc.perform(rate(order, "5", "Great").with(as(customer)))
				.andExpect(redirectedUrl("/orders/" + order.getCode() + "#review"))
				.andExpect(flash().attribute("flashError", "You can rate this order once it's delivered."));
			mvc.perform(rate(order, null, null).with(as(customer)))
				.andExpect(redirectedUrl("/orders/" + order.getCode()))
				.andExpect(flash().attribute("flashError", "You can rate this order once it's delivered."));
			assertThat(reviewRows.findByOrderId(order.getId())).isEmpty();
		}
		assertThat(reload(r).getRatingCount()).isEqualTo(3);
	}

	@Test
	void invalidReviewsShowInlineErrorsAndKeepTheInput() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		User customer = orders.customer();
		Order order = delivered(customer, r);

		String noStars = mvc.perform(rate(order, null, "<i>Lovely</i> misal").with(as(customer)))
			.andExpect(status().isOk())
			.andExpect(view().name("order/detail"))
			.andReturn().getResponse().getContentAsString().replaceAll("\\s+", " ");
		assertThat(noStars).contains("Choose a rating from 1 to 5 stars.")
			.contains("star-field has-error")
			.contains("aria-describedby=\"stars-error\"")
			.contains("&lt;i&gt;Lovely&lt;/i&gt; misal</textarea>")
			.doesNotContain("<i>Lovely</i>");

		for (String stars : new String[] { "0", "6", "-1" }) {
			assertThat(html(rate(order, stars, null).with(as(customer)))).contains("Choose a rating from 1 to 5 stars.");
		}
		assertThat(html(rate(order, "four", null).with(as(customer)))).contains("star-field has-error");

		String tooLong = html(rate(order, "4", "a".repeat(501)).with(as(customer)));
		assertThat(tooLong).contains("Keep your review under 500 characters.")
			.contains("aria-invalid=\"true\"")
			.contains("value=\"4\" checked"); // the chosen stars stay chosen
		assertThat(reviewRows.findByOrderId(order.getId())).isEmpty();
		assertThat(reload(r).getRatingCount()).isEqualTo(3);

		mvc.perform(rate(order, "4", "a".repeat(500)).with(as(customer)))
			.andExpect(redirectedUrl("/orders/" + order.getCode() + "#review"));
		assertThat(reviewRows.findByOrderId(order.getId()).orElseThrow().getComment()).hasSize(500);
	}

	@Test
	void aBlankCommentIsARatingWithoutText() throws Exception {
		Restaurant r = restaurant("0", 0);
		User customer = orders.customer();
		Order order = delivered(customer, r);
		mvc.perform(rate(order, "3", "   ").with(as(customer))).andExpect(status().is3xxRedirection());
		assertThat(reviewRows.findByOrderId(order.getId()).orElseThrow().getComment()).isNull();
		Restaurant after = reload(r);
		assertThat(after.getRatingAverage()).isEqualByComparingTo("3.00");
		assertThat(after.getRatingCount()).isOne();
	}

	@Test
	void onlyTheCustomerWhoOrderedCanRateIt() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		User customer = orders.customer();
		Order order = delivered(customer, r);

		User stranger = orders.customer();
		mvc.perform(rate(order, "1", "Not mine").with(as(stranger))).andExpect(status().isNotFound());
		mvc.perform(rate(order, null, null).with(as(stranger))).andExpect(status().isNotFound());
		mvc.perform(get("/orders/" + order.getCode()).with(as(stranger))).andExpect(status().isNotFound());
		mvc.perform(rate(order, "1", null).with(asOwner(r))).andExpect(status().isForbidden());
		mvc.perform(rate(order, "1", null).with(asAdmin())).andExpect(status().isForbidden());
		mvc.perform(rate(order, "1", null)).andExpect(redirectedUrl("/login"));
		mvc.perform(post("/orders/" + order.getCode() + "/review").param("stars", "1").with(as(customer)))
			.andExpect(status().isForbidden()); // no CSRF token

		assertThat(reviewRows.findByOrderId(order.getId())).isEmpty();
		assertThat(reload(r).getRatingCount()).isEqualTo(3);
	}

	// ---------------------------------------------------------------- public: restaurant page and reviews page

	@Test
	void theRestaurantPageShowsTheNewestThreeShownReviews() throws Exception {
		Restaurant r = restaurant("4.10", 40);
		Review oldest = review(r, 5, "Oldest one");
		Review second = review(r, 4, "Second <script>x</script>");
		Review third = review(r, 3, "Third one");
		Review hidden = review(r, 1, "Rude words");
		reviews.setHidden(hidden.getId(), true);
		Review newest = review(r, 5, "Newest one");
		reviews.reply(r.getOwner().getId(), newest.getId(), "Thank you, come again!");

		String page = html(get("/r/" + r.getSlug()));
		assertThat(page).contains("Ratings and reviews")
			.contains("href=\"/r/" + r.getSlug() + "/reviews\">See all reviews</a>")
			.contains("Newest one").contains("Third one").contains("Second &lt;script&gt;x&lt;/script&gt;")
			.doesNotContain("Oldest one").doesNotContain("Rude words")
			.contains("Riya S.")
			.contains("Reply from <strong>" + r.getName() + "</strong>")
			.contains("Thank you, come again!");
		assertThat(page.indexOf("Newest one")).isLessThan(page.indexOf("Third one"));
		assertThat(page.indexOf("Third one")).isLessThan(page.indexOf("Second &lt;"));
		assertThat(page).contains("id=\"review-" + newest.getId() + "\"").contains("id=\"review-" + second.getId() + "\"")
			.doesNotContain("id=\"review-" + oldest.getId() + "\"").doesNotContain("id=\"review-" + third.getId() + "\" hidden");

		Restaurant quiet = restaurant("0", 0);
		assertThat(html(get("/r/" + quiet.getSlug())))
			.contains("No written reviews yet. Customers can review their order once it arrives.")
			.doesNotContain("See all reviews");
	}

	@Test
	void theRestaurantPageRunsOneQueryForItsReviewsHoweverManyThereAre() throws Exception {
		Restaurant r = restaurant("4.10", 40);
		for (int i = 0; i < 3; i++) {
			review(r, 4, "Review " + i);
		}
		Restaurant quiet = restaurant("0", 0);
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html(get("/r/" + quiet.getSlug())); // warm up
		stats.clear();
		html(get("/r/" + quiet.getSlug()));
		long none = stats.getPrepareStatementCount();
		stats.clear();
		html(get("/r/" + r.getSlug()));
		assertThat(stats.getPrepareStatementCount()).isEqualTo(none).isEqualTo(5);
	}

	@Test
	void theReviewsPageListsTenAPageNewestFirstOrHighestRated() throws Exception {
		Restaurant r = restaurant("4.00", 50);
		List<Review> made = new ArrayList<>();
		int[] stars = { 3, 5, 1, 4, 2, 5, 3, 4, 1, 2, 5, 4 };
		for (int i = 0; i < stars.length; i++) {
			made.add(review(r, stars[i], "Review number " + (i + 1) + "."));
		}
		reviews.setHidden(made.get(11).getId(), true); // the newest is hidden: 11 shown

		String first = html(get("/r/" + r.getSlug() + "/reviews"));
		assertThat(first).contains("<title>Reviews of " + r.getName() + " | Zaika</title>")
			.contains("11 written reviews")
			.contains("href=\"/r/" + r.getSlug() + "\"")
			.contains("aria-current=\"true\">Newest</a>")
			.contains("href=\"/r/" + r.getSlug() + "/reviews?sort=highest\"")
			.doesNotContain("Review number 12.")
			.contains("href=\"/r/" + r.getSlug() + "/reviews?page=2\"");
		assertThat(count(first, "class=\"review\"")).isEqualTo(10);
		assertThat(first.indexOf("Review number 11.")).isLessThan(first.indexOf("Review number 10."));
		assertThat(first).doesNotContain("Review number 1.<");

		String second = html(get("/r/" + r.getSlug() + "/reviews?page=2"));
		assertThat(count(second, "class=\"review\"")).isOne();
		assertThat(second).contains("Review number 1.<");

		String highest = html(get("/r/" + r.getSlug() + "/reviews?sort=highest"));
		assertThat(highest).contains("aria-current=\"true\">Highest rated</a>")
			.contains("href=\"/r/" + r.getSlug() + "/reviews?sort=highest&amp;page=2\"");
		// 5-star reviews first (newest of them first), then 4s
		assertThat(highest.indexOf("Review number 11.")).isLessThan(highest.indexOf("Review number 6."));
		assertThat(highest.indexOf("Review number 6.")).isLessThan(highest.indexOf("Review number 2."));
		assertThat(highest.indexOf("Review number 2.")).isLessThan(highest.indexOf("Review number 8."));
		String highestLast = html(get("/r/" + r.getSlug() + "/reviews?sort=highest&page=2"));
		assertThat(highestLast).contains("Review number 3.<"); // a 1-star, the oldest of them
	}

	@Test
	void theReviewsPageRunsAFixedNumberOfQueries() throws Exception {
		Restaurant r = restaurant("4.00", 50);
		for (int i = 0; i < 11; i++) {
			review(r, 1 + i % 5, "Query check " + i);
		}
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		html(get("/r/" + r.getSlug() + "/reviews")); // warm up
		stats.clear();
		html(get("/r/" + r.getSlug() + "/reviews"));
		// restaurant, the page of reviews with their authors, the count: nothing per review
		assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
	}

	@Test
	void nonCanonicalReviewsUrlsRedirect() throws Exception {
		Restaurant r = restaurant("4.00", 50);
		for (int i = 0; i < 11; i++) {
			review(r, 4, "Canonical " + i);
		}
		String base = "/r/" + r.getSlug() + "/reviews";
		String[][] cases = { { "?sort=newest", base }, { "?page=1", base }, { "?page=abc", base }, { "?page=0", base },
				{ "?sort=nonsense", base }, { "?page=9", base + "?page=2" }, { "?sort=HIGHEST", base + "?sort=highest" },
				{ "?page=2&sort=highest", base + "?sort=highest&page=2" }, { "?sort=highest&page=1", base + "?sort=highest" } };
		for (String[] c : cases) {
			mvc.perform(get(base + c[0])).andExpect(redirectedUrl(c[1]));
		}
	}

	@Test
	void reviewsPagesOfUnknownOrUnlistedRestaurantsAre404AndAnEmptyOneSaysSo() throws Exception {
		mvc.perform(get("/r/no-such-kitchen/reviews")).andExpect(status().isNotFound());
		String id = UUID.randomUUID().toString().substring(0, 8);
		Restaurant pending = fixtures.restaurant("rv-p-" + id, "Pending " + id).status(RestaurantStatus.PENDING).save();
		mvc.perform(get("/r/" + pending.getSlug() + "/reviews")).andExpect(status().isNotFound());

		Restaurant quiet = restaurant("0", 0);
		assertThat(html(get("/r/" + quiet.getSlug() + "/reviews"))).contains("No written reviews yet")
			.contains("See the menu")
			.doesNotContain("aria-label=\"Sort reviews\"");
	}

	// ---------------------------------------------------------------- owner: one reply per review

	@Test
	void theOwnerSeesTheirReviewsAndWhatWaitsForAReply() throws Exception {
		Restaurant r = restaurant("4.00", 10);
		Review answered = review(r, 5, "Answered one");
		reviews.reply(r.getOwner().getId(), answered.getId(), "Thanks a lot!");
		Review waiting = review(r, 2, "Cold <b>food</b>");
		Review hidden = review(r, 1, "Abusive words");
		reviews.setHidden(hidden.getId(), true);
		Restaurant other = restaurant("4.00", 10);
		review(other, 3, "Someone else's review");

		String page = html(get("/owner/reviews").with(asOwner(r)));
		assertThat(page).contains("<title>Reviews | Zaika</title>")
			.contains("href=\"/owner/reviews\" aria-current=\"page\"")
			.contains("Cold &lt;b&gt;food&lt;/b&gt;")
			.contains("Answered one").contains("Your reply").contains("Thanks a lot!")
			.contains("Abusive words").contains("Hidden by Zaika")
			.contains("Customers can't see this review and it doesn't count towards your rating.")
			.doesNotContain("Someone else's review")
			.contains("Order " + waiting.getOrder().getCode())
			.contains("action=\"/owner/reviews/" + waiting.getId() + "/reply\"")
			.doesNotContain("action=\"/owner/reviews/" + answered.getId() + "/reply\"");
		assertThat(count(page, "Needs a reply")).isOne();
		assertThat(page).contains("<dt>Waiting for a reply</dt> <dd class=\"owner-stat-value\">1</dd>")
			.contains("<dt>Written reviews</dt> <dd class=\"owner-stat-value\">3</dd>");
	}

	@Test
	void theOwnerRepliesOnceAndEveryoneSeesTheReply() throws Exception {
		Restaurant r = restaurant("4.00", 10);
		Review rv = review(r, 2, "Cold food");
		clock.advance(Duration.ofDays(1));

		mvc.perform(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "  Sorry! Next one is on us <3  ")
			.with(csrf()).with(asOwner(r)))
			.andExpect(redirectedUrl("/owner/reviews#review-" + rv.getId()))
			.andExpect(flash().attribute("flashSuccess", "Reply posted. Customers see it under the review."));
		Review saved = reviewRows.findById(rv.getId()).orElseThrow();
		assertThat(saved.getReply()).isEqualTo("Sorry! Next one is on us <3");
		assertThat(saved.getRepliedAt()).isEqualTo(MutableClock.DEFAULT_NOW.plusMinutes(1).plusDays(1));

		assertThat(html(get("/r/" + r.getSlug() + "/reviews"))).contains("Reply from <strong>" + r.getName() + "</strong>")
			.contains("Sorry! Next one is on us &lt;3").contains("5 Oct 2026");
		assertThat(html(get("/orders/" + rv.getOrder().getCode()).with(as(customerOf(rv)))))
			.contains("Sorry! Next one is on us &lt;3");

		mvc.perform(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "Second go").param("page", "1")
			.with(csrf()).with(asOwner(r)))
			.andExpect(redirectedUrl("/owner/reviews#review-" + rv.getId()))
			.andExpect(flash().attribute("flashError", "You've already replied to this review."));
		mvc.perform(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "").with(csrf()).with(asOwner(r)))
			.andExpect(flash().attribute("flashError", "You've already replied to this review."));
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getReply()).isEqualTo("Sorry! Next one is on us <3");
	}

	@Test
	void aReplyThatFailsValidationReopensWithTheTextKept() throws Exception {
		Restaurant r = restaurant("4.00", 10);
		Review rv = review(r, 2, "Cold food");

		String blank = html(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "  ").with(csrf()).with(asOwner(r)));
		assertThat(blank).contains("Write your reply.")
			.contains("class=\"owner-reply\" open")
			.contains("aria-describedby=\"reply-" + rv.getId() + "-error reply-" + rv.getId() + "-help\"");

		String tooLong = html(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "<b>" + "x".repeat(500))
			.with(csrf()).with(asOwner(r)));
		assertThat(tooLong).contains("Keep your reply under 500 characters.")
			.contains("&lt;b&gt;xxx")
			.doesNotContain("<b>xxx");
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getReply()).isNull();

		mvc.perform(post("/owner/reviews/" + rv.getId() + "/reply").param("reply", "x".repeat(500)).with(csrf())
			.with(asOwner(r))).andExpect(status().is3xxRedirection());
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getReply()).hasSize(500);
	}

	@Test
	void ownersReplyOnlyToTheirOwnRestaurantsReviews() throws Exception {
		Restaurant r = restaurant("4.00", 10);
		Review rv = review(r, 2, "Cold food");
		Restaurant other = restaurant("4.00", 10);
		String url = "/owner/reviews/" + rv.getId() + "/reply";

		mvc.perform(post(url).param("reply", "Not my review").with(csrf()).with(asOwner(other))).andExpect(status().isNotFound());
		mvc.perform(post(url).param("reply", "").with(csrf()).with(asOwner(other))).andExpect(status().isNotFound());
		mvc.perform(post("/owner/reviews/999999/reply").param("reply", "Ghost").with(csrf()).with(asOwner(r)))
			.andExpect(status().isNotFound());
		mvc.perform(post(url).param("reply", "Hi").with(csrf()).with(as(customerOf(rv)))).andExpect(status().isForbidden());
		mvc.perform(get("/owner/reviews").with(as(customerOf(rv)))).andExpect(status().isForbidden());
		mvc.perform(get("/owner/reviews").with(asAdmin())).andExpect(status().isForbidden());
		mvc.perform(post(url).param("reply", "Hi").with(csrf())).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/owner/reviews")).andExpect(redirectedUrl("/login"));
		mvc.perform(post(url).param("reply", "Hi").with(asOwner(r))).andExpect(status().isForbidden()); // no CSRF
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getReply()).isNull();

		User lonely = users.save(new User("Owner Without Kitchen", "lonely-" + UUID.randomUUID() + "@zaika.test",
				"9876543210", "hash", Role.RESTAURANT_OWNER));
		assertThat(html(get("/owner/reviews").with(as(lonely)))).contains("No restaurant on this account");
	}

	@Test
	void theOwnerListPagesCanonically() throws Exception {
		Restaurant r = restaurant("4.00", 10);
		for (int i = 0; i < 11; i++) {
			review(r, 4, "Paged " + i);
		}
		mvc.perform(get("/owner/reviews?page=1").with(asOwner(r))).andExpect(redirectedUrl("/owner/reviews"));
		mvc.perform(get("/owner/reviews?page=x").with(asOwner(r))).andExpect(redirectedUrl("/owner/reviews"));
		mvc.perform(get("/owner/reviews?page=7").with(asOwner(r))).andExpect(redirectedUrl("/owner/reviews?page=2"));
		String second = html(get("/owner/reviews?page=2").with(asOwner(r)));
		assertThat(second).contains("Paged 0").contains("name=\"page\" value=\"2\"");
		Restaurant empty = restaurant("0", 0);
		assertThat(html(get("/owner/reviews").with(asOwner(empty)))).contains("No reviews yet");
	}

	// ---------------------------------------------------------------- admin: hide and show again

	@Test
	void hidingAReviewTakesItOffThePageAndOutOfTheRating() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		Review rv = review(r, 1, "Spam spam spam");
		assertThat(reload(r).getRatingAverage()).isEqualByComparingTo("3.25");
		RequestPostProcessor admin = asAdmin();

		mvc.perform(post("/admin/reviews/" + rv.getId() + "/hide").param("status", "shown").param("q", r.getName())
			.with(csrf()).with(admin))
			.andExpect(redirectedUrl("/admin/reviews?status=shown&q=" + r.getName().replace(" ", "+")))
			.andExpect(flash().attribute("flashSuccess",
					"Review hidden. It's off the restaurant page and no longer counts towards the rating."));
		Restaurant hidden = reload(r);
		assertThat(hidden.getRatingAverage()).isEqualByComparingTo("4.00");
		assertThat(hidden.getRatingCount()).isEqualTo(3);
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getHiddenAt()).isNotNull();
		assertThat(html(get("/r/" + r.getSlug()))).doesNotContain("Spam spam spam");
		assertThat(html(get("/r/" + r.getSlug() + "/reviews"))).doesNotContain("Spam spam spam");
		assertThat(html(get("/orders/" + rv.getOrder().getCode()).with(as(customerOf(rv)))))
			.contains("Spam spam spam").contains("Zaika has hidden this review from the restaurant page");

		mvc.perform(post("/admin/reviews/" + rv.getId() + "/hide").with(csrf()).with(admin))
			.andExpect(redirectedUrl("/admin/reviews"))
			.andExpect(flash().attribute("flashInfo", "This review is already hidden."));
		assertThat(reload(r).getRatingCount()).isEqualTo(3);

		mvc.perform(post("/admin/reviews/" + rv.getId() + "/show").param("page", "junk").with(csrf()).with(admin))
			.andExpect(redirectedUrl("/admin/reviews"))
			.andExpect(flash().attribute("flashSuccess",
					"Review shown again. It's back on the restaurant page and counts towards the rating."));
		Restaurant shown = reload(r);
		assertThat(shown.getRatingAverage()).isEqualByComparingTo("3.25");
		assertThat(shown.getRatingCount()).isEqualTo(4);
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().getHiddenAt()).isNull();
		assertThat(html(get("/r/" + r.getSlug() + "/reviews"))).contains("Spam spam spam");

		mvc.perform(post("/admin/reviews/999999/hide").with(csrf()).with(admin)).andExpect(status().isNotFound());
	}

	@Test
	void theAdminListShowsEveryReviewWithChipsAndSearch() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		String w = r.getName();
		Review shown = review(r, 5, "Brilliant <em>thali</em>");
		Review hidden = review(r, 1, "Hidden insult");
		reviews.setHidden(hidden.getId(), true);
		RequestPostProcessor admin = asAdmin();

		String all = html(get(java.net.URI.create("/admin/reviews?q=" + w.replace(" ", "%20"))).with(admin));
		assertThat(all).contains("<title>Reviews | Zaika</title>")
			.contains("href=\"/admin/reviews\" aria-current=\"page\"")
			.contains("Brilliant &lt;em&gt;thali&lt;/em&gt;").contains("Hidden insult")
			.contains("href=\"/admin/restaurants/" + r.getSlug() + "\"")
			.contains("href=\"/admin/users/" + shown.getCustomer().getId() + "\"")
			.contains("href=\"/admin/orders/" + shown.getOrder().getCode() + "\"")
			.contains("action=\"/admin/reviews/" + shown.getId() + "/hide\"")
			.contains("action=\"/admin/reviews/" + hidden.getId() + "/show\"")
			.contains("name=\"q\" value=\"" + w + "\"")
			.contains("Hide review").contains("Show again");

		String onlyHidden = html(get(java.net.URI.create("/admin/reviews?status=hidden&q=" + w.replace(" ", "%20"))).with(admin));
		assertThat(onlyHidden).contains("Hidden insult").doesNotContain("Brilliant")
			.contains("name=\"status\" value=\"hidden\"");
		String byComment = html(get(java.net.URI.create("/admin/reviews?q=brilliant%20%3Cem%3E")).with(admin));
		assertThat(byComment).contains("Brilliant &lt;em&gt;thali");
		assertThat(html(get(java.net.URI.create("/admin/reviews?q=zzqq%25none")).with(admin))).contains("No reviews match");

		mvc.perform(get("/admin/reviews?status=nope").with(admin)).andExpect(redirectedUrl("/admin/reviews"));
		mvc.perform(get("/admin/reviews?page=1").with(admin)).andExpect(redirectedUrl("/admin/reviews"));
	}

	@Test
	void onlyAdminsModerateReviews() throws Exception {
		Restaurant r = restaurant("4.00", 3);
		Review rv = review(r, 1, "Moderate me");
		String hide = "/admin/reviews/" + rv.getId() + "/hide";
		mvc.perform(get("/admin/reviews")).andExpect(redirectedUrl("/login"));
		mvc.perform(post(hide).with(csrf())).andExpect(redirectedUrl("/login"));
		for (RequestPostProcessor notAdmin : new RequestPostProcessor[] { as(customerOf(rv)), asOwner(r),
				as(orders.rider()) }) {
			mvc.perform(get("/admin/reviews").with(notAdmin)).andExpect(status().isForbidden());
			mvc.perform(post(hide).with(csrf()).with(notAdmin)).andExpect(status().isForbidden());
		}
		mvc.perform(post(hide).with(asAdmin())).andExpect(status().isForbidden()); // no CSRF
		assertThat(reviewRows.findById(rv.getId()).orElseThrow().isHidden()).isFalse();
		assertThat(reload(r).getRatingCount()).isEqualTo(4);
	}

}
