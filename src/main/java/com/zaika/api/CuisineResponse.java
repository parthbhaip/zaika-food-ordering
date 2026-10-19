package com.zaika.api;

import com.zaika.catalog.Cuisine;

/** A cuisine; {@code slug} is the {@code cuisine} filter of {@code GET /api/v1/restaurants}. */
public record CuisineResponse(String slug, String name) {

	static CuisineResponse of(Cuisine cuisine) {
		return new CuisineResponse(cuisine.getSlug(), cuisine.getName());
	}

}
