package com.zaika.storage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.zaika.catalog.MenuItem;
import com.zaika.catalog.Restaurant;
import com.zaika.catalog.Slugs;

/**
 * Picks the picture to show, as the template bean {@code img}:
 * {@code <img th:with="src=${@img.cover(restaurant)}" th:src="@{${src}}">} (Thymeleaf does not allow bean calls
 * inside {@code th:src} itself).
 * Order: the uploaded image, else a bundled seed photo if that file exists, else a generated placeholder from
 * {@link PlaceholderController}. Seed photos live in one folder per kind, so a dish can never pick up a restaurant's
 * cover: {@code /img/seed/cover/{slug}.jpg} and {@code /img/seed/logo/{slug}.jpg} by restaurant slug, and
 * {@code /img/seed/dish/{slug}.jpg} by slugified dish name, so one "Misal Pav" photo serves every menu that sells it.
 */
@Component("img")
public class ImageUrls {

	private static final String SEED_URL = "/img/seed/";

	/** Seed path ({@code kind/slug.jpg}) -> whether it ships with the app. The classpath never changes while running. */
	private final Map<String, Boolean> seedExists = new ConcurrentHashMap<>();

	public String cover(Restaurant restaurant) {
		return cover(restaurant.getCoverPath(), restaurant.getSlug());
	}

	/** For read models that carry the fields instead of the entity. */
	public String cover(String uploadedPath, String slug) {
		return pick(uploadedPath, "cover", slug, slug, "restaurant");
	}

	public String logo(Restaurant restaurant) {
		return logo(restaurant.getLogoPath(), restaurant.getSlug());
	}

	public String logo(String uploadedPath, String slug) {
		return pick(uploadedPath, "logo", slug, slug, "logo");
	}

	public String dish(MenuItem item) {
		return dish(item.getImagePath(), item.getName());
	}

	public String dish(String uploadedPath, String dishName) {
		String slug = Slugs.slugify(dishName);
		return pick(uploadedPath, "dish", slug, slug.isEmpty() ? "dish" : slug, "dish");
	}

	private String pick(String uploadedPath, String seedFolder, String seedName, String placeholderSeed,
			String placeholderKind) {
		if (uploadedPath != null && !uploadedPath.isBlank()) {
			return StorageService.URL_PREFIX + uploadedPath;
		}
		String seedPath = seedFolder + "/" + seedName + ".jpg";
		if (!seedName.isEmpty() && seedExists.computeIfAbsent(seedPath, this::onClasspath)) {
			return SEED_URL + seedPath;
		}
		return "/placeholder/" + placeholderKind + "/" + placeholderSeed + ".svg";
	}

	private boolean onClasspath(String seedPath) {
		return new ClassPathResource("static" + SEED_URL + seedPath).exists();
	}

}
