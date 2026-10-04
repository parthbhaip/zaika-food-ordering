package com.zaika;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.thymeleaf.extras.springsecurity6.dialect.SpringSecurityDialect;

/** Proves the extra T01 dependencies are wired: htmx WebJar via webjars-locator-lite and the Thymeleaf security dialect. */
@SpringBootTest
@AutoConfigureMockMvc
class StarterDependenciesTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ApplicationContext context;

	@Test
	@WithMockUser
	void htmxIsServedFromWebJarWithoutVersionInPath() throws Exception {
		mvc.perform(get("/webjars/htmx.org/dist/htmx.min.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("htmx")));
	}

	@Test
	void thymeleafSpringSecurityDialectIsRegistered() {
		assertThat(context.getBeansOfType(SpringSecurityDialect.class)).hasSize(1);
	}

}
