package com.zaika;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.ZoneId;

import jakarta.mail.internet.MimeMessage;

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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.config.AppProperties;
import com.zaika.notification.LoggingMailSender;

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
	JavaMailSender mailSender;

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
	void appPropertiesAreBoundWithBusinessDefaults() {
		assertThat(app.brandName()).isEqualTo("Zaika");
		assertThat(app.zone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
		assertThat(app.uploadDir()).isEqualTo("./uploads");
		assertThat(app.seedDemo()).isFalse();
		assertThat(app.platformFee()).isEqualTo(new BigDecimal("5.00"));
		assertThat(app.gstRate()).isEqualByComparingTo("0.05");
		assertThat(app.freeDeliveryThreshold()).isEqualTo(new BigDecimal("499.00"));
		assertThat(app.riderBasePayout()).isEqualTo(new BigDecimal("30.00"));
	}

	@Test
	void mailIsOnlyLoggedInTests(CapturedOutput output) throws Exception {
		assertThat(mailSender).isInstanceOf(LoggingMailSender.class);

		SimpleMailMessage simple = new SimpleMailMessage();
		simple.setTo("customer@zaika.test");
		simple.setSubject("Your order is on its way");
		simple.setText("secret-link-body");
		mailSender.send(simple);

		MimeMessage mime = mailSender.createMimeMessage();
		MimeMessageHelper helper = new MimeMessageHelper(mime);
		helper.setTo("owner1@zaika.test");
		helper.setSubject("New order ZK-26-000123");
		helper.setText("secret-link-body");
		mailSender.send(mime);

		assertThat(output).contains("to=[customer@zaika.test] subject=\"Your order is on its way\"")
			.contains("to=[owner1@zaika.test] subject=\"New order ZK-26-000123\"")
			.doesNotContain("secret-link-body");
	}

}
