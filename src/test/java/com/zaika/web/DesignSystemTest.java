package com.zaika.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Design-system assets: the UI kit is hidden outside dev, every asset is served, the icon sprite and fonts are
 * complete, and app.css takes all its colours and sizes from tokens.css.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@WithMockUser
class DesignSystemTest {

	static final List<String> REQUIRED_ICONS = List.of("search", "cart", "user", "location", "clock", "star", "heart",
			"plus", "minus", "trash", "chevron-down", "chevron-right", "check", "x", "menu", "bell", "filter", "bike",
			"store", "receipt", "logout", "edit", "upload", "phone", "info", "leaf");

	static final List<Integer> FONT_WEIGHTS = List.of(400, 500, 600, 700, 800);

	@Autowired
	MockMvc mvc;

	@Test
	void uiKitIsNotFoundOutsideTheDevProfile() throws Exception {
		mvc.perform(get("/dev/ui-kit")).andExpect(status().isNotFound());
	}

	@Test
	void designSystemAssetsAreServed() throws Exception {
		for (String path : List.of("/css/tokens.css", "/css/app.css", "/img/icons.svg", "/img/logo.svg",
				"/img/favicon.svg", "/img/placeholder-food.svg")) {
			mvc.perform(get(path)).andExpect(status().isOk());
		}
		for (int weight : FONT_WEIGHTS) {
			mvc.perform(get("/fonts/plus-jakarta-sans-latin-" + weight + "-normal.woff2")).andExpect(status().isOk());
		}
	}

	@Test
	void iconSpriteHasEveryIconOnTheSharedGrid() throws Exception {
		Document sprite = parse("static/img/icons.svg");
		NodeList symbols = sprite.getElementsByTagName("symbol");
		Set<String> ids = new HashSet<>();
		for (int i = 0; i < symbols.getLength(); i++) {
			Element symbol = (Element) symbols.item(i);
			String id = symbol.getAttribute("id");
			assertThat(ids.add(id)).as("duplicate icon %s", id).isTrue();
			assertThat(symbol.getAttribute("viewBox")).as(id).isEqualTo("0 0 24 24");
			assertThat(symbol.getAttribute("stroke")).as(id).isEqualTo("currentColor");
			assertThat(symbol.getAttribute("stroke-width")).as(id).isEqualTo("1.75");
		}
		assertThat(ids).containsAll(REQUIRED_ICONS);
	}

	@Test
	void logoAndFaviconAreSvgsWithAViewBox() throws Exception {
		Element logo = parse("static/img/logo.svg").getDocumentElement();
		assertThat(logo.getAttribute("viewBox")).isNotBlank();
		assertThat(logo.getElementsByTagName("title").item(0).getTextContent()).isEqualTo("Zaika");
		assertThat(parse("static/img/favicon.svg").getDocumentElement().getAttribute("viewBox")).isEqualTo("0 0 40 40");
	}

	@Test
	void plusJakartaSansIsSelfHostedWithSwap() throws Exception {
		String css = read("static/css/app.css");
		for (int weight : FONT_WEIGHTS) {
			String file = "plus-jakarta-sans-latin-" + weight + "-normal.woff2";
			try (InputStream in = new ClassPathResource("static/fonts/" + file).getInputStream()) {
				assertThat(new String(in.readNBytes(4), StandardCharsets.US_ASCII)).as(file).isEqualTo("wOF2");
			}
			assertThat(css).containsPattern("font-weight: " + weight + "; font-display: swap; src: url\\(\"../fonts/" + file + "\"\\)");
		}
		assertThat(read("static/css/tokens.css")).contains("--font-sans: \"Plus Jakarta Sans\", system-ui");
	}

	@Test
	void appCssUsesOnlyTokensForColours() throws Exception {
		String css = read("static/css/app.css");
		assertThat(css).doesNotContainPattern("#[0-9a-fA-F]{3,8}\\b").doesNotContainPattern("\\b(rgba?|hsla?)\\(");

		Set<String> defined = new HashSet<>();
		collect(Pattern.compile("(--[a-z0-9-]+)\\s*:"), read("static/css/tokens.css") + css, defined);
		Set<String> used = new HashSet<>();
		collect(Pattern.compile("var\\((--[a-z0-9-]+)"), css, used);
		assertThat(defined).as("every var() in app.css is defined").containsAll(used);
	}

	@Test
	void appCssStylesEveryComponentAndState() throws Exception {
		String css = read("static/css/app.css");
		assertThat(css).contains(".btn-primary", ".btn-secondary", ".btn-ghost", ".btn-danger", ".btn-sm", ".btn-lg",
				".btn:disabled", ".btn.is-loading", ".btn.htmx-request", ".htmx-indicator", ".input", ".select", ".textarea",
				".field-label", ".field-help", ".field-error", ".checkbox", ".radio", ".switch", ".card", ".restaurant-card",
				".menu-item", ".qty", ".badge", ".chip", ".rating-pill", ".veg-mark", ".veg-mark.non-veg", ".tabs", ".tab",
				".stepper", ".step.is-current", ".table", ".pagination", ".alert", ".toast", ".modal", "::backdrop",
				".empty-state", ".skeleton", ".cart-bar", ".skip-link", ":hover", ":focus-visible", ":disabled",
				"prefers-reduced-motion", "@media (min-width: 640px)", "@media (min-width: 960px)",
				"@media (min-width: 1200px)");
	}

