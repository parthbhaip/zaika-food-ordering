package com.zaika.api;

import static com.zaika.api.RestaurantSummary.absolute;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.RestaurantCard;
import com.zaika.catalog.RestaurantMenu;
import com.zaika.storage.ImageUrls;

/**
 * One restaurant and its menu, as {@code /r/{slug}} shows it: archived dishes left out, sold-out ones kept with
 * {@code available: false}, empty sections left out.
 *
 * @param opensAt daily opening time ("HH:mm"); equal to {@code closesAt} when open round the clock
 * @param closesAt daily closing time; earlier than {@code opensAt} when the kitchen works past midnight
 * @param openNow within today's hours and taking orders: only then can it take an order
 * @param acceptingOrders false while the kitchen has paused orders
 * @param nextOpensAt next opening time when closed by its hours, else null
 * @param offers the delivery offer, then the coupons usable here
 */
public record RestaurantDetail(String slug, String name, String tagline, String description, String imageUrl,
		String logoUrl, List<String> cuisines, String address, String area, String city, String pincode,
		RestaurantSummary.Rating rating, int deliveryMinutes, BigDecimal costForTwo, BigDecimal minimumOrder,
		BigDecimal deliveryFee, boolean pureVeg, @JsonFormat(pattern = "HH:mm") LocalTime opensAt,
		@JsonFormat(pattern = "HH:mm") LocalTime closesAt, boolean openAllDay, boolean openNow,
		boolean acceptingOrders, @JsonFormat(pattern = "HH:mm") LocalTime nextOpensAt, List<Offer> offers,
		List<Section> menu) {

	static RestaurantDetail of(RestaurantMenu menu, ImageUrls images, String baseUrl) {
		Restaurant r = menu.restaurant();
		RestaurantCard card = menu.card();
		return new RestaurantDetail(r.getSlug(), r.getName(), r.getTagline(), r.getDescription(),
				absolute(baseUrl, card.imageUrl()), absolute(baseUrl, images.logo(r)),
				r.getCuisines().stream().map(Cuisine::getName).toList(), r.getAddress(), r.getArea(),
				r.getCity(), r.getPincode(), RestaurantSummary.Rating.of(card.ratingAverage(), card.ratingCount()),
				card.deliveryMinutes(), r.getCostForTwo(), r.getMinimumOrder(), r.getDeliveryFee(), r.isPureVeg(),
				r.getOpensAt(), r.getClosesAt(), menu.isOpenAllDay(), card.openNow(), r.isAcceptingOrders(),
				card.opensAt(), menu.offers().stream().map(o -> new Offer(o.title(), o.detail())).toList(),
				menu.sections().stream()
					.map(s -> new Section(s.id(), s.name(),
							s.items().stream().map(item -> Dish.of(item, images, baseUrl)).toList()))
					.toList());
	}

	/** "Free delivery above ₹499" / "₹30 delivery fee below that", or a coupon: "50% off up to ₹100" / "Use WELCOME50". */
	@Schema(name = "RestaurantOffer")
	public record Offer(String title, String detail) {
	}

	/** A menu section with its dishes, in menu order. */
	@Schema(name = "MenuSection")
	public record Section(long id, String name, List<Dish> items) {
	}

	/**
	 * A dish. Its {@code id} is what the cart takes.
	 *
	 * @param spiceLevel 0 (not spicy) to 3
	 * @param available false while sold out
	 */
	@Schema(name = "MenuDish")
	public record Dish(long id, String name, String description, BigDecimal price, boolean veg, boolean bestseller,
			int spiceLevel, boolean available, String imageUrl) {

		static Dish of(MenuItem item, ImageUrls images, String baseUrl) {
			return new Dish(item.getId(), item.getName(), item.getDescription(), item.getPrice(), item.isVeg(),
					item.isBestseller(), item.getSpiceLevel(), item.isAvailable(), absolute(baseUrl, images.dish(item)));
		}

	}

}
