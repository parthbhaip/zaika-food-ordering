package com.zaika.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;

class CancelOrderFormTest {

	static Errors validate(CancelOrderForm form) {
		Errors errors = new BeanPropertyBindingResult(form, "cancelForm");
		form.validate(errors);
		return errors;
	}

	static CancelOrderForm form(String reason, String details) {
		CancelOrderForm form = new CancelOrderForm();
		form.setReason(reason);
		form.setDetails(details);
		return form;
	}

	@Test
	void aListedReasonIsEnough() {
		CancelOrderForm form = form("Ordered by mistake", "   ");
		assertThat(validate(form).hasErrors()).isFalse();
		assertThat(form.getDetails()).isNull();
		assertThat(form.fullReason()).isEqualTo("Ordered by mistake");
	}

	@Test
	void detailsAreAddedToTheReasonOrStandInForSomethingElse() {
		assertThat(form("Wrong delivery address", " Flat 4B, not 4A ").fullReason())
			.isEqualTo("Wrong delivery address: Flat 4B, not 4A");
		assertThat(form(CancelOrderForm.OTHER, "Plans changed").fullReason()).isEqualTo("Plans changed");
	}

	@Test
	void aMissingOrUnknownReasonIsRejected() {
		assertThat(validate(form(null, null)).getFieldError("reason").getDefaultMessage())
			.isEqualTo("Choose why you are cancelling.");
		assertThat(validate(form("Because", null)).hasFieldErrors("reason")).isTrue();
	}

	@Test
	void somethingElseNeedsDetailsAndDetailsHaveALimit() {
		assertThat(validate(form(CancelOrderForm.OTHER, " ")).getFieldError("details").getDefaultMessage())
			.isEqualTo("Tell us why you are cancelling.");
		assertThat(validate(form("Ordered by mistake", "x".repeat(200))).hasErrors()).isFalse();
		assertThat(validate(form("Ordered by mistake", "x".repeat(201))).getFieldError("details").getDefaultMessage())
			.isEqualTo("Keep it to 200 characters or fewer.");
		// the longest stored reason still fits the order's column
		assertThat(form("Restaurant is taking too long", "x".repeat(200)).fullReason().length())
			.isLessThanOrEqualTo(Order.MAX_REASON);
	}

}