	@Test
	void focusRingIsAnOutlineNoComponentCanCancel() throws Exception {
		String css = read("static/css/app.css").replaceAll("(?s)/\\*.*?\\*/", "");
		assertThat(rule(css, ":focus-visible")).isEqualTo("outline: var(--focus-outline); outline-offset: 2px;");
		assertThat(read("static/css/tokens.css")).contains("--focus-outline: 2px solid var(--color-primary);");
		// Only the stretched card link drops its own outline, because its ::after draws the ring round the card.
		Matcher block = Pattern.compile("([^{}]+)\\{([^{}]*)\\}").matcher(css);
		while (block.find()) {
			if (block.group(2).matches("(?s).*outline:\\s*(none|0)\\b.*")) {
				assertThat(block.group(1).trim()).isEqualTo(".restaurant-card-link:focus-visible");
			}
		}
		assertThat(rule(css, ".restaurant-card-link:focus-visible::after")).contains("outline: var(--focus-outline)");
	}

	@Test
	void colourPairsMeetWcagAa() throws Exception {
		Map<String, String> tokens = tokens();
		String css = read("static/css/app.css");
		// text: 4.5:1
		assertThat(rule(css, "a")).contains("color: var(--color-link)");
		assertThat(rule(css, ".btn-ghost")).contains("color: var(--color-link)");
		assertThat(contrast(tokens, "--color-link", "--color-bg")).isGreaterThanOrEqualTo(4.5);
		assertThat(contrast(tokens, "--color-link", "--color-surface")).isGreaterThanOrEqualTo(4.5);
		assertThat(contrast(tokens, "--color-on-primary", "--color-primary")).isGreaterThanOrEqualTo(4.5);
		assertThat(rule(css, ".restaurant-card-closed")).contains("background: var(--color-ink)", "color: var(--color-on-primary)")
			.doesNotContain("gradient");
		assertThat(contrast(tokens, "--color-on-primary", "--color-ink")).isGreaterThanOrEqualTo(4.5);
		// graphics and focus ring: 3:1
		assertThat(rule(css, ".stars")).contains("color: var(--color-input-border)");
		assertThat(rule(css, ".stars .is-on")).contains("color: var(--color-star)");
		assertThat(contrast(tokens, "--color-star", "--color-surface")).isGreaterThanOrEqualTo(3);
		assertThat(contrast(tokens, "--color-input-border", "--color-surface")).isGreaterThanOrEqualTo(3);
		assertThat(contrast(tokens, "--color-primary", "--color-bg")).isGreaterThanOrEqualTo(3);
	}

	@Test
	void disabledAddButtonHasNoHoverState() throws Exception {
		String css = read("static/css/app.css");
		assertThat(rule(css, ".btn-add:hover")).isNull();
		assertThat(rule(css, ".btn-add:hover:not(:disabled)")).isNotNull();
	}

	/** Body of the rule whose selector is exactly {@code selector}, or null. */
	private static String rule(String css, String selector) {
		Matcher m = Pattern.compile("(?m)^" + Pattern.quote(selector) + " \\{([^}]*)\\}").matcher(css);
		return m.find() ? m.group(1).trim() : null;
	}

	private static Map<String, String> tokens() throws Exception {
		Map<String, String> tokens = new HashMap<>();
		Matcher m = Pattern.compile("(--[a-z0-9-]+):\\s*([^;]+);").matcher(read("static/css/tokens.css"));
		while (m.find()) {
			tokens.put(m.group(1), m.group(2).trim());
		}
		return tokens;
	}

	private static double contrast(Map<String, String> tokens, String fg, String bg) {
		double a = luminance(hex(tokens, fg));
		double b = luminance(hex(tokens, bg));
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}

	private static String hex(Map<String, String> tokens, String name) {
		String value = tokens.get(name);
		assertThat(value).as(name).isNotNull();
		return value.startsWith("var(") ? hex(tokens, value.substring(4, value.length() - 1)) : value;
	}

	private static double luminance(String hex) {
		assertThat(hex).matches("#[0-9A-Fa-f]{6}");
		double[] c = new double[3];
		for (int i = 0; i < 3; i++) {
			double v = Integer.parseInt(hex.substring(1 + 2 * i, 3 + 2 * i), 16) / 255.0;
			c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
	}

	private static void collect(Pattern pattern, String text, Set<String> into) {
		Matcher m = pattern.matcher(text);
		while (m.find()) {
			into.add(m.group(1));
		}
	}

	private static String read(String path) throws Exception {
		return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
	}

	private static Document parse(String path) throws Exception {
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		try (InputStream in = new ClassPathResource(path).getInputStream()) {
			return factory.newDocumentBuilder().parse(in);
		}
	}

}
