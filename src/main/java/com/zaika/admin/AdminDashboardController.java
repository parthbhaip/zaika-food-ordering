package com.zaika.admin;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The admin's home ({@code /admin}): platform KPIs, the orders and top-restaurants charts and the latest orders. */
@Controller
class AdminDashboardController {

	private final AdminDashboardService dashboard;

	AdminDashboardController(AdminDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/admin")
	String dashboard(Model model) {
		model.addAttribute("d", dashboard.dashboard());
		return "admin/dashboard";
	}

}
