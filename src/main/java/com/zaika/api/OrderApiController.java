package com.zaika.api;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.MapBindingResult;
import org.springframework.validation.SmartValidator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.zaika.cart.CartService;
import com.zaika.common.Formatters;
import com.zaika.config.AppProperties;
import com.zaika.order.CancelOrderForm;
import com.zaika.order.CheckoutForm;
import com.zaika.order.CheckoutService;
import com.zaika.order.Order;
import com.zaika.order.OrderHistoryService;
import com.zaika.order.OrderStatus;
import com.zaika.order.OrderTrackingService;
import com.zaika.order.PaymentMethod;
import com.zaika.review.Review;
import com.zaika.review.ReviewForm;
import com.zaika.review.ReviewService;
import com.zaika.security.CurrentUser;

/**
 * The signed-in customer's orders (CUSTOMER tokens only), through the website's own services: place the cart as an
 * order, the history, one order, live tracking, cancel while the restaurant has not accepted it, and rate it once it
 * is delivered. Another customer's order code is a 404, the same as an unknown one.
 */
@RestController
@RequestMapping("/api/v1/orders")
class OrderApiController {

	private final CheckoutService checkout;

	private final OrderHistoryService history;

	private final OrderTrackingService tracking;

	private final ReviewService reviews;

	private final SmartValidator validator;

	private final Formatters fmt;

	private final Clock clock;

	private final String baseUrl;

	OrderApiController(CheckoutService checkout, OrderHistoryService history, OrderTrackingService tracking,
			ReviewService reviews, SmartValidator validator, Formatters fmt, Clock clock, AppProperties app) {
		this.checkout = checkout;
		this.history = history;
		this.tracking = tracking;
		this.reviews = reviews;
		this.validator = validator;
		this.fmt = fmt;
		this.clock = clock;
		this.baseUrl = app.baseUrl();
	}

	/**
	 * Places the cart as an order, priced and checked again on the server. 201 with the order; cash orders go
	 * straight to the restaurant, online ones wait for payment at {@code paymentUrl}. Sending the same
	 * {@code idempotencyKey} again answers 200 with the order it already placed. 409 when the cart, coupon or total
	 * changed or the kitchen cannot take it ({@code checkCart}: fix the cart first).
	 */
	@PostMapping
	ResponseEntity<OrderResponse> place(@AuthenticationPrincipal CurrentUser me,
			@Valid @RequestBody PlaceOrderRequest body) {
		CheckoutService.Placement placement = checkout.placeOrder(me.getId(), body.toForm());
		String code = placement.order().getCode();
		OrderResponse order = detail(me, code);
		return placement.repeat() ? ResponseEntity.ok(order)
				: ResponseEntity.created(URI.create("/api/v1/orders/" + code)).body(order);
	}

	/** The customer's orders, newest first, {@link OrderHistoryService#PAGE_SIZE} a page. */
	@GetMapping
	PageResponse<OrderSummary> list(@AuthenticationPrincipal CurrentUser me,
			@RequestParam(defaultValue = "1") int page) throws BindException {
		if (page < 1) {
			BindException errors = new BindException(new MapBindingResult(new HashMap<>(), "query"));
			errors.rejectValue("page", "min", "Pages start at 1.");
			throw errors;
		}
		LocalDateTime now = LocalDateTime.now(clock);
		return PageResponse.of(history.history(me.getId(), page - 1),
				order -> OrderSummary.of(order, now, baseUrl, zone()));
	}

	@GetMapping("/{code}")
	OrderResponse order(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		return detail(me, code);
	}

	@GetMapping("/{code}/tracking")
	TrackingResponse track(@AuthenticationPrincipal CurrentUser me, @PathVariable String code) {
		return TrackingResponse.of(tracking.track(me.getId(), code), fmt, zone());
	}

