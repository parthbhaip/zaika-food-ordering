package com.zaika.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

import java.util.List;

import jakarta.validation.Validator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first admin is created on startup from ADMIN_EMAIL / ADMIN_PASSWORD. Runs on its own in-memory database, so
 * "no admin exists" is true at startup whatever other test classes created. Tests that remove the admin do it inside
 * a rolled-back transaction.
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:adminbootstrap;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"app.admin.email=Boss@Zaika.TEST", "app.admin.password=Kolhapur@2026" })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminBootstrapTest {

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	AdminBootstrap bootstrap;

	@Autowired
	Validator validator;

	@Autowired
	MockMvc mvc;

	@Test
	void startupCreatedTheAdminWhoCanSignIn() throws Exception {
		User admin = users.findByEmail("boss@zaika.test").orElseThrow();
		assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
		assertThat(admin.isEnabled()).isTrue();
		assertThat(admin.getName()).isEqualTo("Zaika Admin");
		assertThat(admin.getPasswordHash()).startsWith("$2").doesNotContain("Kolhapur");
		assertThat(passwordEncoder.matches("Kolhapur@2026", admin.getPasswordHash())).isTrue();

		mvc.perform(post("/login").param("email", "boss@zaika.test").param("password", "Kolhapur@2026").with(csrf()))
			.andExpect(redirectedUrl("/admin"))
			.andExpect(authenticated().withRoles("ADMIN"));
	}

	@Test
	void restartingDoesNotCreateASecondAdminOrTouchThePassword() throws Exception {
		String hash = users.findByEmail("boss@zaika.test").orElseThrow().getPasswordHash();
		bootstrap("boss@zaika.test", "Different@99").run(null);
		bootstrap.run(null);

		assertThat(admins()).hasSize(1);
		assertThat(users.findByEmail("boss@zaika.test").orElseThrow().getPasswordHash()).isEqualTo(hash);
	}

	@Test
	@Transactional
	void anyExistingAdminIsEnoughEvenWithADifferentEmail() throws Exception {
		users.deleteAll(admins());
		users.save(new User("Other Admin", "other.admin@zaika.test", "9123456780", "$2a$10$hash", Role.ADMIN));

		bootstrap.run(null);

		assertThat(admins()).extracting(User::getEmail).containsExactly("other.admin@zaika.test");
	}

	@Test
	@Transactional
	void withoutSettingsNoAdminIsCreatedAndStartupGoesOn() throws Exception {
		users.deleteAll(admins());

		bootstrap("", "").run(null);
		bootstrap("admin@zaika.test", " ").run(null);

		assertThat(admins()).isEmpty();
	}

	@Test
	@Transactional
	void aWeakPasswordStopsStartupWithAClearMessage() {
		users.deleteAll(admins());

		assertThatThrownBy(() -> bootstrap("admin@zaika.test", "letmeinnow").run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("ADMIN_PASSWORD is too weak")
			.hasMessageNotContaining("letmeinnow");
		assertThatThrownBy(() -> bootstrap("admin@zaika.test", "a1" + "x".repeat(71)).run(null))
			.isInstanceOf(IllegalStateException.class);
		assertThat(admins()).isEmpty();
	}

	@Test
	@Transactional
	void anEmailThatBelongsToAnotherRoleIsNeverPromoted() {
		users.deleteAll(admins());
		users.save(new User("Asha Patil", "asha@zaika.test", "9876543211", "$2a$10$hash", Role.CUSTOMER));

		assertThatThrownBy(() -> bootstrap("ASHA@zaika.test", "Kolhapur@2026").run(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("asha@zaika.test already belongs to a non-admin account");
		assertThat(users.findByEmail("asha@zaika.test").orElseThrow().getRole()).isEqualTo(Role.CUSTOMER);
		assertThat(admins()).isEmpty();
	}

	@Test
	@Transactional
	void aMalformedOrOverlongEmailStopsStartupWithAClearMessage() {
		users.deleteAll(admins());

		for (String email : List.of("admin.zaika.test", "admin@" + ("abcdefghij".repeat(4) + ".").repeat(6) + "test")) {
			assertThatThrownBy(() -> bootstrap(email, "Kolhapur@2026").run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ADMIN_EMAIL is not a valid email address");
		}
		assertThat(admins()).isEmpty();
	}

	private AdminBootstrap bootstrap(String email, String password) {
		return new AdminBootstrap(users, passwordEncoder, validator, email, password);
	}

	private List<User> admins() {
		return users.findAll().stream().filter(user -> user.getRole() == Role.ADMIN).toList();
	}

}
