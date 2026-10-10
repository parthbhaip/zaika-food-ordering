package com.zaika.common;

import jakarta.servlet.http.HttpServletRequest;

/** Request helpers shared by list pages that redirect to their canonical URL (catalog, owner reports, orders). */
public final class Requests {

	private Requests() {
	}

	/** The requested URL. {@code %20} and {@code +} both mean a space in a query, so they compare equal. */
	public static String currentUrl(HttpServletRequest request) {
		String query = request.getQueryString();
		return request.getRequestURI() + (query == null ? "" : "?" + query.replace("%20", "+"));
	}

	/** The 1-based {@code ?page=} asked for: 1 when absent, 0 when unreadable. */
	public static int pageNumber(String page) {
		if (page == null) {
			return 1;
		}
		try {
			return Integer.parseInt(page);
		}
		catch (NumberFormatException ex) {
			return 0;
		}
	}

}
