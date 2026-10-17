package com.zaika.catalog;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.zaika.common.BaseEntity;
import com.zaika.storage.ImageUrls;

/**
 * Restaurants as customers browse them: only {@link RestaurantStatus#APPROVED} ones, filtered, searched, sorted and
 * paged in the database, mapped to {@link RestaurantCard}s. "Open" always means within today's hours (overnight hours
 * included) and taking orders, judged by the app clock.
 */
@Service
public class RestaurantBrowseService {

	public static final int PAGE_SIZE = 12;

	/** Cards per home page row. */
	public static final int HOME_ROW_SIZE = 4;

	/** The "Rating 4.0+" filter. Unrated restaurants never pass it. */
	public static final BigDecimal TOP_RATING = new BigDecimal("4.00");

	/** LIKE escape character. Not a backslash, which MySQL string literals treat as an escape themselves. */
	private static final char ESCAPE = '!';

	private final RestaurantRepository restaurants;

	private final CuisineRepository cuisines;

	private final ImageUrls images;

	private final Clock clock;

	public RestaurantBrowseService(RestaurantRepository restaurants, CuisineRepository cuisines, ImageUrls images,
			Clock clock) {
		this.restaurants = restaurants;
		this.cuisines = cuisines;
		this.images = images;
		this.clock = clock;
	}

	/** Every cuisine, by name, for the filter chips. */
	@Transactional(readOnly = true)
	public List<Cuisine> cuisines() {
		return cuisines.findAllByOrderByNameAsc();
	}

	/** The cuisines at least one live (APPROVED) restaurant cooks, by name, so no shortcut leads to an empty list. */
	@Transactional(readOnly = true)
	public List<Cuisine> liveCuisines() {
		return cuisines.findServedBy(RestaurantStatus.APPROVED);
	}

	/**
	 * One page ({@link RestaurantFilter#getPage()}, {@link #PAGE_SIZE} cards) of the listing. A page past the end
	 * comes back empty, with the real totals. At most three queries: the page, the count and the page's cuisines.
	 */
	@Transactional(readOnly = true)
	public Page<RestaurantCard> browse(RestaurantFilter filter) {
		LocalTime now = LocalTime.now(clock);
		Page<Restaurant> page = restaurants.findAll(matching(filter, now),
				PageRequest.of(filter.getPage() - 1, PAGE_SIZE));
		if (page.hasContent()) {
			restaurants.findWithCuisinesByIdIn(page.getContent().stream().map(BaseEntity::getId).toList());
		}
		return page.map(restaurant -> toCard(restaurant, now));
	}

	/**
	 * The home page rows, each the first {@link #HOME_ROW_SIZE} cards of a listing query (no count), plus how many
	 * restaurants are live and how many are open now. Six queries: three rows, their cuisines and two counts.
	 */
	@Transactional(readOnly = true)
	public Highlights highlights() {
		LocalTime now = LocalTime.now(clock);
		RestaurantFilter topRated = new RestaurantFilter();
		topRated.setTop(true);
		RestaurantFilter fastest = new RestaurantFilter();
		fastest.setOpen(true);
		fastest.setSort(RestaurantSort.FASTEST.getParam());
		RestaurantFilter pureVeg = new RestaurantFilter();
		pureVeg.setVeg(true);

		List<List<Restaurant>> rows = Stream.of(topRated, fastest, pureVeg)
			.map(filter -> restaurants.findBy(matching(filter, now), q -> q.limit(HOME_ROW_SIZE).all()))
			.toList();
		List<Long> ids = rows.stream().flatMap(List::stream).map(BaseEntity::getId).distinct().toList();
		if (!ids.isEmpty()) {
			restaurants.findWithCuisinesByIdIn(ids);
		}
		RestaurantFilter open = new RestaurantFilter();
		open.setOpen(true);
		return new Highlights(restaurants.count(matching(new RestaurantFilter(), now)),
				restaurants.count(matching(open, now)), row(topRated, rows.get(0), now), row(fastest, rows.get(1), now),
				row(pureVeg, rows.get(2), now));
	}

	private Row row(RestaurantFilter filter, List<Restaurant> found, LocalTime now) {
		return new Row(filter.url(), found.stream().map(restaurant -> toCard(restaurant, now)).toList());
	}

	/** The card for one restaurant at {@code now}. Its cuisines must be loaded. */
	public RestaurantCard toCard(Restaurant restaurant, LocalTime now) {
		boolean openNow = restaurant.isAcceptingOrders() && restaurant.isOpenAt(now);
		// A paused kitchen has no known reopening time; one closed by its hours opens at its opening time.
		LocalTime opensAt = openNow || !restaurant.isAcceptingOrders() ? null : restaurant.getOpensAt();
		String offer = restaurant.getDeliveryFee().signum() == 0 ? "Free delivery" : null;
		return new RestaurantCard(restaurant.getSlug(), restaurant.getName(), images.cover(restaurant),
				restaurant.getCuisines().stream().map(Cuisine::getName).toList(), restaurant.getRatingAverage(),
				restaurant.getRatingCount(), restaurant.getDeliveryMinutes(), restaurant.getCostForTwo(),
				restaurant.isPureVeg(), offer, openNow, opensAt);
	}

