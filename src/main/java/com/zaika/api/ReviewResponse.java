package com.zaika.api;

import static com.zaika.api.OrderResponse.at;

import java.time.OffsetDateTime;
import java.time.ZoneId;

import com.zaika.review.Review;

/**
 * The customer's review of an order.
 *
 * @param reply the restaurant's answer, or null
 * @param hidden Zaika took it off the restaurant's public page (it no longer counts in the rating)
 */
public record ReviewResponse(int stars, String comment, OffsetDateTime createdAt, String reply,
		OffsetDateTime repliedAt, boolean hidden) {

	static ReviewResponse of(Review r, ZoneId zone) {
		return new ReviewResponse(r.getStars(), r.getComment(), at(r.getCreatedAt(), zone), r.getReply(),
				at(r.getRepliedAt(), zone), r.isHidden());
	}

}
