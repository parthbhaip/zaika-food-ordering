package com.zaika.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.zaika.security.ZaikaUserDetailsService;
import com.zaika.user.Role;
import com.zaika.user.User;
import com.zaika.user.UserRepository;

/** T38: the admin dashboard of a brand-new platform (its own empty database): zeros and friendly empty states. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:admindashempty;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminDashboardEmptyTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	UserRepository users;

	@Autowired
	ZaikaUserDetailsService userDetails;

	@Test
	void aNewPlatformShowsZerosAndEmptyStates() throws Exception {
		User admin = users.save(new User("Aditi Kulkarni", "first-admin@zaika.test", "9890011223", "hash", Role.ADMIN));
		String html = mvc.perform(get("/admin").with(user(userDetails.loadUserByUsername(admin.getEmail()))))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replaceAll("\\s+", " ");
		assertThat(html).contains("<dt>Orders today</dt> <dd class=\"owner-stat-value\">0</dd>")
			.contains("<dt>GMV today</dt> <dd class=\"owner-stat-value\">₹0</dd>")
			.contains("<dt>GMV, last 7 days</dt> <dd class=\"owner-stat-value\">₹0</dd>")
			.contains("<dt>Customers</dt> <dd class=\"owner-stat-value\">0</dd>")
			.contains("<div class=\"card owner-stat\"> <dt>Pending approvals</dt> <dd class=\"owner-stat-value\">0</dd>")
			.contains("0 restaurants · 0 riders")
			.contains("<strong>0</strong> orders, 21 Sep to 4 Oct")
			.contains("No orders in the last 14 days yet.")
			.contains("No sales in the last 30 days yet.")
			.contains("No orders yet. The latest ten orders on Zaika show up here.")
			.doesNotContain("class=\"revenue-chart-bar", "class=\"admin-top\"", "class=\"table\"");
		// an empty week still gets a sensible scale: 10, 5 and 0 orders
		assertThat(html).contains("<div class=\"revenue-chart-axis\"> <span>10</span><span>5</span><span>0</span> </div>");
	}

}
