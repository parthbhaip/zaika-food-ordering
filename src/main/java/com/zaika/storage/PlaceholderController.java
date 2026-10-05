package com.zaika.storage;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.zaika.catalog.Slugs;
import com.zaika.common.NotFoundException;

/**
 * Generated SVG stand-ins for missing photos at {@code /placeholder/{kind}/{seed}.svg}. The seed is a slug (the
 * restaurant's slug, or a dish name slugified); the same seed always gives the same picture, its colours picked by a
 * hash of the seed.
 * <ul>
 * <li>{@code restaurant}: 16:10 cover with the name's initials</li>
 * <li>{@code logo}: square badge with the initials</li>
 * <li>{@code dish}: square with a simple food line drawing: a glass for drinks, a plate for thalis and meals, a
 * bowl otherwise</li>
 * </ul>
 */
@RestController
public class PlaceholderController {

	static final MediaType SVG = MediaType.parseMediaType("image/svg+xml");

	/** Background / ink pairs: warm tints with dark ink, every pair above 4.5:1. */
	static final List<String[]> PALETTE = List.of(
			new String[] { "#FFF1EB", "#B23A0C" },
			new String[] { "#FDF3DC", "#8A5200" },
			new String[] { "#E8F4EC", "#17663A" },
			new String[] { "#FBE9E7", "#9E2A20" },
			new String[] { "#EEF0FA", "#3D4A9E" },
			new String[] { "#F3ECF8", "#6B3FA0" },
			new String[] { "#E6F4F4", "#1F6464" },
			new String[] { "#F5EFE6", "#6B4E2E" });

	/** A bowl with steam. */
	static final String BOWL = "<path d=\"M128 208h144M136 208c4 44 30 72 64 72s60-28 64-72M176 296h48\"/>"
			+ "<path d=\"M168 172c-8-12 8-20 0-32M200 164c-8-12 8-20 0-32M232 172c-8-12 8-20 0-32\" opacity=\"0.6\"/>";

	/** A plate with fork and knife. */
	static final String PLATE = "<circle cx=\"200\" cy=\"200\" r=\"76\"/><circle cx=\"200\" cy=\"200\" r=\"48\" opacity=\"0.6\"/>"
			+ "<path d=\"M92 130v28a12 12 0 0 0 24 0v-28M104 130v142M308 130c-14 10-16 40-14 62h14M308 130v142\"/>";

	/** A glass with a straw. */
	static final String GLASS = "<path d=\"M150 140h100l-12 140a12 12 0 0 1-12 11h-52a12 12 0 0 1-12-11z\"/>"
			+ "<path d=\"M214 140l22-42M156 196h88\" opacity=\"0.6\"/>";

	/** Whole words of a slugified dish name (an optional plural "s" allowed) that mean a plate or a drink. */
	private static final Pattern PLATE_WORDS = Pattern.compile("(^|-)(thali|platter|meal|combo|plate)s?(-|$)");

	private static final Pattern DRINK_WORDS = Pattern.compile("(^|-)(lassi|chai|tea|coffee|juice|shake|milkshake|soda"
			+ "|sharbat|taak|chaas|buttermilk|sol-kadhi|solkadhi|mojito|cooler|falooda|latte|cappuccino|espresso|cold-brew"
			+ "|mastani)s?(-|$)");

	private static final Set<String> SKIPPED_WORDS = Set.of("the", "and", "of", "n");

	private static final int MAX_SEED_LENGTH = 100;

	@GetMapping("/placeholder/{kind}/{seed}.svg")
	ResponseEntity<String> placeholder(@PathVariable String kind, @PathVariable String seed) {
		if (seed.length() > MAX_SEED_LENGTH || !seed.matches(Slugs.PATTERN)) {
			throw new NotFoundException("Bad placeholder seed " + seed);
		}
		String svg = switch (kind) {
			case "restaurant" -> cover(seed);
			case "logo" -> logo(seed);
			case "dish" -> dish(seed);
			default -> throw new NotFoundException("Unknown placeholder kind " + kind);
		};
		return ResponseEntity.ok()
			.contentType(SVG)
			.cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
			.body(svg);
	}

	static String cover(String seed) {
		String[] c = colours(seed);
		return open(800, 500, c[0])
				+ "<circle cx=\"690\" cy=\"90\" r=\"170\" fill=\"" + c[1] + "\" opacity=\"0.07\"/>"
				+ "<circle cx=\"90\" cy=\"450\" r=\"130\" fill=\"" + c[1] + "\" opacity=\"0.05\"/>"
				+ text(400, 250, 168, c[1], initials(seed)) + "</svg>";
	}

	static String logo(String seed) {
		String[] c = colours(seed);
		return open(200, 200, c[0]) + text(100, 100, 84, c[1], initials(seed)) + "</svg>";
	}

	static String dish(String seed) {
		String[] c = colours(seed);
		return open(400, 400, c[0]) + "<g fill=\"none\" stroke=\"" + c[1]
				+ "\" stroke-width=\"10\" stroke-linecap=\"round\" stroke-linejoin=\"round\">" + dishGlyph(seed) + "</g></svg>";
	}

	/** The drawing follows the dish name: a glass for drinks, a plate for thalis and meals, a bowl for the rest. */
	static String dishGlyph(String seed) {
		if (PLATE_WORDS.matcher(seed).find()) {
			return PLATE;
		}
		return DRINK_WORDS.matcher(seed).find() ? GLASS : BOWL;
	}

	/** Up to two letters from the first words of the slug, skipping filler words: "the-kolhapur-kitchen" -> "KK". */
	static String initials(String seed) {
		List<String> words = Arrays.stream(seed.split("-")).filter(w -> !SKIPPED_WORDS.contains(w)).toList();
		if (words.isEmpty()) {
			words = List.of(seed.split("-"));
		}
		return words.stream().limit(2).map(w -> w.substring(0, 1)).collect(Collectors.joining()).toUpperCase(Locale.ROOT);
	}

	/** {@link String#hashCode()} is fixed by the Java spec, so a seed maps to the same colours on every server. */
	private static String[] colours(String seed) {
		return PALETTE.get(Math.floorMod(seed.hashCode(), PALETTE.size()));
	}

	private static String open(int width, int height, String background) {
		return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + width + " " + height + "\" width=\"" + width
				+ "\" height=\"" + height + "\" preserveAspectRatio=\"xMidYMid slice\"><rect width=\"" + width
				+ "\" height=\"" + height + "\" fill=\"" + background + "\"/>";
	}

	/** Initials are only [A-Z0-9] (from a validated slug), so they need no escaping. */
	private static String text(int x, int y, int size, String colour, String letters) {
		return "<text x=\"" + x + "\" y=\"" + y + "\" fill=\"" + colour + "\" font-size=\"" + size
				+ "\" font-weight=\"800\" font-family=\"'Plus Jakarta Sans', system-ui, sans-serif\""
				+ " text-anchor=\"middle\" dominant-baseline=\"central\" letter-spacing=\"2\">" + letters + "</text>";
	}

}
