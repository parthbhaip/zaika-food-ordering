package com.zaika.common;

import jakarta.servlet.http.HttpServletRequest;

/** Request helpers shared by list pages that redirect to their canonical URL (catalog, owner reports). */
public final class Requests {

	private Requests() {
	}

	/** The requested URL. {@code %20} and {@code +} both mean a space in a query, so they compare equal. */
	public static String currentUrl(HttpServletRequest request) {
		String query = request.getQueryString();
		return request.getRequestURI() + (query == null ? "" : "?" + query.replace("%20", "+"));
	}

}
