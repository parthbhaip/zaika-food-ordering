package com.zaika.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.zaika.notification.MailService;
import com.zaika.notification.SmtpMailService;

/**
 * The mysql profile takes the database, SMTP and seed settings from environment variables. Here the variables
 * are supplied as test properties and point at in-memory H2, so no MySQL server is needed. Every variable is pinned
 * (test properties beat real env vars and .env), so the result does not depend on the shell the build runs in;
 * the fallback defaults are checked by resolving application-mysql.properties with no variables set.
 */
@SpringBootTest(properties = { "DB_URL=jdbc:h2:mem:mysqlprofile;MODE=MySQL;DATABASE_TO_LOWER=TRUE", "DB_USER=sa",
		"DB_PASSWORD=", "SMTP_HOST=smtp.zaika.test", "SMTP_PORT=2525", "SMTP_USERNAME=mailer", "SMTP_PASSWORD=",
		"SMTP_AUTH=false", "SMTP_STARTTLS=false", "MAIL_FROM=Zaika Test <mailer@zaika.test>", "APP_BASE_URL=https://zaika.example.in/", "SEED_DEMO=false", "REMEMBER_ME_KEY=mysql-profile-test-key",
		"ADMIN_EMAIL=", "ADMIN_PASSWORD=" })
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
class MysqlProfileTest {

	private static final Pattern ENV_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)");

	@Autowired
	Environment env;

	@Autowired
	ApplicationContext context;

	@Autowired
	MockMvc mvc;

	@Test
	void databaseCredentialsComeFromEnvironmentVariables() {
		assertThat(env.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:mysqlprofile;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
		assertThat(env.getProperty("spring.datasource.username")).isEqualTo("sa");
	}

	@Test
	void smtpSenderIsConfiguredFromEnvironmentVariables() {
		assertThat(context.getBeansOfType(JavaMailSender.class)).hasSize(1);
		JavaMailSenderImpl sender = context.getBean(JavaMailSenderImpl.class);
		assertThat(sender.getHost()).isEqualTo("smtp.zaika.test");
		assertThat(sender.getPort()).isEqualTo(2525);
		assertThat(sender.getUsername()).isEqualTo("mailer");
		assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.auth", "false")
			.containsEntry("mail.smtp.starttls.enable", "false");
		assertThat(context.getBean(MailService.class)).isInstanceOf(SmtpMailService.class);
		assertThat(AopUtils.isAopProxy(context.getBean(MailService.class))).as("@Async proxy: mail is sent off the request thread").isTrue();
		assertThat(env.getProperty("app.mail.from")).isEqualTo("Zaika Test <mailer@zaika.test>");
		assertThat(context.getBean(AppProperties.class).baseUrl()).as("trailing slash dropped").isEqualTo("https://zaika.example.in");
	}

	@Test
	void withoutEnvironmentVariablesDemoSeedIsOffAndSmtpUsesSafeDefaults() throws Exception {
		Properties mysql = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-mysql.properties"));
		PropertySourcesPropertyResolver noVariables = new PropertySourcesPropertyResolver(new MutablePropertySources());

		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("app.seed-demo"))).isEqualTo("false");
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("spring.mail.host"))).isEqualTo("localhost");
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("spring.mail.port"))).isEqualTo("587");
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("app.mail.from"))).isEqualTo("Zaika <no-reply@zaika.test>");
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("app.base-url"))).isEqualTo("http://localhost:8080");
		// no built-in admin password outside dev: without ADMIN_EMAIL / ADMIN_PASSWORD no admin is created
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("app.admin.email"))).isEmpty();
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("app.admin.password"))).isEmpty();
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("spring.mail.properties.mail.smtp.auth"))).isEqualTo("true");
		assertThat(noVariables.resolvePlaceholders(mysql.getProperty("spring.mail.properties.mail.smtp.starttls.enable")))
			.isEqualTo("true");
	}

	@Test
	void h2ConsoleIsNotRegistered() {
		assertThat(env.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
		assertThat(context.getBeansOfType(ServletRegistrationBean.class).values())
			.noneSatisfy(registration -> assertThat(registration.getUrlMappings()).contains("/h2-console/*"));
	}

	@Test
	void devOnlyUiKitIsNotRegistered() {
		List<String> patterns = context.getBeansOfType(RequestMappingHandlerMapping.class).values().stream()
			.flatMap(mapping -> mapping.getHandlerMethods().keySet().stream())
			.flatMap(info -> info.getPatternValues().stream())
			.toList();
		assertThat(patterns).isNotEmpty().noneMatch(pattern -> pattern.startsWith("/dev"));
	}

	@Test
	void devToolsStayBehindTheLoginInTheMysqlProfile() throws Exception {
		mvc.perform(get("/dev/ui-kit")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		mvc.perform(get("/h2-console/")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
		assertThat(env.getProperty("app.remember-me-key")).isEqualTo("mysql-profile-test-key");
	}

	@Test
	void envExampleDocumentsEveryEnvironmentVariableWithAComment() throws Exception {
		Properties mysql = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-mysql.properties"));
		List<String> lines = Files.readAllLines(Path.of(".env.example"));
		List<String> referenced = mysql.values().stream().map(String::valueOf)
			.flatMap(value -> ENV_PLACEHOLDER.matcher(value).results().map(m -> m.group(1)))
			.toList();

		assertThat(referenced).contains("DB_URL", "DB_USER", "DB_PASSWORD", "SMTP_HOST", "SEED_DEMO", "ADMIN_EMAIL",
				"ADMIN_PASSWORD");
		for (String name : referenced) {
			int line = indexOfKey(lines, name);
			assertThat(line).as("%s is listed in .env.example", name).isPositive();
			assertThat(lines.get(line - 1)).as("%s has a comment above it", name).startsWith("#");
		}
	}

	private static int indexOfKey(List<String> lines, String name) {
		for (int i = 0; i < lines.size(); i++) {
			if (lines.get(i).startsWith(name + "=")) {
				return i;
			}
		}
		return -1;
	}

}
