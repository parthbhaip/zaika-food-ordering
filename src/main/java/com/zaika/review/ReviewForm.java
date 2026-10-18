package com.zaika.review;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A customer's review of a delivered order: the stars and an optional comment. */
public class ReviewForm {

	@NotNull(message = "Choose a rating from 1 to 5 stars.")
	@Min(value = 1, message = "Choose a rating from 1 to 5 stars.")
	@Max(value = 5, message = "Choose a rating from 1 to 5 stars.")
	private Integer stars;

	@Size(max = Review.MAX_TEXT, message = "Keep your review under 500 characters.")
	private String comment;

	public Integer getStars() {
		return stars;
	}

	public void setStars(Integer stars) {
		this.stars = stars;
	}

	public String getComment() {
		return comment;
	}

	public void setComment(String comment) {
		this.comment = comment;
	}

}
