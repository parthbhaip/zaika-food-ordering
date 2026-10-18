package com.zaika.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.zaika.catalog.Slugs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Attribution for the bundled seed photos ({@code static/img/seed/cover|dish}), read once at startup from
 * {@value #RESOURCE}. Each photo is shown with the name of the seeded restaurant or dish it stands for, taken from
 * {@link DemoData} (so it also works when the demo data is not loaded), else the slug in words.
 */
@Component
public class SeedPhotoCredits {

	static final String RESOURCE = "seed-photo-credits.json";

	/** One photo as stored in the JSON file. {@code match} is "exact", or "generic" for a similar dish. */
	public record Credit(String kind, String slug, String file, String title, String author, String license,
			String licenseUrl, String sourceUrl, String match) {

		public boolean generic() {
			return "generic".equals(match);
		}

	}

	/** A credit with the name of what the photo is used for. */
	public record Entry(String name, Credit credit) {
	}

	private final List<Entry> restaurants;

	private final List<Entry> dishes;

	public SeedPhotoCredits(ObjectMapper json) {
		List<Credit> credits = read(json);
		Map<String, String> restaurantNames = new HashMap<>();
		Map<String, String> dishNames = new HashMap<>();
		for (DemoData.Place place : DemoData.RESTAURANTS) {
			restaurantNames.put(place.slug(), place.name());
			place.menu().forEach(section -> section.dishes()
				.forEach(dish -> dishNames.putIfAbsent(Slugs.slugify(dish.name()), dish.name())));
		}
		this.restaurants = entries(credits, "cover", restaurantNames);
		this.dishes = entries(credits, "dish", dishNames);
	}

	static List<Credit> read(ObjectMapper json) {
		try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
			return json.readValue(in, new TypeReference<List<Credit>>() {
			});
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + RESOURCE, ex);
		}
	}

	private static List<Entry> entries(List<Credit> credits, String kind, Map<String, String> names) {
		return credits.stream()
			.filter(c -> kind.equals(c.kind()))
			.map(c -> new Entry(names.getOrDefault(c.slug(), humanise(c.slug())), c))
			.toList();
	}

	/** "sitaphal-ice-cream" -> "Sitaphal ice cream". */
	static String humanise(String slug) {
		String words = slug.replace('-', ' ');
		return words.isEmpty() ? words : Character.toUpperCase(words.charAt(0)) + words.substring(1);
	}

	public List<Entry> restaurants() {
		return restaurants;
	}

	public List<Entry> dishes() {
		return dishes;
	}

}
