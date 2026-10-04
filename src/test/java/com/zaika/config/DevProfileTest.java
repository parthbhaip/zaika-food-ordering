package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import com.zaika.notification.LoggingMailSender;

/**
 * The dev profile boots with its own settings. The datasource is swapped for an in-memory one so the test
 * never writes ./data; the real file URL is checked straight from application-dev.properties.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:devprofile;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("dev")
class DevProfileTest {

	@Autowired
	Environment env;

	@Autowired
	AppProperties app;

	@Autowired
	JavaMailSender mailSender;

	@Autowired
	ApplicationContext context;

	@Test
	void devProfileSeedsDemoDataAndOnlyLogsMail() {
		assertThat(env.getActiveProfiles()).containsExactly("dev");
		assertThat(app.seedDemo()).isTrue();
		assertThat(mailSender).isInstanceOf(LoggingMailSender.class);
	}

	@Test
	void devProfileRegistersTheH2Console() {
		assertThat(context.getBeansOfType(ServletRegistrationBean.class).values())
			.anySatisfy(registration -> assertThat(registration.getUrlMappings()).contains("/h2-console/*"));
	}

	@Test
	void devDatabaseIsAnH2FileInMySqlMode() throws Exception {
		Properties dev = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-dev.properties"));
		assertThat(dev.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:h2:file:./data/zaika;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
	}

}
