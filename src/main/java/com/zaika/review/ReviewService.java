package com.zaika.review;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantRepository;
import com.zaika.catalog.RestaurantStatus;
import com.zaika.common.NotFoundException;
import com.zaika.order.Order;
import com.zaika.order.OrderRepository;
import com.zaika.order.OrderStatus;

/**
 * Ratings and reviews. A customer rates a delivered order once; the restaurant's rating moves in the same
 * transaction, incrementally from its stored average and count (seeded restaurants have ratings but no review rows).
 * Every rating change locks the restaurant row first, so two reviews never overwrite each other's update. The owner
 * replies once; an admin hides a review (taking its stars back out of the rating) or shows it again.
 */
@Service
public class ReviewService {

	/** Reviews per page on the restaurant's reviews page and in the owner panel. */
	public static final int PAGE_SIZE = 10;

	/** Reviews per page in the admin list. */
	public static final int ADMIN_PAGE_SIZE = 20;

	/** Newest reviews shown under a restaurant's menu. */
	public static final int PREVIEW_SIZE = 3;

	public static final String ALREADY_REPLIED = "You've already replied to this review.";

	private final ReviewRepository reviews;

	private final OrderRepository orders;

	private final RestaurantRepository restaurants;

	private final EntityManager em;

	private final Clock clock;

	ReviewService(ReviewRepository reviews, OrderRepository orders, RestaurantRepository restaurants, EntityManager em,
			Clock clock) {
		this.reviews = reviews;
		this.orders = orders;
		this.restaurants = restaurants;
		this.em = em;
		this.clock = clock;
	}

	/** The review of an order, if it has one: the order page shows it (only the review's own columns are used). */
	@Transactional(readOnly = true)
	public Optional<Review> ofOrder(long orderId) {
		return reviews.findByOrderId(orderId);
	}

	/**
	 * Posts the customer's review of their order {@code code} and counts the stars into the restaurant's rating.
	 * Someone else's (or an unknown) order is a 404; an order not delivered or already rated is a
	 * {@link ReviewException}.
	 */
	@Transactional
	public Review post(long customerId, String code, int stars, String comment) {
		Order order = orders.findByCodeAndCustomerId(code, customerId)
			.orElseThrow(() -> new NotFoundException("Order " + code + " for customer " + customerId));
		Restaurant restaurant = lock(order.getRestaurant());
		String problem = problem(order); // under the lock, so a double submit finds the first review
		if (problem != null) {
			throw new ReviewException(problem);
		}
		Review review = reviews.save(new Review(order, stars, comment));
		restaurant.addRating(stars);
		return review;
	}

	/** Why the order cannot be rated (not delivered, already rated), as a message for the customer; null when it can. */
	@Transactional(readOnly = true)
	public String problem(Order order) {
		if (order.getStatus() != OrderStatus.DELIVERED) {
			return "You can rate this order once it's delivered.";
		}
		if (reviews.existsByOrderId(order.getId())) {
			return "You've already rated this order. Thanks for the feedback!";
		}
		return null;
	}

	/** An approved restaurant's public reviews, page {@code page} (0-based) in {@code sort}; anything else is a 404. */
	@Transactional(readOnly = true)
	public Listing listing(String slug, ReviewSort sort, int page) {
		Restaurant restaurant = restaurants.findBySlug(slug)
			.filter(r -> r.getStatus() == RestaurantStatus.APPROVED)
			.orElseThrow(() -> new NotFoundException("No approved restaurant " + slug));
		return new Listing(restaurant, reviews.findVisible(restaurant.getId(),
				PageRequest.of(Math.max(page, 0), PAGE_SIZE, sort.sort())));
	}

	/** The newest public reviews of a restaurant, customers loaded: the restaurant page's preview. */
	@Transactional(readOnly = true)
	public List<Review> latest(long restaurantId) {
		return reviews.findLatest(restaurantId, Limit.of(PREVIEW_SIZE));
	}

	/** The owner's restaurant and page {@code page} (0-based) of its reviews; empty for an owner without a restaurant. */
	@Transactional(readOnly = true)
	public Optional<OwnerReviews> forOwner(long ownerId, int page) {
		return restaurants.findByOwnerId(ownerId)
			.map(r -> new OwnerReviews(r, reviews.findForOwner(r.getId(), PageRequest.of(Math.max(page, 0), PAGE_SIZE)),
					reviews.countByRestaurantIdAndHiddenFalseAndReplyIsNull(r.getId())));
	}

	/** The owner's one reply to a review of their restaurant; another restaurant's review is a 404. */
	@Transactional
	public Review reply(long ownerId, long reviewId, String text) {
		Review review = reviews.findForReply(reviewId, ownerId)
			.orElseThrow(() -> new NotFoundException("Review " + reviewId + " for owner " + ownerId));
		if (review.getReply() != null) {
			throw new ReviewException(ALREADY_REPLIED);
		}
		review.reply(text, LocalDateTime.now(clock));
		return review;
	}

	/** A review of the owner's restaurant; another restaurant's review is a 404. */
	@Transactional(readOnly = true)
	public Review ownedReview(long ownerId, long reviewId) {
		return reviews.findByIdAndRestaurantOwnerId(reviewId, ownerId)
			.orElseThrow(() -> new NotFoundException("Review " + reviewId + " for owner " + ownerId));
	}

	/** Every review for the admin list: hidden or not ({@code hidden}, any when null), matching {@code q} (any when null). */
	@Transactional(readOnly = true)
	public Page<Review> adminList(Boolean hidden, String q, int page) {
		return reviews.findForAdmin(hidden, q == null ? null : RestaurantBrowseService.likePattern(q),
				PageRequest.of(Math.max(page, 0), ADMIN_PAGE_SIZE));
	}

	/** How many reviews are hidden ({@code true}) and how many shown ({@code false}); absent when none. */
	@Transactional(readOnly = true)
	public List<ReviewRepository.VisibilityCount> countPerVisibility() {
		return reviews.countPerVisibility();
	}

	/**
	 * Hides a review (its stars leave the restaurant's rating) or shows it again (they come back). Returns whether
	 * anything changed: hiding a hidden review does nothing.
	 */
	@Transactional
	public boolean setHidden(long reviewId, boolean hidden) {
		Review review = reviews.findForUpdate(reviewId)
			.orElseThrow(() -> new NotFoundException("Review " + reviewId));
		if (review.isHidden() == hidden) {
			return false;
		}
		Restaurant restaurant = lock(review.getRestaurant());
		if (hidden) {
			restaurant.removeRating(review.getStars());
		}
		else {
			restaurant.addRating(review.getStars());
		}
		review.setHidden(hidden, LocalDateTime.now(clock));
		return true;
	}

	/** Re-reads the restaurant with a row lock, so its rating is current and nobody else changes it until commit. */
	private Restaurant lock(Restaurant restaurant) {
		em.refresh(restaurant, LockModeType.PESSIMISTIC_WRITE);
		return restaurant;
	}

	/** A restaurant's reviews page: the restaurant and one page of its public reviews. */
	public record Listing(Restaurant restaurant, Page<Review> reviews) {
	}

	/** The owner panel's reviews: the restaurant, one page of its reviews, and how many still wait for a reply. */
	public record OwnerReviews(Restaurant restaurant, Page<Review> reviews, long waiting) {
	}

}
