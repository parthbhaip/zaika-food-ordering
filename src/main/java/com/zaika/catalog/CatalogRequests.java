package com.zaika.catalog;

import jakarta.servlet.http.HttpServletRequest;

/** Small helpers shared by the catalog pages: the requested URL (for canonical redirects) and search text. */
final class CatalogRequests {

	private CatalogRequests() {
	}

	/** The requested URL. {@code %20} and {@code +} both mean a space in a query, so they compare equal. */
	static String currentUrl(HttpServletRequest request) {
		String query = request.getQueryString();
		return request.getRequestURI() + (query == null ? "" : "?" + query.replace("%20", "+"));
	}

	/** Search text as a search box sends it: stripped and cut to {@code maxLength}; null when blank. */
	static String searchText(String raw, int maxLength) {
		String stripped = raw == null ? "" : raw.strip();
		return stripped.isEmpty() ? null : stripped.substring(0, Math.min(stripped.length(), maxLength)).strip();
	}

}
