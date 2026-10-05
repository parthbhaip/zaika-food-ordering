package com.zaika.catalog;

/** Sort orders of the restaurant listing, with the {@code ?sort=} value and the label in the sort menu. */
public enum RestaurantSort {

	/** Name matches first when searching, then open before closed, then the best rated. */
	RELEVANCE("relevance", "Relevance"),
	RATING("rating", "Rating"),
	FASTEST("fastest", "Delivery time"),
	COST_ASC("cost-asc", "Cost: low to high"),
	COST_DESC("cost-desc", "Cost: high to low");

	private final String param;

	private final String label;

	RestaurantSort(String param, String label) {
		this.param = param;
		this.label = label;
	}

	/** The sort for a {@code ?sort=} value; unknown or missing values mean {@link #RELEVANCE}. */
	public static RestaurantSort fromParam(String value) {
		for (RestaurantSort sort : values()) {
			if (sort.param.equals(value)) {
				return sort;
			}
		}
		return RELEVANCE;
	}

	public String getParam() {
		return param;
	}

	public String getLabel() {
		return label;
	}

}
