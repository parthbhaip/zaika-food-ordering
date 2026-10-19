package com.zaika.api;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.zaika.catalog.Cuisine;
import com.zaika.catalog.MenuFilter;
import com.zaika.catalog.RestaurantBrowseService;
import com.zaika.catalog.RestaurantFilter;
import com.zaika.catalog.RestaurantMenuService;
import com.zaika.catalog.RestaurantSort;
import com.zaika.catalog.SearchService;
import com.zaika.common.Requests;
import com.zaika.config.AppProperties;
import com.zaika.storage.ImageUrls;

/**
 * The public catalog for the mobile app, no token needed: cuisines, the restaurant list (same filters, sort and
 * pages as {@code /restaurants}), one restaurant with its menu, and search. Only approved restaurants ever show.
 * Unknown filter values are a 400 with {@code errors} per parameter (the website quietly drops them instead).
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = OpenApiConfig.CATALOG)
class CatalogApiController {

	private static final String SORTS = Arrays.stream(RestaurantSort.values())
		.map(RestaurantSort::getParam)
		.collect(Collectors.joining(", "));

	private final RestaurantBrowseService browse;

	private final RestaurantMenuService menus;

	private final SearchService search;

	private final ImageUrls images;

	private final String baseUrl;

	CatalogApiController(RestaurantBrowseService browse, RestaurantMenuService menus, SearchService search,
			ImageUrls images, AppProperties app) {
		this.browse = browse;
		this.menus = menus;
		this.search = search;
		this.images = images;
		this.baseUrl = app.baseUrl();
	}

	/** Every cuisine, by name. */
	@Operation(summary = "Every cuisine, by name",
			description = "Use a cuisine's `slug` as the `cuisine` filter of the restaurant list.")
	@GetMapping("/cuisines")
	List<CuisineResponse> cuisines() {
		return browse.cuisines().stream().map(CuisineResponse::of).toList();
	}

	/**
	 * Approved restaurants, 12 a page. {@code q} matches the restaurant name, a cuisine or a dish; {@code open} =
	 * open and taking orders now; {@code top} = rated 4.0+; {@code veg} = pure veg.
	 */
	@Operation(summary = "Restaurants, 12 a page",
			description = "Approved restaurants with the website's filters and sorts. `q` matches a restaurant, "
					+ "cuisine or dish name; `cuisine` is a cuisine slug; `veg` = pure veg; `top` = rated 4.0+; `open` "
					+ "= open and taking orders now; `sort` = relevance, rating, fastest, cost-asc or cost-desc. "
					+ "Unknown values are a 400 with `errors`.")
	@GetMapping("/restaurants")
	PageResponse<RestaurantSummary> restaurants(@RequestParam(required = false) String q,
			@RequestParam(required = false) String cuisine, @RequestParam(defaultValue = "false") boolean veg,
			@RequestParam(defaultValue = "false") boolean top, @RequestParam(defaultValue = "false") boolean open,
			@RequestParam(defaultValue = "relevance") String sort, @RequestParam(defaultValue = "1") int page)
			throws BindException {
		BindException errors = new BindException(new MapBindingResult(new HashMap<>(), "query"));
		RestaurantFilter filter = new RestaurantFilter();
		filter.setQ(q);
		filter.setCuisine(cuisine);
		if (filter.getCuisine() != null
				&& browse.cuisines().stream().map(Cuisine::getSlug).noneMatch(filter.getCuisine()::equals)) {
			errors.rejectValue("cuisine", "unknown", "There is no cuisine \"" + filter.getCuisine()
					+ "\". GET /api/v1/cuisines lists them.");
		}
		filter.setSort(sort);
		if (!filter.getSortOption().getParam().equals(sort)) {
			errors.rejectValue("sort", "unknown", "Sort by one of: " + SORTS + ".");
		}
		if (page < 1) {
			errors.rejectValue("page", "min", "Pages start at 1.");
		}
		if (errors.hasErrors()) {
			throw errors;
		}
		filter.setVeg(veg);
		filter.setTop(top);
		filter.setOpen(open);
		filter.setPage(page);
		return PageResponse.of(browse.browse(filter), card -> RestaurantSummary.of(card, baseUrl));
	}

	/** One approved restaurant with its menu; 404 for unknown and not approved ones. */
	@Operation(summary = "One restaurant with its menu",
			description = "Sections in menu order; sold-out dishes have `available: false`. 404 for unknown or "
					+ "unapproved restaurants.")
	@GetMapping("/restaurants/{slug}")
	RestaurantDetail restaurant(@PathVariable String slug) {
		return RestaurantDetail.of(menus.menu(slug, new MenuFilter()), images, baseUrl);
	}

	/** Restaurants and dishes matching {@code q} (required, at most 80 characters are used). */
	@Operation(summary = "Search restaurants and dishes",
			description = "`q` is required; only the first 80 characters are used.")
	@GetMapping("/search")
	SearchResponse search(@RequestParam(required = false) String q) throws BindException {
		String query = Requests.searchText(q, RestaurantFilter.MAX_QUERY_LENGTH);
		if (query == null) {
			BindException errors = new BindException(new MapBindingResult(new HashMap<>(), "query"));
			errors.rejectValue("q", "required", "Type a dish, restaurant or cuisine to search for.");
			throw errors;
		}
		return SearchResponse.of(search.search(query), baseUrl);
	}

}
