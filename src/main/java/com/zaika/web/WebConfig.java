package com.zaika.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Content pages with no model: About, Contact, the legal pages and "Partner with us". */
@Configuration(proxyBeanMethods = false)
class WebConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(ViewControllerRegistry registry) {
		registry.addViewController("/about").setViewName("pages/about");
		registry.addViewController("/contact").setViewName("pages/contact");
		registry.addViewController("/terms").setViewName("pages/terms");
		registry.addViewController("/privacy").setViewName("pages/privacy");
		registry.addViewController("/refund-policy").setViewName("pages/refund-policy");
		registry.addViewController("/partner-with-us").setViewName("pages/partner");
	}

}