	/**
	 * Cancels the order while it is PLACED, with one of the tracking answer's {@code cancelReasons} ({@code details}
	 * required for "Something else"). Answers with the tracking; an order already cancelled answers the same way. 409
	 * once the restaurant has accepted it, or while it still waits for payment (cancel on the payment page).
	 */
	@PostMapping("/{code}/cancel")
	TrackingResponse cancel(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestBody CancelOrderForm form) throws BindException {
		BindException errors = new BindException(new BeanPropertyBindingResult(form, "cancel"));
		form.validate(errors);
		if (errors.hasErrors()) {
			OrderTrackingService.Tracking t = tracking.track(me.getId(), code); // 404 for someone else's order
			if (!t.canCancel()) {
				throw cannotCancel(t.order()); // the rule wins over the input, as on the website
			}
			throw errors;
		}
		OrderTrackingService.CancelResult result = tracking.cancel(me.getId(), code, form.fullReason());
		if (!result.cancelled() && result.order().getStatus() != OrderStatus.CANCELLED) {
			throw cannotCancel(result.order());
		}
		return track(me, code);
	}

	/** Rates a delivered order once: 201 with the review. 409 when it is not delivered yet or already rated. */
	@PostMapping("/{code}/review")
	ResponseEntity<ReviewResponse> review(@AuthenticationPrincipal CurrentUser me, @PathVariable String code,
			@RequestBody ReviewForm form) throws BindException {
		BindException errors = new BindException(new BeanPropertyBindingResult(form, "review"));
		validator.validate(form, errors);
		if (errors.hasErrors()) {
			String problem = reviews.problem(history.detail(me.getId(), code).order()); // 404 for someone else's
			if (problem != null) {
				throw ApiExceptionHandler.conflict(problem, Map.of());
			}
			throw errors;
		}
		Review review = reviews.post(me.getId(), code, form.getStars(), form.getComment());
		return ResponseEntity.created(URI.create("/api/v1/orders/" + code)).body(ReviewResponse.of(review, zone()));
	}

	private OrderResponse detail(CurrentUser me, String code) {
		OrderHistoryService.Detail detail = history.detail(me.getId(), code);
		Order order = detail.order();
		Review review = order.getStatus() == OrderStatus.DELIVERED ? reviews.ofOrder(order.getId()).orElse(null)
				: null;
		return OrderResponse.of(detail, review, baseUrl, zone());
	}

	private RuntimeException cannotCancel(Order order) {
		if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
			return ApiExceptionHandler.conflict("This order is waiting for payment. Pay, switch to cash or cancel it "
					+ "on its payment page.", Map.of("reason", "AWAITING_PAYMENT", "paymentUrl",
							baseUrl + "/pay/" + order.getCode()));
		}
		return ApiExceptionHandler.conflict("Too late to cancel: this order is now “" + order.getStatus().getLabel()
				+ "”. Need help with it? Contact us.", Map.of("reason", "TOO_LATE"));
	}

	private ZoneId zone() {
		return clock.getZone();
	}

	/**
	 * The checkout. {@code addressId} is one of the customer's saved addresses (add a new one with
	 * {@code POST /api/v1/addresses} first); {@code expectedTotal} is the cart's {@code bill.grandTotal} the customer
	 * saw, so an order is never placed for another amount; {@code idempotencyKey} is a new random UUID per checkout
	 * (resend it on a retry).
	 */
	record PlaceOrderRequest(
			@NotNull(message = "Choose one of your saved addresses (GET /api/v1/addresses).")
			@Positive(message = "Choose one of your saved addresses (GET /api/v1/addresses).") Long addressId,
			@NotNull(message = "Choose how you want to pay: COD or ONLINE.") PaymentMethod paymentMethod,
			@Size(max = CartService.MAX_INSTRUCTIONS,
					message = "Keep the note for the rider to 250 characters or fewer.") String deliveryNote,
			@NotNull(message = "Send a new random UUID for each checkout.")
			@Pattern(regexp = CheckoutForm.KEY_PATTERN, flags = Pattern.Flag.CASE_INSENSITIVE,
					message = "Send a new random UUID for each checkout, like 3f2b8c1e-4d5a-4b6c-9d7e-8f9a0b1c2d3e.") String idempotencyKey,
			@NotNull(message = "Send the total you showed the customer (the cart's bill.grandTotal).")
			BigDecimal expectedTotal) {

		CheckoutForm toForm() {
			CheckoutForm form = new CheckoutForm();
			form.setAddressId(addressId);
			form.setPaymentMethod(paymentMethod);
			form.setDeliveryNote(deliveryNote);
			form.setIdempotencyKey(idempotencyKey.toLowerCase(Locale.ROOT));
			form.setExpectedTotal(expectedTotal);
			return form;
		}

	}

}