	private static Specification<Restaurant> matching(RestaurantFilter filter, LocalTime now) {
		return (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>();
			where.add(cb.equal(root.get("status"), RestaurantStatus.APPROVED));
			if (filter.getCuisine() != null) {
				Subquery<Long> tagged = query.subquery(Long.class);
				Join<Restaurant, Cuisine> cuisine = tagged.correlate(root).join("cuisines");
				tagged.select(cuisine.get("id")).where(cb.equal(cuisine.get("slug"), filter.getCuisine()));
				where.add(cb.exists(tagged));
			}
			if (filter.isVeg()) {
				where.add(cb.isTrue(root.get("pureVeg")));
			}
			if (filter.isTop()) {
				where.add(cb.greaterThan(root.<Integer>get("ratingCount"), 0));
				where.add(cb.greaterThanOrEqualTo(root.get("ratingAverage"), TOP_RATING));
			}
			if (filter.isOpen()) {
				where.add(openAt(root, cb, now));
			}
			String pattern = filter.getQ() == null ? null : likePattern(filter.getQ());
			if (pattern != null) {
				Subquery<Long> dish = query.subquery(Long.class);
				Root<MenuItem> item = dish.from(MenuItem.class);
				dish.select(item.get("id"))
					.where(cb.equal(item.get("restaurant"), root), cb.isFalse(item.get("archived")),
							cb.like(cb.lower(item.get("name")), pattern, ESCAPE));
				Subquery<Long> tagged = query.subquery(Long.class);
				Join<Restaurant, Cuisine> cuisine = tagged.correlate(root).join("cuisines");
				tagged.select(cuisine.get("id")).where(cb.like(cb.lower(cuisine.get("name")), pattern, ESCAPE));
				where.add(cb.or(nameLike(root, cb, pattern), cb.exists(tagged), cb.exists(dish)));
			}
			if (!Long.class.equals(query.getResultType())) { // the count query needs no order
				query.orderBy(order(filter.getSortOption(), pattern, root, cb, now));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
	}

	private static List<Order> order(RestaurantSort sort, String pattern, Root<Restaurant> root, CriteriaBuilder cb,
			LocalTime now) {
		List<Order> order = new ArrayList<>();
		switch (sort) {
			case RELEVANCE -> {
				if (pattern != null) {
					order.add(cb.asc(firstIf(nameLike(root, cb, pattern), cb)));
				}
				order.add(cb.asc(firstIf(openAt(root, cb, now), cb)));
				order.add(cb.desc(root.get("ratingAverage")));
				order.add(cb.desc(root.get("ratingCount")));
			}
			case RATING -> {
				order.add(cb.desc(root.get("ratingAverage")));
				order.add(cb.desc(root.get("ratingCount")));
			}
			case FASTEST -> {
				order.add(cb.asc(root.get("avgPrepMinutes")));
				order.add(cb.desc(root.get("ratingAverage")));
			}
			case COST_ASC -> {
				order.add(cb.asc(root.get("costForTwo")));
				order.add(cb.desc(root.get("ratingAverage")));
			}
			case COST_DESC -> {
				order.add(cb.desc(root.get("costForTwo")));
				order.add(cb.desc(root.get("ratingAverage")));
			}
		}
		order.add(cb.asc(root.get("id"))); // stable pages
		return order;
	}

	/** 0 when {@code condition} holds, else 1: sorts the rows that match first. */
	private static Expression<Integer> firstIf(Predicate condition, CriteriaBuilder cb) {
		return cb.<Integer>selectCase().when(condition, 0).otherwise(1);
	}

	private static Predicate nameLike(Root<Restaurant> root, CriteriaBuilder cb, String pattern) {
		return cb.like(cb.lower(root.get("name")), pattern, ESCAPE);
	}

	/** Same rule as {@link Restaurant#isOpenAt(LocalTime)}, plus the accepting-orders toggle, in SQL. */
	private static Predicate openAt(Root<Restaurant> root, CriteriaBuilder cb, LocalTime now) {
		Path<LocalTime> opens = root.get("opensAt");
		Path<LocalTime> closes = root.get("closesAt");
		return cb.and(cb.isTrue(root.get("acceptingOrders")), cb.or(
				cb.equal(opens, closes),
				cb.and(cb.lessThan(opens, closes), cb.lessThanOrEqualTo(opens, now), cb.greaterThan(closes, now)),
				cb.and(cb.greaterThan(opens, closes),
						cb.or(cb.lessThanOrEqualTo(opens, now), cb.greaterThan(closes, now)))));
	}

	/** {@code %text%}, lower-cased, with LIKE wildcards in the text matched literally. */
	public static String likePattern(String text) {
		StringBuilder pattern = new StringBuilder("%");
		for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
			if (c == ESCAPE || c == '%' || c == '_') {
				pattern.append(ESCAPE);
			}
			pattern.append(c);
		}
		return pattern.append('%').toString();
	}

	/** A home page row: its first cards and the listing URL that shows all of them ("See all"). */
	public record Row(String url, List<RestaurantCard> cards) {
	}

	/**
	 * What the home page shows from the catalog.
	 *
	 * @param liveCount approved restaurants; 0 means the catalog is empty
	 * @param openCount approved restaurants open and taking orders now
	 * @param topRated rated 4.0+, open ones first
	 * @param fastest open now, shortest delivery estimate first
	 * @param pureVeg pure veg restaurants, open ones first
	 */
	public record Highlights(long liveCount, long openCount, Row topRated, Row fastest, Row pureVeg) {
	}

}
