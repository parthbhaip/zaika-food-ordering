package com.zaika.common;

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Pagination helpers for {@code fragments/pagination}, as the bean {@code pages}. Page numbers are 1-based in
 * URLs and on screen ({@code spring.data.web.pageable.one-indexed-parameters=true}).
 */
@Component("pages")
public class PageLinks {

	/** Page numbers to show: first, last, and the current one with its neighbours; {@code null} marks a gap. */
	public List<Integer> numbers(Page<?> page) {
		int total = page.getTotalPages();
		int current = page.getNumber() + 1;
		List<Integer> numbers = new ArrayList<>();
		for (int n = 1; n <= total; n++) {
			boolean shown = n == 1 || n == total || Math.abs(n - current) <= 1
					// a gap that would hide a single page shows that page instead
					|| (n == 2 && current == 4) || (n == total - 1 && current == total - 3);
			if (shown) {
				numbers.add(n);
			}
			else if (numbers.getLast() != null) {
				numbers.add(null);
			}
		}
		return numbers;
	}

	/** The current URL (path and query) pointing at page {@code number}; page 1 drops the parameter. */
	public String url(int number) {
		HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
		UriComponentsBuilder uri = UriComponentsBuilder.fromPath(request.getRequestURI()).query(request.getQueryString());
		if (number > 1) {
			uri.replaceQueryParam("page", number);
		}
		else {
			uri.replaceQueryParam("page");
		}
		return uri.build().toUriString();
	}

}
