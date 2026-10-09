package com.zaika.owner;

import java.math.BigDecimal;

import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.zaika.config.AppProperties;

/** Model values every owner page may show: the platform's free-delivery threshold (fee copy on sign-up, profile, dashboard). */
@ControllerAdvice(basePackageClasses = OwnerModelAdvice.class)
class OwnerModelAdvice {

	private final AppProperties app;

	OwnerModelAdvice(AppProperties app) {
		this.app = app;
	}

	@ModelAttribute("freeDeliveryThreshold")
	BigDecimal freeDeliveryThreshold() {
		return app.freeDeliveryThreshold();
	}

}
