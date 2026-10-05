package com.zaika.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** /placeholder/{kind}/{seed}.svg: public, cacheable, deterministic SVG stand-ins for missing photos. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PlaceholderControllerTest {

	static final List<String> SEEDS = List.of("panchganga-misal-house", "rankala-tandoor", "shahupuri-chai-co",
			"tarabai-park-thali", "mahadwar-road-biryani", "kolhapuri-tambda-rassa", "rajarampuri-cafe",
			"bhavani-mandap-sweets", "nagala-park-pizza", "the-kolhapur-kitchen", "misal-pav", "paneer-tikka");

	@Autowired
	MockMvc mvc;

	@Test
	void restaurantCoverIs16By10WithTheInitialsAndIsCachedForAWeek() throws Exception {
		String svg = mvc.perform(get("/placeholder/restaurant/panchganga-misal-house.svg"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith("image/svg+xml"))
			.andExpect(header().string("Cache-Control", "max-age=604800, public"))
			.andReturn().getResponse().getContentAsString();

		Element root = parse(svg).getDocumentElement();
		assertThat(root.getTagName()).isEqualTo("svg");
		assertThat(root.getAttribute("viewBox")).isEqualTo("0 0 800 500");
		assertThat(root.getElementsByTagName("text").item(0).getTextContent()).isEqualTo("PM");
	}

	@Test
	void logoIsSquareWithTheInitialsSkippingFillerWords() throws Exception {
		Element root = parse(fetch("/placeholder/logo/the-kolhapur-kitchen.svg")).getDocumentElement();
		assertThat(root.getAttribute("viewBox")).isEqualTo("0 0 200 200");
		assertThat(root.getElementsByTagName("text").item(0).getTextContent()).isEqualTo("KK");
	}

	@Test
	void dishIsASquareFoodDrawingWithoutText() throws Exception {
		Document doc = parse(fetch("/placeholder/dish/misal-pav.svg"));
		assertThat(doc.getDocumentElement().getAttribute("viewBox")).isEqualTo("0 0 400 400");
		assertThat(doc.getElementsByTagName("text").getLength()).isZero();
		assertThat(doc.getElementsByTagName("path").getLength()).isPositive();
	}

	@Test
	void theSameSeedAlwaysGivesTheSamePictureAndSeedsSpreadOverColoursAndGlyphs() throws Exception {
		assertThat(fetch("/placeholder/restaurant/rankala-tandoor.svg"))
			.isEqualTo(fetch("/placeholder/restaurant/rankala-tandoor.svg"));

		Set<String> backgrounds = new HashSet<>();
		Set<String> glyphs = new HashSet<>();
		for (String seed : SEEDS) {
			backgrounds.add(((Element) parse(fetch("/placeholder/restaurant/" + seed + ".svg"))
				.getElementsByTagName("rect").item(0)).getAttribute("fill"));
			String dish = fetch("/placeholder/dish/" + seed + ".svg");
			glyphs.add(dish.substring(dish.indexOf("<g ")));
		}
		assertThat(backgrounds).hasSizeGreaterThanOrEqualTo(4);
		assertThat(glyphs).hasSizeGreaterThanOrEqualTo(2);
	}

	@Test
	void placeholdersAreInertImagesWithNoScriptOrExternalReferences() throws Exception {
		for (String kind : List.of("restaurant", "logo", "dish")) {
			String svg = fetch("/placeholder/" + kind + "/panchganga-misal-house.svg");
			assertThat(svg).doesNotContain("<script", "foreignObject", "href", " on");
		}
	}

	@Test
	void unknownKindsAndMalformedSeedsAre404() throws Exception {
		for (String url : List.of("/placeholder/menu/misal.svg", "/placeholder/restaurant/Misal.svg",
				"/placeholder/restaurant/misal--house.svg", "/placeholder/restaurant/-misal.svg",
				"/placeholder/logo/misal_house.svg", "/placeholder/dish/" + "a".repeat(101) + ".svg",
				"/placeholder/dish/misal.png")) {
			mvc.perform(get(url)).andExpect(status().isNotFound());
		}
		mvc.perform(get("/placeholder/dish/" + "a".repeat(100) + ".svg")).andExpect(status().isOk());
	}

	@Test
	void initialsComeFromTheFirstTwoWordsThatAreNotFiller() {
		assertThat(PlaceholderController.initials("panchganga-misal-house")).isEqualTo("PM");
		assertThat(PlaceholderController.initials("the-kolhapur-kitchen")).isEqualTo("KK");
		assertThat(PlaceholderController.initials("misal")).isEqualTo("M");
		assertThat(PlaceholderController.initials("7-spice-street")).isEqualTo("7S");
		assertThat(PlaceholderController.initials("the")).isEqualTo("T");
		assertThat(PlaceholderController.initials("rassa-and-co")).isEqualTo("RC");
	}

	@Test
	void everyPaletteInkMeetsWcagAaOnItsBackground() {
		for (String[] pair : PlaceholderController.PALETTE) {
			assertThat(contrast(pair[1], pair[0])).as("%s on %s", pair[1], pair[0]).isGreaterThanOrEqualTo(4.5);
		}
	}

	private String fetch(String url) throws Exception {
		return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static Document parse(String svg) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		return factory.newDocumentBuilder().parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
	}

	private static double contrast(String fg, String bg) {
		double a = luminance(fg);
		double b = luminance(bg);
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}

	private static double luminance(String hex) {
		double[] rgb = new double[3];
		for (int i = 0; i < 3; i++) {
			double c = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
			rgb[i] = c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2];
	}

}
