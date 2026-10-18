package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.zaika.catalog.Slugs;
import com.zaika.config.SeedPhotoCredits.Credit;

import tools.jackson.databind.json.JsonMapper;

/**
 * Every seeded restaurant and dish ships with a cropped photo and a credit entry, and every credit is complete, under
 * a license we may use, and points at a photo within the size budget. Adding a seed item without both fails here.
 */
class SeedPhotosTest {

	/** Seed slugs that genuinely have no photo yet ("cover/slug" or "dish/slug"). Empty: every seed item has one. */
	static final Set<String> NO_PHOTO = Set.of();

	/** CC0, public domain, CC BY or CC BY-SA in any version (with an optional port, e.g. "CC BY-SA 2.5 in"). */
	static final Pattern ALLOWED_LICENSE = Pattern.compile("CC0|Public domain|CC BY(-SA)? \\d\\.\\d( [a-z]{2})?");

	static final Path SEED_DIR = Path.of("src/main/resources/static/img/seed");

	final List<Credit> credits = SeedPhotoCredits.read(JsonMapper.builder().build());

	@Test
	void everySeededRestaurantAndDishHasAPhotoAndACredit() {
		for (DemoData.Place place : DemoData.RESTAURANTS) {
			assertPhotoAndCredit("cover", place.slug());
			place.menu().forEach(section -> section.dishes()
				.forEach(dish -> assertPhotoAndCredit("dish", Slugs.slugify(dish.name()))));
		}
	}

	private void assertPhotoAndCredit(String kind, String slug) {
		String file = kind + "/" + slug + ".jpg";
		if (NO_PHOTO.contains(kind + "/" + slug)) {
			return;
		}
		assertThat(new ClassPathResource("static/img/seed/" + file).exists()).as("photo " + file).isTrue();
		assertThat(credits).as("credit for " + file).anyMatch(c -> c.kind().equals(kind) && c.slug().equals(slug));
	}

	@Test
	void everyCreditIsCompleteUnderAnAllowedLicenseAndPointsAtItsPhoto() {
		assertThat(credits).hasSizeGreaterThan(100);
		assertThat(credits.stream().map(Credit::file)).doesNotHaveDuplicates();
		for (Credit c : credits) {
			assertThat(c.kind()).as(c.slug()).isIn("cover", "dish");
			assertThat(c.file()).isEqualTo(c.kind() + "/" + c.slug() + ".jpg");
			assertThat(new ClassPathResource("static/img/seed/" + c.file()).exists()).as(c.file()).isTrue();
			assertThat(c.title()).as(c.file()).isNotBlank();
			assertThat(c.author()).as(c.file()).isNotBlank();
			assertThat(c.sourceUrl()).as(c.file()).startsWith("https://commons.wikimedia.org/wiki/File:");
			assertThat(c.licenseUrl()).as(c.file()).startsWith("http");
			assertThat(c.license()).as(c.file()).matches(ALLOWED_LICENSE).doesNotContain("NC", "ND");
			assertThat(c.match()).as(c.file()).isIn("exact", "generic");
		}
	}

	@Test
	void everyBundledPhotoHasACredit() throws IOException {
		Set<String> credited = Set.copyOf(credits.stream().map(Credit::file).toList());
		try (Stream<Path> files = Files.walk(SEED_DIR)) {
			List<String> photos = files.filter(Files::isRegularFile)
				.map(p -> SEED_DIR.relativize(p).toString().replace('\\', '/'))
				.toList();
			assertThat(photos).isNotEmpty().allMatch(credited::contains);
		}
	}

	@Test
	void photosAreCroppedAndWithinTheSizeBudget() throws IOException {
		for (Credit c : credits) {
			ClassPathResource photo = new ClassPathResource("static/img/seed/" + c.file());
			boolean cover = c.kind().equals("cover");
			assertThat(photo.contentLength()).as(c.file()).isLessThanOrEqualTo((cover ? 220 : 90) * 1024L);
			try (InputStream in = photo.getInputStream()) {
				BufferedImage image = ImageIO.read(in);
				assertThat(image).as(c.file() + " is a readable JPEG").isNotNull();
				assertThat(image.getWidth()).as(c.file()).isEqualTo(cover ? 960 : 400);
				assertThat(image.getHeight()).as(c.file()).isEqualTo(cover ? 600 : 400);
			}
		}
	}

	@Test
	void namesComeFromTheSeedDefinitionsElseTheSlugInWords() {
		SeedPhotoCredits page = new SeedPhotoCredits(JsonMapper.builder().build());

		assertThat(page.restaurants()).hasSize(DemoData.RESTAURANTS.size())
			.anySatisfy(e -> assertThat(e.name()).isEqualTo("Rassa Darbar"));
		assertThat(page.dishes()).anySatisfy(e -> assertThat(e.name()).isEqualTo("Mutton Thali"));
		assertThat(page.restaurants().size() + page.dishes().size()).isEqualTo(credits.size());
		assertThat(SeedPhotoCredits.humanise("sitaphal-ice-cream")).isEqualTo("Sitaphal ice cream");
	}

}
