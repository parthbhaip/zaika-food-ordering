package com.zaika;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ErrorProperties.IncludeAttribute;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.config.AppProperties;
import com.zaika.notification.LoggingMailService;
import com.zaika.notification.MailService;

/** The application context starts on the test profile with the shared settings from application.properties. */
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class ZaikaApplicationTests {

	@Autowired
	Environment env;

	@Autowired
	AppProperties app;

	@Autowired
	MailService mail;

	@Autowired
	WebProperties webProperties;

	@Autowired
	ApplicationContext context;

	@Test
	void contextLoadsOnTestProfileWithInMemoryH2() {
		assertThat(env.getActiveProfiles()).containsExactly("test");
		assertThat(env.getProperty("spring.datasource.url")).startsWith("jdbc:h2:mem:zaika;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
		assertThat(env.getProperty("spring.test.database.replace")).isEqualTo("none");
	}

	@Test
	void sharedSettingsApplyToEveryProfile() {
		assertThat(env.getProperty("spring.application.name")).isEqualTo("Zaika");
		assertThat(env.getProperty("spring.profiles.default")).isEqualTo("dev");
		assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
		assertThat(env.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
		assertThat(env.getProperty("server.compression.enabled", Boolean.class)).isTrue();
		assertThat(env.getProperty("server.compression.mime-types")).contains("text/html", "text/css", "application/javascript");
		assertThat(env.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
	}

	@Test
	void whitelabelErrorPageIsOffAndStackTracesAreNeverShown() {
		assertThat(webProperties.getError().getWhitelabel().isEnabled()).isFalse();
		assertThat(webProperties.getError().getIncludeStacktrace()).isEqualTo(IncludeAttribute.NEVER);
		// ErrorMvcAutoConfiguration registers its whitelabel View under this name only when the page is enabled
		assertThat(context.containsBean("error")).isFalse();
	}

	@Test
	void appPropertiesAreBoundWithBusinessDefaults() throws Exception {
		assertThat(app.brandName()).isEqualTo("Zaika");
		assertThat(app.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		// the shared default; the test profile keeps uploads written by tests inside the build output
		assertThat(PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"))
			.getProperty("app.upload-dir")).isEqualTo("./uploads");
		assertThat(app.uploadDir()).isEqualTo("./target/test-uploads");
		assertThat(app.seedDemo()).isFalse();
		assertThat(app.platformFee()).isEqualTo(new BigDecimal("5.00"));
		assertThat(app.gstRate()).isEqualByComparingTo("0.05");
		assertThat(app.freeDeliveryThreshold()).isEqualTo(new BigDecimal("499.00"));
		assertThat(app.riderBasePayout()).isEqualTo(new BigDecimal("30.00"));
		assertThat(app.baseUrl()).isEqualTo("http://localhost:8080");
	}

	@Test
	void demoDataIsNotLoadedWhenSeedingIsOff() {
		// app.seed-demo=false here (and by default in mysql): the seeder bean does not even exist
		assertThat(context.containsBean("demoDataSeeder")).isFalse();
	}

	@Test
	void mailIsOnlyLoggedInTests(CapturedOutput output) {
		assertThat(context.getBeansOfType(MailService.class).values()).singleElement().isInstanceOf(LoggingMailService.class);
		assertThat(context.getBeansOfType(JavaMailSender.class)).as("no SMTP sender outside the mysql profile").isEmpty();

		mail.send("customer@zaika.test", "Your order is on its way", "Track it at http://localhost:8080/orders/ZK-26-000123/track");

		// The body is logged on purpose (dev and test only): developers open reset links from the log
		assertThat(output).contains("[mail not sent: log-only] to=customer@zaika.test subject=\"Your order is on its way\"")
			.contains("http://localhost:8080/orders/ZK-26-000123/track");
	}

}
