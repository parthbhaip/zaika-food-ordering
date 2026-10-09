package com.zaika.catalog;

/** Small helper shared by the catalog pages: search text. (The requested URL is {@code common.Requests.currentUrl}.) */
final class CatalogRequests {

	private CatalogRequests() {
	}

	/** Search text as a search box sends it: stripped and cut to {@code maxLength}; null when blank. */
	static String searchText(String raw, int maxLength) {
		String stripped = raw == null ? "" : raw.strip();
		return stripped.isEmpty() ? null : stripped.substring(0, Math.min(stripped.length(), maxLength)).strip();
	}

}
